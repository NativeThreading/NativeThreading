"""Driver tests use synthetic telemetry and temporary copies; never launch Minecraft."""

import copy
from contextlib import ExitStack
import csv
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile

spec = importlib.util.spec_from_file_location("tnt_run", Path(__file__).with_name("run.py"))
run = importlib.util.module_from_spec(spec)
spec.loader.exec_module(run)


def samples(means=(10, 10, 10, 10, 10, 10), counts=None, warmup=30, window=30):
    counts = counts or [3] * len(means)
    rows, ticks = [], []
    for i, (mean, count) in enumerate(zip(means, counts)):
        row = dict(index=i, phase="warmup" if i * window < warmup else "measure",
                   start_seconds=i * window, end_seconds=(i + 1) * window, seconds=window,
                   ticks=count, mean_ms=mean, p50_ms=mean, p95_ms=mean, p99_ms=mean, max_ms=mean,
                   stddev_ms=0, tps_over_window=count / window, explosions=count,
                   explosions_per_tick=1, explosions_per_second=count / window,
                   entities=2, tnt_entities=1, item_entities=1, loaded_chunks=16,
                   heap_used_mb=50, gc_count=0, gc_ms=0)
        rows.append(row)
        for j in range(count):
            ticks.append(dict(tick_index=len(ticks), elapsed_seconds=i * window + (j + 1) * window / count,
                              mspt=mean, explosions=1))
    return rows, ticks


def tick_samples(durations=None, warmup_windows=1, window_ticks=200):
    durations = durations or [120 + i * 7 for i in range(16)]
    rows, ticks = samples([10 + i for i in range(len(durations))],
                          [window_ticks] * len(durations), warmup=warmup_windows, window=1)
    start = 0
    for i, (row, seconds) in enumerate(zip(rows, durations)):
        row.update(start_seconds=start, end_seconds=start + seconds, seconds=seconds,
                   tps_over_window=window_ticks / seconds, explosions_per_second=window_ticks / seconds)
        for j, tick in enumerate(ticks[i * window_ticks:(i + 1) * window_ticks]):
            tick["elapsed_seconds"] = start + (j + 1) * seconds / window_ticks
        start += seconds
    return rows, ticks


def jar(path, ident):
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("fabric.mod.json", json.dumps({"id": ident}))
    return path


class AnalysisTests(unittest.TestCase):
    def test_flat_and_drifting_windows(self):
        rows, ticks = samples(warmup=0)
        flat = run.analyze(rows, ticks, 0, 180, 30)
        self.assertEqual(flat["window_mean_ms"]["sample_cv_percent"], 0)
        self.assertEqual(flat["window_mean_ms"]["ols_percent_per_minute"], 0)
        self.assertEqual(flat["window_mean_ms"]["last_vs_first_percent"], 0)
        rows, ticks = samples((10, 20, 30, 40, 50, 60), warmup=0)
        drift = run.analyze(rows, ticks, 0, 180, 30)
        self.assertAlmostEqual(drift["window_mean_ms"]["ols_ms_per_minute"], 20)
        self.assertAlmostEqual(drift["window_mean_ms"]["ols_percent_per_minute"], 2000 / 35)
        self.assertAlmostEqual(drift["window_mean_ms"]["last_vs_first_percent"], 100 * (55 / 15 - 1))
        self.assertAlmostEqual(drift["window_mean_ms"]["sample_cv_percent"], 100 * (350 ** .5) / 35)
        self.assertEqual(drift["tick_ms"]["ticks_over_50_ms"], 3)
        self.assertEqual(drift["tick_ms"]["p95"], 60)

    def test_weighted_mean_and_warmup_boundary(self):
        rows, ticks = samples((999, 10, 40), (2, 1, 3))
        result = run.analyze(rows, ticks, 30, 60, 30)
        self.assertEqual(result["tick_ms"]["mean"], 32.5)
        self.assertEqual(result["window_mean_ms"]["mean"], 25)
        self.assertEqual(result["tick_ms"]["p50"], 40)
        self.assertEqual(result["tick_ms"]["max"], 40)
        self.assertEqual(result["measured_ticks"], 4)
        self.assertIsNone(result["window_mean_ms"]["first_third"])

    def test_one_window_and_one_based_ticks(self):
        rows, ticks = samples((0,), warmup=0)
        for tick in ticks:
            tick["tick_index"] += 1
        result = run.analyze(rows, ticks, 0, 30, 30)
        self.assertIsNone(result["window_mean_ms"]["sample_cv_percent"])
        self.assertIsNone(result["window_mean_ms"]["ols_percent_per_minute"])

    def test_missing_malformed_and_inconsistent_rows(self):
        rows, ticks = samples()
        for key, value in (("index", 3), ("index", True), ("phase", "measure"), ("ticks", -1),
                           ("ticks", True), ("mean_ms", float("nan")), ("mean_ms", 30),
                           ("start_seconds", 1), ("end_seconds", 29), ("seconds", 0),
                           ("p95_ms", 11), ("tps_over_window", 20), ("entities", 0)):
            data = copy.deepcopy(rows)
            data[0][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                run.analyze(data, ticks, 30, 150, 30)
        for data in (rows[:-1], rows + [rows[-1]]):
            with self.assertRaisesRegex(ValueError, "Window count"):
                run.analyze(data, ticks, 30, 150, 30)
        with self.assertRaisesRegex(ValueError, "Raw tick count"):
            run.analyze(rows, ticks[:-1], 30, 150, 30)
        for key, value in (("tick_index", 44), ("elapsed_seconds", 90),
                           ("mspt", float("inf")), ("explosions", 12), ("mspt", 12)):
            data = copy.deepcopy(ticks)
            data[1][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                run.analyze(rows, data, 30, 150, 30)

    def test_zero_explosions_rejects_or_flags_without_stability_claim(self):
        rows, ticks = samples((10, 10, 10), warmup=0)
        for row in rows:
            row.update(explosions=0, explosions_per_tick=0, explosions_per_second=0)
        for tick in ticks:
            tick["explosions"] = 0
        result = run.analyze(rows, ticks, 0, 90, 30)
        self.assertFalse(result["valid"])
        self.assertIn("inactive", result["rejection"])
        rows[-1].update(explosions=1, explosions_per_tick=1 / 3, explosions_per_second=1 / 30)
        ticks[-1]["explosions"] = 1
        result = run.analyze(rows, ticks, 0, 90, 30)
        self.assertTrue(result["valid"])
        self.assertEqual(result["zero_explosion_windows"], [0, 1])
        self.assertTrue(result["warnings"])

    def test_fresh_report_and_manifest_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            server = Path(directory)
            rows, ticks = samples((10, 20, 30))
            (server / "tnt-windows.jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows))
            (server / "tnt-complete.json").write_text('{"arbitrary_extra": true}')
            with (server / "tnt-ticks.csv").open("w", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=list(ticks[0]))
                writer.writeheader()
                writer.writerows(ticks)
            manifest = dict(schema=1, observer_started_ns=1,
                            parameters=dict(warmup_seconds=30, duration_seconds=60, window_seconds=30))
            self.assertTrue(run.read_report(server, manifest)["valid"])
            for bad in (None, {}, manifest | {"schema": True}, manifest | {"parameters": None},
                        manifest | {"observer_started_ns": True}, manifest | {"parameters": {}},
                        manifest | {"parameters": dict(warmup_seconds=0, duration_seconds=0, window_seconds=0)},
                        manifest | {"parameters": dict(warmup_seconds=0, duration_seconds=60, window_seconds=30)}):
                with self.assertRaises(ValueError):
                    run.read_report(server, bad)
            os.utime(server / "tnt-complete.json", ns=(1, 1))
            with self.assertRaisesRegex(ValueError, "stale"):
                run.read_report(server, manifest | {"observer_started_ns": 2})


class IsolationTests(unittest.TestCase):
    def test_plain_python_dry_run_does_not_create_bytecode(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("tnt", "pathfinding"):
                target = root / "benchmarks" / name / "run.py"
                target.parent.mkdir(parents=True)
                shutil.copy2(run.ROOT / "benchmarks" / name / "run.py", target)
            before = set(root.rglob("*"))
            subprocess.run([sys.executable, str(root / "benchmarks/tnt/run.py"), "--dry-run"],
                           check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                           env={k: v for k, v in os.environ.items() if k != "PYTHONDONTWRITEBYTECODE"})
            self.assertEqual(before, set(root.rglob("*")))

    def test_config_redaction_preserves_settings(self):
        original = {"poolParallelism": 16, "nested": [{"api_key": "private", "enabled": True}]}
        self.assertEqual(run.redact(original),
                         {"poolParallelism": 16, "nested": [{"api_key": "<redacted>", "enabled": True}]})
        self.assertEqual(original["nested"][0]["api_key"], "private")

    def test_dry_run_no_writes_build_or_process_and_defaults(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(run.subprocess, "Popen") as launch, \
                patch.object(run.subprocess, "run") as build, patch("sys.stdout", new_callable=io.StringIO) as out:
            output = Path(directory) / "not-created"
            self.assertEqual(run.main(["--dry-run", "--output", str(output)]), 0)
            data = json.loads(out.getvalue())
            self.assertEqual(data["build"], ["./gradlew", "-p", "benchmarks/tnt/mod", "clean", "assemble", "check"])
            self.assertEqual(data["observer_command"], "tntobserve start 120 900 30")
            self.assertFalse(data["parameters"]["activate_classic"])
            self.assertEqual(data["automatic_mod_ids"], ["fabric-api", "spark"])
            self.assertFalse(output.exists())
            launch.assert_not_called()
            build.assert_not_called()

    def test_invalid_arguments(self):
        for args in (("--window-seconds", "0"), ("--warmup-seconds", "-30"),
                     ("--duration-seconds", "0"), ("--duration-seconds", "61"),
                     ("--warmup-seconds", "2"), ("--game-port", "25595"),
                     ("--output", "~/fabric-server/results")):
            with self.subTest(args=args), patch("sys.stderr", new_callable=io.StringIO), self.assertRaises(SystemExit):
                run.main(["--dry-run", *args])
        with patch("sys.stdout", new_callable=io.StringIO):
            self.assertEqual(run.main(["--dry-run", "--warmup-seconds", "0"]), 0)

    def test_only_explicit_optional_mods(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            (home / "mods").mkdir()
            jars = {ident: jar(home / "mods" / (ident + ".jar"), ident)
                    for ident in ("fabric-api", "spark", "lithium", "carpet", "native-threading")}
            observer = jar(home / "observer.jar", "tnt-observer")
            selected = run.base.select_mods(home, [], observer)
            self.assertEqual(selected, [jars["fabric-api"], jars["spark"], observer])
            self.assertIn(jars["native-threading"], run.base.select_mods(home, [jars["native-threading"]], observer))
            with self.assertRaises(ValueError):
                run.base.select_mods(home, [jars["spark"]], observer)

    def test_copy_materializes_world_and_runtime_without_source_writes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            home, folder = root / "source", root / "output"
            home.mkdir()
            folder.mkdir()
            (home / "world-tnt-backup").mkdir()
            (home / "world-tnt-backup/level.dat").write_bytes(b"original NBT")
            (home / "world-tnt-backup/link").symlink_to("level.dat")
            (home / "libraries").mkdir()
            (home / "libraries/lib.jar").write_bytes(b"lib")
            (home / "eula.txt").write_text("# accepted previously\neula=true\n")
            (home / "fabric-server-launch.jar").write_bytes(b"launcher")
            (home / "actual.jar").write_bytes(b"server")
            (home / "fabric-server-launcher.properties").write_text("serverJar=actual.jar\n")
            observer = jar(root / "observer.jar", "tnt-observer")
            args = run.parser().parse_args(["--server-home", str(home), "--source-world", str(home / "world-tnt-backup")])
            before = {p.relative_to(home): run.base.sha(p) for p in home.rglob("*") if p.is_file()}
            manifest = {}
            run.prepare(args, folder, [observer], "secret", manifest)
            server = folder / "server"
            self.assertEqual((server / "fabric-server-launcher.properties").read_text(), "serverJar=server.jar\n")
            self.assertEqual(manifest["source_world"]["files_sha256"]["level.dat"], before[Path("world-tnt-backup/level.dat")])
            for p in server.rglob("*"):
                self.assertFalse(p.is_symlink())
                if p.is_file():
                    self.assertEqual(p.stat().st_nlink, 1)
            (server / "world/level.dat").write_bytes(b"changed sandbox")
            self.assertEqual(before, {p.relative_to(home): run.base.sha(p) for p in home.rglob("*") if p.is_file()})
            props = run.properties(args, "<redacted>")
            for value in ("enable-command-block=true", "max-chained-neighbor-updates=1000000",
                          "pause-when-empty-seconds=0", "online-mode=false", "server-ip=127.0.0.1"):
                self.assertIn(value, props)
            self.assertNotIn("peaceful", props)
            self.assertNotIn("minecraft:flat", props)

    def test_provenance_includes_both_tnt_files_and_reused_harness(self):
        with patch.object(run.base, "provenance", return_value={"source_files_sha256": {}}):
            origin = run.provenance()
        for name in ("benchmarks/tnt/run.py", "benchmarks/tnt/test_run.py", "benchmarks/pathfinding/run.py"):
            self.assertEqual(origin["source_files_sha256"][name], run.base.sha(run.ROOT / name))


class ProtocolTests(unittest.TestCase):
    def experiment(self, *, warmup=30, activate=False, acknowledgement="TNT_OBSERVER_STARTED",
                   spark_marker=True, zero=False, mode="time", watchdog=False,
                   changed=None, changed_source=False, changed_source_after=False):
        with tempfile.TemporaryDirectory() as directory, ExitStack() as stack:
            folder = Path(directory)
            server = folder / "server"
            args = run.parser().parse_args(["--warmup-seconds", str(warmup), "--duration-seconds", "60"])
            args.mode = mode
            args.source_world = "/unused/offline-world"
            args.activate_classic = activate
            process = Mock(returncode=None, pid=12345)
            process.poll.side_effect = lambda: process.returncode
            def stopped(**kw):
                process.returncode = 0
                if changed_source_after:
                    source.write_text("changed during experiment")
            process.wait.side_effect = stopped
            commands, validations = [], []
            rows, ticks = samples([10] * ((warmup + 60) // 30), warmup=warmup)
            if mode == "ticks":
                rows, ticks = tick_samples()
            warmup_windows = 1 if mode == "ticks" else warmup // 30
            reference = {} if changed else None
            source = folder / "driver.py"
            source.write_text("original source")
            origin = {"source_files_sha256": {str(source): run.base.sha(source)}}
            clock = [0]
            if zero:
                for row in rows:
                    row.update(explosions=0, explosions_per_tick=0, explosions_per_second=0)
                for tick in ticks:
                    tick["explosions"] = 0

            def prepare(args, folder, mods, password, manifest):
                server.mkdir()
                (server / "server.properties").write_text(run.properties(args, password) + "management-server-secret=TEST_SECRET\n")
                (server / "config").mkdir()
                (server / "config/test.json").write_text('{"secret": "TEST_SECRET", "enabled": true}')
                manifest["source_world"] = {"files_sha256": {"level.dat": "test-hash"}}
                manifest["runtime_sha256"] = {"server.jar": "runtime-hash"}
                manifest["mods"] = [{"id": "tnt-observer", "file": "server/mods/tnt-observer.jar", "sha256": "observer-hash"}]
                if changed:
                    reference.update(copy.deepcopy(run.repeat_identity(manifest)))
                    reference[changed] = "different"
                if changed_source:
                    source.write_text("changed source")

            def launch(*a, **kw):
                # Complete manifest must exist before the process could write anything.
                initial = json.loads((folder / "manifest.json").read_text())
                self.assertIn("source_world", initial)
                self.assertIn("launch_at", initial)
                self.assertIn("-Dtnt.observer.enabled=true", a[0])
                self.assertNotIn("pathbench", " ".join(a[0]))
                (folder / "console.log").write_text("Old Profiler is now running\nDone (1.0s)!\n")
                return process

            def write_rows(selected):
                (server / "tnt-windows.jsonl").write_text("".join(json.dumps(r) + "\n" for r in selected))

            def rcon(port, password, command=None, timeout=None):
                if command is None:
                    return ""
                commands.append(command)
                if command == "function tnt_chamber:build":
                    return "Executed 6 command(s) from function 'tnt_chamber:build'"
                if command.startswith(("tntobserve start", "tntobserve ticks")):
                    write_rows(rows[:warmup_windows])
                    return acknowledgement
                if command.startswith("spark profiler start"):
                    if spark_marker:
                        with (folder / "console.log").open("a") as log:
                            log.write("[spark] Profiler is now running\n")
                    return ""  # Actual Spark RCON acknowledgement may be asynchronous.
                if command == "spark profiler stop --save-to-file":
                    (server / "profile.sparkprofile").write_bytes(b"mock-profile")
                    return ""
                self.fail(f"Unexpected workload command: {command}")

            def wait_for(process, probe, timeout, description):
                validations.append(description)
                if mode == "ticks" and description in ("warmup windows", "all observer windows"):
                    self.assertEqual(timeout, 7200 if description == "warmup windows" else 3600)
                if description == "all observer windows":
                    write_rows(rows)
                    with (server / "tnt-ticks.csv").open("w", newline="") as stream:
                        writer = csv.DictWriter(stream, fieldnames=list(ticks[0]))
                        writer.writeheader()
                        writer.writerows(ticks)
                    (server / "tnt-complete.json").write_text(json.dumps({"mode": mode}))
                for _ in range(3):
                    value = probe()
                    if value:
                        if description == "warmup windows":
                            clock[0] = 3600
                        return value
                raise TimeoutError(description)

            stack.enter_context(patch.object(run, "prepare", side_effect=prepare))
            stack.enter_context(patch.object(run, "provenance", return_value=origin))
            stack.enter_context(patch.object(run.platform, "platform", return_value="test-platform"))
            stack.enter_context(patch.object(run.secrets, "token_hex", return_value="TEST_SECRET"))
            stack.enter_context(patch.object(run.subprocess, "check_output", return_value=b"java test"))
            popen = stack.enter_context(patch.object(run.subprocess, "Popen", side_effect=launch))
            stack.enter_context(patch.object(run.os, "sched_getaffinity", return_value={0, 1}))
            stack.enter_context(patch.object(run.socket, "socket"))
            stack.enter_context(patch.object(run.base, "rcon", side_effect=rcon))
            stack.enter_context(patch.object(run.base, "wait_for", side_effect=wait_for))
            stack.enter_context(patch("sys.stdout", new_callable=io.StringIO))
            if watchdog:
                stack.enter_context(patch.object(run.time, "monotonic", side_effect=[0, 7201]))
            elif mode == "ticks":
                stack.enter_context(patch.object(run.time, "monotonic", side_effect=lambda: clock[0]))
            manifest = run.run(args, folder, [], reference=reference)
            summary = json.loads((folder / "summary.json").read_text())
            self.assertEqual(summary["valid"], manifest["valid"])
            self.assertNotIn("TEST_SECRET", (folder / "manifest.json").read_text())
            self.assertNotIn("TEST_SECRET", (server / "server.properties").read_text())
            self.assertNotIn("TEST_SECRET", (server / "config/test.json").read_text())
            self.assertIn("rcon.password=<redacted>", (server / "server.properties").read_text())
            if changed or changed_source:
                popen.assert_not_called()
                process.wait.assert_not_called()
                self.assertFalse(manifest["valid"])
                return manifest, summary, commands, validations
            process.wait.assert_called_once_with(timeout=120)
            process.terminate.assert_not_called()
            process.kill.assert_not_called()
            self.assertEqual(process.stdin.write.call_args.args, (b"stop\n",))
            if "spark" in manifest:
                self.assertEqual((folder / manifest["spark"]["file"]).read_bytes(), b"mock-profile")
            return manifest, summary, commands, validations

    def test_passive_protocol_and_async_spark_acknowledgement(self):
        manifest, summary, commands, checks = self.experiment()
        self.assertTrue(manifest["valid"], manifest["rejection"])
        self.assertEqual(commands, ["tntobserve start 30 60 30", "spark profiler start --thread *",
                                    "spark profiler stop --save-to-file"])
        self.assertEqual(summary["measured_windows"], 2)
        self.assertIn("fresh Spark startup confirmation", checks)
        self.assertEqual(manifest["spark_start"]["observed_windows"], 1)

    def test_zero_warmup_profiles_before_observer_and_explicit_activation(self):
        manifest, _, commands, _ = self.experiment(warmup=0, activate=True)
        self.assertTrue(manifest["valid"], manifest["rejection"])
        self.assertEqual(commands[:3], ["function tnt_chamber:build", "spark profiler start --thread *",
                                       "tntobserve start 0 60 30"])

    def test_bad_ack_or_missing_fresh_spark_marker_rejects(self):
        for options in ({"acknowledgement": "TNT_OBSERVER_STARTED\n"}, {"spark_marker": False}):
            with self.subTest(options=options):
                manifest, _, _, _ = self.experiment(**options)
                self.assertFalse(manifest["valid"])

    def test_inactive_pilot_keeps_raw_results_and_rejects(self):
        manifest, summary, commands, _ = self.experiment(zero=True)
        self.assertFalse(manifest["valid"])
        self.assertIn("inactive", manifest["rejection"])
        self.assertEqual(summary["explosions"], 0)
        self.assertEqual(len(manifest["raw_outputs"]), 3)
        self.assertEqual(commands[-1], "spark profiler stop --save-to-file")

    def test_tick_protocol_uses_generous_shared_wall_clock_budget(self):
        manifest, summary, commands, _ = self.experiment(mode="ticks")
        self.assertTrue(manifest["valid"], manifest["rejection"])
        self.assertEqual(commands[0], "tntobserve ticks 200 3000 200")
        self.assertEqual(summary["measured_ticks"], 3000)
        self.assertEqual(summary["measured_windows"], 15)
        self.assertEqual(manifest["mode"], "ticks")

    def test_watchdog_rejects_and_still_stops_own_process(self):
        manifest, _, commands, _ = self.experiment(watchdog=True)
        self.assertFalse(manifest["valid"])
        self.assertIn("watchdog", manifest["rejection"])
        self.assertEqual(commands, [])

    def test_changed_source_is_rejected_before_launch(self):
        manifest, _, _, _ = self.experiment(changed_source=True)
        self.assertIn("source changed", manifest["rejection"])

    def test_source_changed_during_run_rejects_after_graceful_stop(self):
        manifest, summary, _, _ = self.experiment(changed_source_after=True)
        self.assertFalse(manifest["valid"])
        self.assertIn("source integrity", manifest["rejection"])
        self.assertFalse(summary["valid"])
        self.assertIn("tick_ms", summary)

    def test_changed_repeat_artifacts_are_rejected_before_launch(self):
        for key in ("runtime_sha256", "source_world_sha256", "mods", "nt_config_sha256",
                    "command", "parameters", "java_version", "server_properties", "affinity_available"):
            with self.subTest(key=key):
                manifest, _, _, _ = self.experiment(changed=key)
                self.assertIn("before launch", manifest["rejection"])
                self.assertIn(key, manifest["rejection"])


class TickModeTests(unittest.TestCase):
    def test_varying_wall_windows_accept_exact_measured_tick_target(self):
        rows, ticks = tick_samples()
        summary = run.analyze(rows, ticks, 200, 3000, 200, mode="ticks")
        self.assertTrue(summary["valid"])
        self.assertEqual(summary["mode"], "ticks")
        self.assertEqual(summary["window_ticks"], 200)
        self.assertNotIn("window_seconds", summary)
        self.assertEqual(summary["targets"], {"unit": "ticks", "warmup": 200, "measure": 3000, "window": 200})
        self.assertEqual(summary["measured_ticks"], 3000)
        self.assertEqual(summary["measured_windows"], 15)
        self.assertEqual(summary["tick_ms"]["mean"], 18)
        self.assertEqual(summary["measured_seconds"], sum(row["seconds"] for row in rows[1:]))
        with self.assertRaises(ValueError):
            run.analyze(rows, ticks, 200, 3000, 200)  # Wall-clock checks are still enforced in time mode.

    def test_tick_slope_uses_actual_wall_midpoints(self):
        rows, ticks = tick_samples([20, 70, 130, 30], warmup_windows=1)
        for i, row in enumerate(rows):
            mean = 10 + (row["start_seconds"] + row["end_seconds"]) / 120 * 2
            for key in ("mean_ms", "p50_ms", "p95_ms", "p99_ms", "max_ms"):
                row[key] = mean
            for tick in ticks[i * 200:(i + 1) * 200]:
                tick["mspt"] = mean
        summary = run.analyze(rows, ticks, 200, 600, 200, mode="ticks")
        self.assertAlmostEqual(summary["window_mean_ms"]["ols_ms_per_minute"], 2)

    def test_tick_mode_rejects_short_run_wrong_windows_and_phases(self):
        rows, ticks = tick_samples()
        for data, raw in ((rows[:-1], ticks[:-200]), (rows, ticks[:-1])):
            with self.assertRaises(ValueError):
                run.analyze(data, raw, 200, 3000, 200, mode="ticks")
        for key, value in (("ticks", 199), ("ticks", 201), ("phase", "measure"),
                           ("seconds", 0), ("start_seconds", 1)):
            data = copy.deepcopy(rows)
            data[0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                run.analyze(data, ticks, 200, 3000, 200, mode="ticks")
        with self.assertRaises(ValueError):
            run.analyze(rows, ticks, 200, 3200, 200, mode="ticks")

    def test_tick_completion_must_match_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            server = Path(directory)
            rows, ticks = tick_samples()
            (server / "tnt-windows.jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows))
            with (server / "tnt-ticks.csv").open("w", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=list(ticks[0]))
                writer.writeheader()
                writer.writerows(ticks)
            manifest = {"schema": 1, "mode": "ticks", "observer_started_ns": 1,
                        "parameters": vars(run.parser().parse_args(["--mode", "ticks"]))}
            for completion in ({}, {"mode": "time"}, {"mode": "unknown"}):
                (server / "tnt-complete.json").write_text(json.dumps(completion))
                with self.assertRaisesRegex(ValueError, "mode mismatch"):
                    run.read_report(server, manifest)
            (server / "tnt-complete.json").write_text('{"mode":"ticks", "extra":"ignored"}')
            self.assertEqual(run.read_report(server, manifest)["measured_ticks"], 3000)

    def test_tick_cli_defaults_bounds_and_dry_run(self):
        with patch("sys.stdout", new_callable=io.StringIO) as out:
            self.assertEqual(run.main(["--dry-run", "--mode", "ticks", "--repeat", "3"]), 0)
        data = json.loads(out.getvalue())
        self.assertEqual(data["observer_command"], "tntobserve ticks 200 3000 200")
        self.assertEqual(data["fresh_jvms"], 3)
        self.assertEqual(data["parameters"]["run_timeout_seconds"], 7200)
        for option, value in (("warmup-ticks", "-1"), ("warmup-ticks", "3601"), ("warmup-ticks", "199"),
                              ("measure-ticks", "0"), ("measure-ticks", "86401"), ("measure-ticks", "3001"),
                              ("window-ticks", "0"), ("window-ticks", "601"), ("repeat", "0"),
                              ("run-timeout-seconds", "0")):
            with self.subTest(option=option, value=value), patch("sys.stderr", new_callable=io.StringIO), self.assertRaises(SystemExit):
                run.main(["--dry-run", "--mode", "ticks", "--" + option, value])
        with patch("sys.stdout", new_callable=io.StringIO):
            self.assertEqual(run.main(["--dry-run", "--mode", "ticks", "--warmup-ticks", "0"]), 0)
            self.assertEqual(run.main(["--dry-run", "--mode", "ticks", "--warmup-ticks", "3600",
                                       "--measure-ticks", "86400", "--window-ticks", "600"]), 0)


class RepeatTests(unittest.TestCase):
    def test_repeat_dispatch_builds_once_and_freezes_configuration(self):
        with tempfile.TemporaryDirectory() as directory, ExitStack() as stack:
            root = Path(directory)
            calls = []
            origin = {"source_files_sha256": {}, "commit": "same-commit"}

            def fake_run(args, folder, mods, origin=None, reference=None):
                calls.append((copy.deepcopy(vars(args)), folder, mods, origin, reference))
                i = len(calls)
                manifest = {"valid": True, "rejection": None, "identity": {"frozen": "first"}}
                run.base.save(folder / "manifest.json", manifest)
                run.base.save(folder / "summary.json", {"valid": True, "mode": "ticks",
                                                        "tick_ms": {"mean": 80 + 20 * i}, "measured_ticks": 3000})
                return manifest

            build = stack.enter_context(patch.object(run.subprocess, "run"))
            java = stack.enter_context(patch.object(run.subprocess, "Popen"))
            origin_call = stack.enter_context(patch.object(run, "provenance", return_value=origin))
            select = stack.enter_context(patch.object(run.base, "select_mods", return_value=[run.BENCH_JAR]))
            stack.enter_context(patch.object(run, "run", side_effect=fake_run))
            stack.enter_context(patch("sys.stdout", new_callable=io.StringIO))
            self.assertEqual(run.main(["--output", str(root), "--mode", "ticks", "--repeat", "3", "--activate-classic"]), 0)
            self.assertEqual([call[1].name for call in calls], ["01", "02", "03"])
            self.assertEqual(calls[0][0], calls[1][0])
            self.assertEqual(calls[1][0], calls[2][0])
            self.assertTrue(all(call[3] is origin for call in calls))
            self.assertIsNone(calls[0][4])
            self.assertEqual(calls[1][4], {"frozen": "first"})
            self.assertIs(calls[1][4], calls[2][4])
            self.assertEqual(build.call_count, 1)
            self.assertEqual(build.call_args.args[0], run.BUILD)
            java.assert_not_called()
            origin_call.assert_called_once_with()
            select.assert_called_once()
            batch = json.loads((calls[0][1].parent / "summary.json").read_text())
            self.assertTrue(batch["valid"])
            self.assertEqual(batch["run_mean_mspt"], [100, 120, 140])
            self.assertEqual(batch["mean_mspt"], 120)
            self.assertAlmostEqual(batch["sample_cv_percent"], 100 * 20 / 120)
            self.assertEqual([r["manifest"] for r in batch["runs"]], ["01/manifest.json", "02/manifest.json", "03/manifest.json"])

    def test_single_run_layout_and_failed_batch_stops_dispatch(self):
        for repetitions, accepted in ((1, True), (3, False)):
            with self.subTest(repetitions=repetitions), tempfile.TemporaryDirectory() as directory, ExitStack() as stack:
                calls = []

                def fake_run(args, folder, mods, origin=None, reference=None):
                    calls.append(folder)
                    result = {"valid": accepted, "rejection": None if accepted else "identity changed", "identity": {}}
                    run.base.save(folder / "summary.json", result | {"tick_ms": {"mean": 10}, "measured_ticks": 3000})
                    return result

                stack.enter_context(patch.object(run, "provenance", return_value={"source_files_sha256": {}}))
                stack.enter_context(patch.object(run.base, "select_mods", return_value=[]))
                stack.enter_context(patch.object(run, "run", side_effect=fake_run))
                stack.enter_context(patch("sys.stdout", new_callable=io.StringIO))
                self.assertEqual(run.main(["--output", directory, "--skip-build", "--repeat", str(repetitions)]), 0 if accepted else 1)
                self.assertEqual(len(calls), 1)
                if repetitions == 1:
                    self.assertEqual(calls[0].parent, Path(directory))
                    self.assertFalse((calls[0] / "01").exists())
                    self.assertIn("tick_ms", json.loads((calls[0] / "summary.json").read_text()))
                else:
                    summary = json.loads((calls[0].parent / "summary.json").read_text())
                    self.assertFalse(summary["valid"])
                    self.assertEqual(summary["completed_repeats"], 1)
                    self.assertEqual(summary["run_mean_mspt"], [])

    def test_identity_snapshot_does_not_alias_mutable_configuration(self):
        manifest = {"runtime_sha256": {"server.jar": "before"}, "source_world": {"files_sha256": {"level.dat": "before"}},
                    "mods": [{"id": "observer", "file": "server/mods/observer.jar", "sha256": "before"}],
                    "parameters": {"cpus": "0-15", "mod": []}, "command": ["java", "-Xmx2G"],
                    "java_version": "test-java", "server_properties": "redacted"}
        identity = run.repeat_identity(manifest)
        manifest["parameters"]["mod"].append("changed")
        manifest["runtime_sha256"]["server.jar"] = "after"
        manifest["source_world"]["files_sha256"]["level.dat"] = "after"
        manifest["mods"][0]["sha256"] = "after"
        self.assertEqual(identity["parameters"]["mod"], [])
        self.assertEqual(identity["runtime_sha256"]["server.jar"], "before")
        self.assertEqual(identity["source_world_sha256"]["level.dat"], "before")
        self.assertEqual(identity["mods"][0][2], "before")


class ActivationAckTests(unittest.TestCase):
    def test_accepts_exact_and_legacy_acknowledgements(self):
        self.assertTrue(run.classic_activation_acknowledged("Running function tnt_chamber:build"))
        self.assertTrue(run.classic_activation_acknowledged("  Running function tnt_chamber:build\n"))
        self.assertTrue(run.classic_activation_acknowledged("Executed 6 command(s) from function 'tnt_chamber:build'"))

    def test_rejects_other_or_malformed_text(self):
        for reply in ("", "Unknown command", "Running function other:build",
                      "Executed 6 commands from function 'other:build'",
                      "Executed commands from function 'tnt_chamber:build'",
                      "prefix Running function tnt_chamber:build"):
            with self.subTest(reply=reply):
                self.assertFalse(run.classic_activation_acknowledged(reply))


if __name__ == "__main__":
    unittest.main()
