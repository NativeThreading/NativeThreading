"""No Minecraft process is launched by these standard-library tests."""

import copy
from contextlib import ExitStack
import importlib.util
import io
import json
import os
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile

spec = importlib.util.spec_from_file_location("pathbench_run", Path(__file__).with_name("run.py"))
run = importlib.util.module_from_spec(spec)
spec.loader.exec_module(run)


def frame(ident, kind, text=""):
    body = struct.pack("<ii", ident, kind) + text.encode() + b"\0\0"
    return struct.pack("<i", len(body)) + body


class Socket:
    def __init__(self, data):
        self.data = data
        self.sent = []

    def recv(self, size):
        chunk, self.data = self.data[:min(size, 2)], self.data[min(size, 2):]
        return chunk

    def sendall(self, data):
        self.sent.append(data)

    def settimeout(self, timeout):
        self.timeout = timeout

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass


def result(scene="open", ticks=4, requests=2):
    return {"schema": 1, "scene": scene, "ticks": ticks, "requests_per_tick": requests,
            "requests": ticks * requests, "reached": 0 if scene == "blocked" else ticks * requests,
            "partial": ticks * requests if scene == "blocked" else 0, "null_paths": 0,
            "nodes": 100, "checksum": "1234", "query_signatures": [str(i) for i in range(requests)],
            "query_ms": dict(mean=1, p50=0.5, p95=2, p99=3, max=4),
            "search_ms": 10.0, "batch_ms": dict(mean=3, p50=2, p95=4, p99=5, max=6),
            "tick_ms": dict(mean=4, p50=3, p95=5, p99=6, max=7)}


def allay_result(ticks=100, entities=64, seed=8675309):
    drops = (ticks + 19) // 20
    data = {"schema": 2, "scene": "allay", "entities": entities, "ticks": ticks, "seed": seed,
            "entity_ticks": entities * ticks, "gap_entity_ticks": 0,
            "layers": 8, "drop_interval_ticks": 20, "spawned": 8 * drops,
            "picked_up": 8 * (drops - 1), "remaining": 8, "lost": 0,
            "carried_pickups": 3, "merges": 2, "inventory_resets": 1, "inventory_items_removed": 5,
            "alive_end": entities, "dead": 0, "escaped": 0, "path_searches": ticks * 2,
            "path_reached": ticks, "path_partial": ticks - 1, "path_null": 1,
            "search_ms": 30.0, "navigation_active_fraction": 0.5,
            "schedule_hash": f"{seed}:{ticks}",
            "per_layer": [dict(spawned=drops, picked_up=drops - 1, remaining=1,
                               lost=0, alive_end=entities // 8) for _ in range(8)],
            "uncollected": [dict(layer=i, age_ticks=1, age_ms=2.5, status="remaining") for i in range(8)],
            "samples": [dict(tick=t, path_searches=t * 2, picked_up=8 * ((t + 19) // 20 - 1),
                             navigating=entities // 2, remaining=8)
                        for t in [*range(20, ticks, 20), ticks]]}
    for key in run.ALLAY_DISTRIBUTIONS:
        data[key] = dict(mean=1, p50=0.5, p95=2, p99=3, max=4)
    return data


class BuildIsolationTests(unittest.TestCase):
    def test_runner_builds_standalone_fixture(self):
        self.assertEqual(run.BUILD, ["./gradlew", "-p", "benchmarks/pathfinding/mod",
                                     "clean", "assemble", "check"])
        self.assertTrue((run.ROOT / "benchmarks/pathfinding/mod/settings.gradle.kts").is_file())
        self.assertTrue((run.ROOT / "benchmarks/pathfinding/mod/gradle.properties").is_file())

    def test_production_build_has_no_benchmark_wiring(self):
        for name in ("settings.gradle.kts", "build.gradle.kts",
                     "fabric/build.gradle.kts", "neoforge/build.gradle.kts"):
            with self.subTest(file=name):
                self.assertNotIn("benchmark", (run.ROOT / name).read_text().lower())


class RconTests(unittest.TestCase):
    def test_delimiter_waits_for_first_response(self):
        remaining = frame(2, 0, "second") + frame(3, 0)
        sock = Socket(frame(1, 2) + frame(2, 0, "first") + remaining)
        original_send = sock.sendall
        def send(data):
            ident, = struct.unpack("<i", data[4:8])
            if ident == 3:
                self.assertEqual(sock.data, remaining)
            original_send(data)
        sock.sendall = send
        with patch.object(run.socket, "create_connection", return_value=sock):
            self.assertEqual(run.rcon(1, "secret", "list"), "firstsecond")

    def test_short_reads_and_multi_packet_response(self):
        sock = Socket(frame(1, 0) + frame(1, 2) + frame(2, 0, "PATHBENCH ")
                      + frame(2, 0, "READY") + frame(3, 0, "Unknown command"))
        with patch.object(run.socket, "create_connection", return_value=sock):
            self.assertEqual(run.rcon(1, "secret", "pathbench setup open 2"), "PATHBENCH READY")
        self.assertEqual(len(sock.sent), 3)

    def test_eof_never_replays_command(self):
        sock = Socket(frame(1, 2) + frame(2, 0, "truncated")[:-4])
        with patch.object(run.socket, "create_connection", return_value=sock) as connect:
            with self.assertRaises(ConnectionError):
                run.rcon(1, "secret", "pathbench run measure 4")
        connect.assert_called_once()
        self.assertEqual(sum(b"pathbench run" in data for data in sock.sent), 1)

    def test_id_type_auth_and_framing_failures(self):
        cases = [frame(-1, 2), frame(8, 2), frame(1, 2) + frame(9, 0),
                 frame(1, 2) + frame(2, 2), struct.pack("<i", 9), struct.pack("<i", 16395),
                 frame(1, 2)[:-1] + b"x", frame(1, 2) + frame(3, 0), frame(1, 2, "unexpected"),
                 frame(1, 2) + frame(2, 0, "embedded\0null")]
        for data in cases:
            with self.subTest(data=data), patch.object(run.socket, "create_connection", return_value=Socket(data)):
                with self.assertRaises((ConnectionError, PermissionError)):
                    run.rcon(1, "secret", "list")

    def test_short_reads_obey_overall_deadline(self):
        sock = Socket(frame(1, 2))
        with patch.object(run.socket, "create_connection", return_value=sock), \
                patch.object(run.time, "monotonic", side_effect=[0, 0, 2]):
            with self.assertRaisesRegex(TimeoutError, "deadline"):
                run.rcon(1, "secret", "list", timeout=1)
        self.assertEqual(len(sock.sent), 1)  # Only authentication was sent.

    def test_response_and_outgoing_payload_bounds(self):
        sock = Socket(frame(1, 2) + frame(2, 0) * 257)
        with patch.object(run.socket, "create_connection", return_value=sock):
            with self.assertRaisesRegex(ConnectionError, "packet limit"):
                run.rcon(1, "secret", "list")
        for payload in ("x" * 4096, "nul\0"):
            sock = Socket(frame(1, 2))
            with patch.object(run.socket, "create_connection", return_value=sock):
                with self.assertRaises(ValueError):
                    run.rcon(1, "secret", payload)
            self.assertEqual(len(sock.sent), 1)


class ValidationTests(unittest.TestCase):
    def test_each_scene_and_warmup_signatures(self):
        for scene in run.SCENES:
            self.assertEqual(run.validate(result(scene), scene, 4, 2), result(scene))
        warmup = result(ticks=3)
        warmup["checksum"] = "different-with-different-ticks"
        run.validate(result(), "open", 4, 2, warmup["query_signatures"])

    def test_rejects_invalid_results(self):
        replacements = {"schema": [2, True], "scene": ["maze"], "ticks": [3, 4.0],
                        "requests_per_tick": [1], "requests": [7], "null_paths": [1],
                        "reached": [7], "partial": [1], "nodes": [-1, True],
                        "checksum": ["", 123], "query_signatures": [["0"], ["0", ""], [0, 1]],
                        "search_ms": [-1, float("nan"), float("inf"), True],
                        "query_ms": [None, {}, dict(mean=1, p50=3, p95=2, p99=3, max=4),
                                     dict(mean=5, p50=1, p95=2, p99=3, max=4)],
                        "batch_ms": [{}, dict(mean=3, p50=4, p95=2, p99=5, max=6)],
                        "tick_ms": [None, dict(mean=8, p50=3, p95=5, p99=6, max=7)]}
        for key, values in replacements.items():
            for value in values:
                data = result()
                data[key] = value
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    run.validate(data, "open", 4, 2)
        with self.assertRaisesRegex(ValueError, "signatures changed"):
            run.validate(result(), "open", 4, 2, ["different", "1"])
        with self.assertRaises(ValueError):
            run.validate(result("blocked") | {"reached": 1}, "blocked", 4, 2)

    def test_query_distribution_is_required_and_finite(self):
        data = result()
        del data["query_ms"]
        with self.assertRaisesRegex(ValueError, "query_ms"):
            run.validate(data, "open", 4, 2)
        for stat in result()["query_ms"]:
            for value in (-1, float("nan"), float("inf"), True):
                data = result()
                data["query_ms"][stat] = value
                with self.subTest(stat=stat, value=value), self.assertRaisesRegex(ValueError, "query_ms"):
                    run.validate(data, "open", 4, 2)


class AllayValidationTests(unittest.TestCase):
    def test_valid_cohorts_partial_null_paths_and_extra_keys(self):
        for ticks in (100, 101, 12000):
            for entities in (64, 72, 4096):
                data = allay_result(ticks, entities, -(2 ** 63))
                data.update(future_key={"detail": []}, requests=0, query_signatures=[], checksum="")
                self.assertIs(run.validate_allay(data, ticks, entities, -(2 ** 63)), data)
        self.assertEqual(allay_result(entities=64)["schedule_hash"],
                         allay_result(entities=128)["schedule_hash"])

    def test_required_fields_and_identity(self):
        original = allay_result()
        for key in original:
            data = copy.deepcopy(original)
            del data[key]
            with self.subTest(missing=key), self.assertRaises(ValueError):
                run.validate_allay(data, 100, 64, 8675309)
        for key, value in {"schema": 1, "scene": "open", "entities": 72, "ticks": 101,
                           "seed": 0, "layers": 7, "drop_interval_ticks": 21,
                           "alive_end": 63, "dead": 1, "escaped": 1, "lost": 1,
                           "spawned": 48, "schedule_hash": ""}.items():
            with self.subTest(key=key), self.assertRaises(ValueError):
                run.validate_allay(original | {key: value}, 100, 64, 8675309)
        for value in (None, [], "allay", 2):
            with self.subTest(value=value), self.assertRaises(ValueError):
                run.validate_allay(value, 100, 64, 8675309)

    def test_counters_are_nonnegative_integers(self):
        for key in run.ALLAY_COUNTERS:
            for value in (-1, True, 1.5, float("nan"), float("inf"), "1", None):
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    run.validate_allay(allay_result() | {key: value}, 100, 64, 8675309)

    def test_entity_ticks_exact_and_no_simulation_gap(self):
        data = allay_result(ticks=200)
        self.assertEqual(data["entity_ticks"], 12800)
        self.assertEqual(data["gap_entity_ticks"], 0)
        run.validate_allay(data, 200, 64, 8675309)
        for key, values in (("entity_ticks", (0, 12799, 12801, 12800.0, True, None)),
                            ("gap_entity_ticks", (1, -1, 0.0, False, None))):
            missing = data.copy()
            del missing[key]
            with self.subTest(missing=key), self.assertRaisesRegex(ValueError, key):
                run.validate_allay(missing, 200, 64, 8675309)
            for value in values:
                with self.subTest(key=key, value=value), self.assertRaisesRegex(ValueError, key):
                    run.validate_allay(data | {key: value}, 200, 64, 8675309)

    def test_pickup_latency_tick_bound_does_not_bound_milliseconds(self):
        for ticks in (100, 200):
            data = allay_result(ticks=ticks)
            data["pickup_latency_ticks"]["max"] = ticks - 1
            data["pickup_latency_ms"]["max"] = ticks * 1000
            run.validate_allay(data, ticks, 64, 8675309)
            for maximum in (ticks - 0.5, ticks, ticks + 1):
                data["pickup_latency_ticks"]["max"] = maximum
                with self.subTest(ticks=ticks, maximum=maximum), self.assertRaisesRegex(
                        ValueError, "pickup_latency_ticks"):
                    run.validate_allay(data, ticks, 64, 8675309)

    def test_distributions_are_finite_nonnegative_and_ordered(self):
        for key in run.ALLAY_DISTRIBUTIONS:
            for stat in ("mean", "p50", "p95", "p99", "max"):
                for value in (-1, True, float("nan"), float("inf"), None, "1"):
                    data = allay_result()
                    data[key][stat] = value
                    with self.subTest(key=key, stat=stat, value=value), self.assertRaises(ValueError):
                        run.validate_allay(data, 100, 64, 8675309)
                data = allay_result()
                del data[key][stat]
                with self.subTest(key=key, missing=stat), self.assertRaises(ValueError):
                    run.validate_allay(data, 100, 64, 8675309)
            for value in (None, [], {}, dict(mean=5, p50=1, p95=2, p99=3, max=4),
                          dict(mean=1, p50=3, p95=2, p99=3, max=4)):
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    run.validate_allay(allay_result() | {key: value}, 100, 64, 8675309)

    def test_search_time_and_active_fraction(self):
        for key in ("search_ms", "navigation_active_fraction"):
            for value in (-1, True, float("nan"), float("inf"), "1", None):
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    run.validate_allay(allay_result() | {key: value}, 100, 64, 8675309)
        for value in (0, 1):
            run.validate_allay(allay_result() | {"navigation_active_fraction": value}, 100, 64, 8675309)
        with self.assertRaises(ValueError):
            run.validate_allay(allay_result() | {"navigation_active_fraction": 1.01}, 100, 64, 8675309)

    def test_conservation_outcomes_and_required_activity(self):
        for changes, message in (({"picked_up": 31}, "conservation"),
                                 ({"path_null": 2}, "outcomes"),
                                 ({"path_searches": 0, "path_reached": 0, "path_partial": 0, "path_null": 0},
                                  "path_searches > 0"),
                                 ({"picked_up": 0, "remaining": 40}, "picked_up > 0")):
            with self.subTest(changes=changes), self.assertRaisesRegex(ValueError, message):
                run.validate_allay(allay_result() | changes, 100, 64, 8675309)

    def test_per_layer_population_conservation_totals_and_activity(self):
        for changes in ({"spawned": 6}, {"picked_up": 3}, {"alive_end": 7},
                        {"picked_up": 3, "remaining": 2}, {"picked_up": 0, "remaining": 5},
                        {"lost": -1}, {"remaining": True}):
            data = allay_result()
            data["per_layer"][0].update(changes)
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                run.validate_allay(data, 100, 64, 8675309)
        for rows in ([], None, {}, [None] * 8, [{}] * 8, allay_result()["per_layer"][:7]):
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                run.validate_allay(allay_result() | {"per_layer": rows}, 100, 64, 8675309)

    def test_uncollected_are_separate_current_phase_cohorts(self):
        for changes in ({"layer": -1}, {"layer": 8}, {"layer": True}, {"age_ticks": 101},
                        {"age_ticks": -1}, {"age_ticks": 1.5}, {"age_ms": float("nan")},
                        {"age_ms": -1}, {"age_ms": True}, {"status": "picked_up"}, {"status": "lost"}):
            data = allay_result()
            data["uncollected"][0].update(changes)
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                run.validate_allay(data, 100, 64, 8675309)
        for items in (None, {}, [], [None], [{}], allay_result()["uncollected"] * 2):
            with self.subTest(items=items), self.assertRaises(ValueError):
                run.validate_allay(allay_result() | {"uncollected": items}, 100, 64, 8675309)
        data = allay_result()
        data["carried_pickups"] = 100  # Older cohorts do not alter current-phase conservation or latency.
        run.validate_allay(data, 100, 64, 8675309)

    def test_samples_cadence_bounds_totals_and_cumulative_counts(self):
        for index, changes in ((0, {"tick": 0}), (0, {"navigating": 65}), (0, {"remaining": 9}),
                               (0, {"path_searches": True}), (0, {"picked_up": -1}),
                               (1, {"path_searches": 1}), (2, {"picked_up": 0, "remaining": 24}),
                               (-1, {"tick": 99}), (-1, {"path_searches": 199}),
                               (-1, {"picked_up": 31, "remaining": 9})):
            data = allay_result()
            data["samples"][index].update(changes)
            with self.subTest(index=index, changes=changes), self.assertRaises(ValueError):
                run.validate_allay(data, 100, 64, 8675309)
        for samples in (None, {}, [], [None] * 5, [{}] * 5,
                        allay_result()["samples"][:-1], allay_result()["samples"] * 2):
            with self.subTest(samples=samples), self.assertRaises(ValueError):
                run.validate_allay(allay_result() | {"samples": samples}, 100, 64, 8675309)
        data = allay_result()
        data["samples"][0].update(navigating=64, picked_up=0, remaining=8)
        data["samples"][1].update(navigating=0, picked_up=16, remaining=0)
        run.validate_allay(data, 100, 64, 8675309)  # Gauges need not be monotonic.


class InputTests(unittest.TestCase):
    def jar(self, path, ident):
        with zipfile.ZipFile(path, "w") as jar:
            jar.writestr("fabric.mod.json", json.dumps({"id": ident}))
        return path

    def test_only_api_spark_and_explicit_mods(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            (home / "mods").mkdir()
            for ident in ("fabric-api", "spark", "lithium", "carpet", "notenoughpalette", "native-threading"):
                self.jar(home / "mods" / f"{ident}.jar", ident)
            bench = self.jar(home / "benchmark.jar", "pathfinding-benchmark")
            selected = run.select_mods(home, [], bench)
            self.assertEqual([run.mod_id(p) for p in selected], ["fabric-api", "spark", "pathfinding-benchmark"])
            nt = home / "mods/native-threading.jar"
            self.assertIn(nt, run.select_mods(home, [nt], bench))
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                run.select_mods(home, [home / "mods/spark.jar"], bench)
            self.jar(home / "mods/another-api.jar", "fabric-api")
            with self.assertRaisesRegex(ValueError, "exactly one"):
                run.select_mods(home, [], bench)

    def test_deterministic_properties_and_loopback(self):
        args = run.parser().parse_args([])
        text = run.properties(args, "secret")
        self.assertEqual(text, run.properties(args, "secret"))
        values = dict(line.split("=", 1) for line in text.splitlines())
        self.assertEqual(values["server-ip"], "127.0.0.1")
        self.assertEqual(values["pause-when-empty-seconds"], "0")
        self.assertEqual(values["view-distance"], "2")
        self.assertEqual(values["simulation-distance"], "2")
        self.assertEqual(json.loads(values["generator-settings"])["structure_overrides"], [])

    def test_dry_run_does_not_build_write_or_launch(self):
        with patch.object(run.subprocess, "run") as build, patch.object(run.subprocess, "Popen") as launch, \
                patch.object(run.Path, "mkdir") as mkdir, patch.object(run, "save") as save, \
                patch("sys.stdout", new_callable=io.StringIO) as out:
            self.assertEqual(run.main(["--dry-run"]), 0)
        for mock in (build, launch, mkdir, save):
            mock.assert_not_called()
        self.assertEqual(json.loads(out.getvalue())["fresh_jvms"], 9)

    def test_argument_bounds(self):
        for option, value in (("--requests", "257"), ("--warmup-ticks", "0"),
                              ("--measure-ticks", "12001"), ("--repeat", "0")):
            with self.subTest(option=option), patch("sys.stderr", new_callable=io.StringIO):
                with self.assertRaises(SystemExit):
                    run.main(["--dry-run", option, value])

    def test_allay_defaults_bounds_and_search_only_requests(self):
        with patch("sys.stdout", new_callable=io.StringIO) as out:
            self.assertEqual(run.main(["--dry-run", "--scene", "allay"]), 0)
        data = json.loads(out.getvalue())
        self.assertEqual(data["fresh_jvms"], 3)
        self.assertEqual(data["parameters"]["entities"], 64)
        self.assertEqual(data["parameters"]["seed"], 8675309)
        for extra in (["--entities", "56"], ["--entities", "65"], ["--entities", "-64"],
                      ["--warmup-ticks", "99"], ["--measure-ticks", "99"],
                      ["--warmup-ticks", "12001"], ["--measure-ticks", "12001"],
                      ["--seed", str(2 ** 63)], ["--seed", str(-(2 ** 63) - 1)],
                      ["--requests", "32"], ["--requests=32"], ["--scene", "open"]):
            with self.subTest(extra=extra), patch("sys.stderr", new_callable=io.StringIO), \
                    patch.object(run.subprocess, "Popen") as launch, self.assertRaises(SystemExit):
                run.main(["--dry-run", "--scene", "allay", *extra])
            launch.assert_not_called()
        for seed in (-(2 ** 63), 2 ** 63 - 1):
            with patch("sys.stdout", new_callable=io.StringIO):
                self.assertEqual(run.main(["--dry-run", "--scene", "allay", "--entities", "1048576",
                                           "--seed", str(seed), "--warmup-ticks", "100",
                                           "--measure-ticks", "12000"]), 0)

    def test_search_default_is_unchanged(self):
        with patch("sys.stdout", new_callable=io.StringIO) as out:
            self.assertEqual(run.main(["--dry-run"]), 0)
        parameters = json.loads(out.getvalue())["parameters"]
        self.assertEqual(parameters["scene"], ["open", "maze", "blocked"])
        self.assertEqual(parameters["requests"], 32)

    def test_cannot_write_into_source_server(self):
        with patch("sys.stderr", new_callable=io.StringIO), self.assertRaises(SystemExit):
            run.main(["--dry-run", "--server-home", "/tmp/source", "--output", "/tmp/source/results"])

    def test_eula_must_be_unambiguously_accepted_before_any_build(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            for text in ("eula=false\n", "# eula=true\n", "eula=true\neula=false\n"):
                (home / "eula.txt").write_text(text)
                with self.subTest(text=text), patch("sys.stderr", new_callable=io.StringIO), \
                        patch.object(run.subprocess, "run") as build, \
                        patch.object(run.subprocess, "Popen") as launch, patch.object(run.Path, "mkdir") as mkdir:
                    self.assertEqual(run.main(["--server-home", str(home)]), 1)
                for mock in (build, launch, mkdir):
                    mock.assert_not_called()

    def test_each_run_prints_verdict_and_reason(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            home = root / "source"
            home.mkdir()
            (home / "eula.txt").write_text("eula=true\n")
            accepted = dict(scene="open", valid=True, rejection=None, measure=result())
            rejected = dict(scene="open", valid=False, rejection="Setup did not return PATHBENCH READY")
            with patch.object(run, "select_mods", return_value=[]), \
                    patch.object(run, "provenance", return_value={}), \
                    patch.object(run, "run_one", side_effect=[accepted, rejected]), \
                    patch("sys.stdout", new_callable=io.StringIO) as out:
                code = run.main(["--server-home", str(home), "--output", str(root / "output"),
                                 "--skip-build", "--scene", "open", "--repeat", "2"])
            self.assertEqual(code, 1)
            self.assertIn("01-open-1 VALID: all checks passed", out.getvalue())
            self.assertIn("02-open-2 REJECTED: Setup did not return PATHBENCH READY", out.getvalue())

    def test_allay_main_routes_arguments_references_and_summary_with_existing_flags(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            home = root / "source"
            home.mkdir()
            (home / "eula.txt").write_text("eula=true\n")
            mod = self.jar(root / "nt.jar", "native-threading")
            config = root / "nt.json"
            config.write_text('{}')
            results = [dict(scene="allay", valid=True, rejection=None,
                            measure=allay_result(100, 128, -123)) for _ in range(2)]
            with patch.object(run, "select_mods", return_value=[mod]) as select, \
                    patch.object(run, "provenance", return_value={}), \
                    patch.object(run, "run_one", side_effect=results) as one, \
                    patch.object(run.subprocess, "run") as build, \
                    patch.object(run.subprocess, "Popen") as launch, \
                    patch("sys.stdout", new_callable=io.StringIO):
                code = run.main(["--scene", "allay", "--entities", "128", "--seed", "-123",
                                 "--server-home", str(home), "--output", str(root / "output"),
                                 "--skip-build", "--mod", str(mod), "--nt-config", str(config),
                                 "--warmup-ticks", "100", "--measure-ticks", "100", "--repeat", "2"])
            self.assertEqual(code, 0)
            build.assert_not_called()
            launch.assert_not_called()
            select.assert_called_once_with(home, [mod])
            self.assertEqual(one.call_count, 2)
            first, second = [call.args for call in one.call_args_list]
            self.assertEqual(first[2], "allay")
            self.assertEqual((first[0].entities, first[0].seed, first[0].nt_config), (128, -123, str(config)))
            self.assertIsNone(first[-1])
            self.assertIs(second[-1], results[0])
            summary = json.loads(next((root / "output").glob("*/summary.json")).read_text())
            self.assertTrue(summary["valid"])
            self.assertEqual(summary["scenes"]["allay"]["repetitions"], 2)
            self.assertEqual((home / "eula.txt").read_text(), "eula=true\n")


class SummaryTests(unittest.TestCase):
    def test_allay_summary_distributions_counts_and_fractions_without_arrays(self):
        first = dict(scene="allay", valid=True, rejection=None, link="m.json", measure=allay_result())
        second = copy.deepcopy(first)
        second["measure"]["search_ms"] = 60
        rejected = dict(scene="allay", valid=False, rejection="lost", link="bad.json")
        with patch("sys.stderr", new_callable=io.StringIO):
            summary = run.summarize([first, second, rejected])
        self.assertFalse(summary["valid"])
        scene = summary["scenes"]["allay"]
        self.assertEqual(scene["repetitions"], 2)
        self.assertEqual(scene["schedule_hash"], first["measure"]["schedule_hash"])
        for key in ("query_signatures", "checksum", "samples", "per_layer", "uncollected"):
            self.assertNotIn(key, scene)
        self.assertEqual(scene["timings"]["search_ms"]["mean"], 45)
        self.assertEqual(scene["timings"]["path_searches"]["mean"], 200)
        self.assertEqual(scene["timings"]["pickup_fraction"]["mean"], 0.8)
        self.assertEqual(scene["timings"]["navigation_active_fraction"]["mean"], 0.5)
        for kind in run.ALLAY_DISTRIBUTIONS:
            for stat, value in first["measure"][kind].items():
                self.assertEqual(scene["timings"][f"{kind}.{stat}"], {"mean": value, "cv_percent": 0})

    def test_cv_and_rejected_run_exclusion(self):
        first = {"scene": "open", "valid": True, "rejection": None, "link": "one/manifest.json", "measure": result()}
        second = copy.deepcopy(first)
        second["measure"]["search_ms"] = 20
        second["measure"]["query_ms"] = {key: value * 2 for key, value in first["measure"]["query_ms"].items()}
        rejected = dict(scene="open", valid=False, rejection="bad counts", link="bad/manifest.json")
        with patch("sys.stderr", new_callable=io.StringIO) as stderr:
            summary = run.summarize([first, second, rejected])
        self.assertFalse(summary["valid"])
        self.assertEqual(summary["scenes"]["open"]["repetitions"], 2)
        self.assertEqual(summary["scenes"]["open"]["timings"]["search_ms"]["mean"], 15)
        for stat, value in first["measure"]["query_ms"].items():
            timing = summary["scenes"]["open"]["timings"][f"query_ms.{stat}"]
            self.assertEqual(timing["mean"], value * 1.5)
            self.assertAlmostEqual(timing["cv_percent"], 100 * 2 ** 0.5 / 3)
        self.assertIn("exceeds 5%", stderr.getvalue())
        self.assertEqual(summary["runs"][0]["manifest"], "one/manifest.json")

    def test_single_repetition_has_no_cv(self):
        summary = run.summarize([dict(scene="open", valid=True, rejection=None, link="m.json", measure=result())])
        self.assertIsNone(summary["scenes"]["open"]["timings"]["search_ms"]["cv_percent"])


class LifecycleTests(unittest.TestCase):
    def exercise(self, mutate=None, reference=None, stale_profile=False, lost_response=False,
                 server_jar="server.jar", setup_response="PATHBENCH READY", malformed_measure=False,
                 nt_config=None, change_artifacts=None, expect_launch=True,
                 spark_response="", spark_confirmation=True, stale_spark_confirmation=False,
                 scene="open", entities=64, seed=8675309, same_ticks=False):
        with tempfile.TemporaryDirectory() as directory, ExitStack() as stack:
            root = Path(directory)
            home = root / "source"
            home.mkdir()
            for name in ("fabric-server-launch.jar", server_jar, "eula.txt"):
                (home / name).write_text("fixture")
            (home / "fabric-server-launcher.properties").write_text(f"serverJar={server_jar}\n")
            (home / "libraries").mkdir()
            (home / "libraries/dependency.jar").write_bytes(b"library")
            bench = home / "bench.jar"
            with zipfile.ZipFile(bench, "w") as jar:
                jar.writestr(zipfile.ZipInfo("fabric.mod.json"), '{"id":"pathfinding-benchmark"}')
            args = run.parser().parse_args(["--server-home", str(home), "--warmup-ticks", "3", "--measure-ticks", "4"])
            if scene == "allay":
                args.warmup_ticks, args.measure_ticks = 100, 100 if same_ticks else 101
                args.entities, args.seed = entities, seed
            if nt_config is not None:
                config = home / "nt.json"
                config.write_text(nt_config)
                args.nt_config = str(config)
            if change_artifacts:
                change_artifacts(home)
            source_hashes = {str(p): run.sha(p) for p in home.rglob("*") if p.is_file()}
            folder = root / "run"
            server = folder / "server"
            process = Mock(pid=123, returncode=None)
            process.poll.side_effect = lambda: process.returncode
            def stopped(timeout=None):
                process.returncode = 0
            process.wait.side_effect = stopped
            def launch(*positional, **kwargs):
                before = json.loads((folder / "manifest.json").read_text())
                self.assertFalse(before["valid"])
                self.assertIn("runtime_sha256", before)
                self.assertIn("-Dpathbench.enabled=true", before["jvm_args"])
                self.assertEqual(len(before["mods"]), 1)
                self.assertNotIn("secret", before["server_properties"])
                self.assertEqual(kwargs["cwd"], server)
                self.assertEqual((server / "server.jar").read_text(), "fixture")
                self.assertEqual((server / "fabric-server-launcher.properties").read_text(), "serverJar=server.jar\n")
                self.assertFalse((server / "libraries").is_symlink())
                kwargs["stdout"].write(b'Done (1.0s)! For help, type "help"\n')
                if stale_spark_confirmation:
                    kwargs["stdout"].write(b'Previous session \xc3\xa9: Profiler is now running! (async)\n')
                kwargs["stdout"].flush()
                return process
            commands = []
            def rcon(port, password, command=None, timeout=120):
                if command is None:
                    return ""
                commands.append(command)
                if command.startswith(("pathbench setup", "pathbench allay")):
                    return setup_response
                if command.startswith("pathbench run"):
                    phase, ticks = command.split()[2:]
                    if phase == "measure":
                        before = json.loads((folder / "manifest.json").read_text())
                        self.assertIn("confirmed_by", before["spark_start"])
                    if scene == "allay":
                        data = allay_result(int(ticks), args.entities, args.seed)
                        data["schedule_hash"] += f":{phase}"
                    else:
                        data = result(ticks=int(ticks), requests=args.requests)
                    if mutate:
                        mutate(phase, data)
                    payload = "{" if malformed_measure and phase == "measure" else json.dumps(data)
                    (server / f"pathbench-{phase}.json").write_text(payload)
                    if lost_response:
                        raise ConnectionError("lost response")
                    return "PATHBENCH STARTED"
                if "start" in command:
                    with (folder / "console.log").open("ab") as console:
                        console.write(b'[spark-worker-pool-1-thread-1/INFO]: [lightning] '
                                      b'Starting a new profiler, please wait...\n')
                    return spark_response
                profile = server / "fresh.sparkprofile"
                profile.write_bytes(b"profile-data")
                if stale_profile:
                    os.utime(profile, (1, 1))
                return "Profiler stopped"
            def wait(process, probe, timeout, description):
                for _ in range(3):
                    value = probe()
                    if value:
                        return value
                    if description == "fresh Spark startup confirmation" and spark_confirmation and _ == 0:
                        self.assertNotIn(f"pathbench run measure {args.measure_ticks}", commands)
                        with (folder / "console.log").open("ab") as console:
                            console.write(b'[spark-worker-pool-1-thread-1/INFO]: [lightning] '
                                          b'Profiler is now running! (async)\n')
                raise TimeoutError(description)
            stack.enter_context(patch.object(run.platform, "platform", return_value="Linux fixture"))
            stack.enter_context(patch.object(run.platform, "processor", return_value="CPU fixture"))
            stack.enter_context(patch.object(run.subprocess, "check_output", return_value=b"java fixture"))
            popen = stack.enter_context(patch.object(run.subprocess, "Popen", side_effect=launch))
            stack.enter_context(patch.object(run.socket, "socket"))
            stack.enter_context(patch.object(run.os, "sched_getaffinity", return_value={0, 1}))
            stack.enter_context(patch.object(run, "rcon", side_effect=rcon))
            stack.enter_context(patch.object(run, "wait_for", side_effect=wait))
            manifest = run.run_one(args, folder, scene, [bench], {"commit": "fixture"}, {}, reference)
            archived = json.loads((folder / "manifest.json").read_text())
            self.assertEqual(manifest, archived)
            if expect_launch:
                popen.assert_called_once()
                process.stdin.write.assert_called_once_with(b"stop\n")
            else:
                popen.assert_not_called()
                process.stdin.write.assert_not_called()
                self.assertFalse(manifest["valid"])
                self.assertEqual(commands, [])
            process.terminate.assert_not_called()
            self.assertEqual(source_hashes, {str(p): run.sha(p) for p in home.rglob("*") if p.is_file()})
            self.assertIn("rcon.password=<redacted>", (server / "server.properties").read_text())
            if manifest["valid"]:
                self.assertEqual(manifest["spark"]["sha256"], run.sha(folder / manifest["spark"]["file"]))
                for phase in ("warmup", "measure"):
                    output = manifest["results"][phase]
                    self.assertEqual(output["sha256"], run.sha(folder / output["file"]))
            return manifest, commands

    def test_success_manifest_archive_and_owned_cleanup(self):
        manifest, commands = self.exercise()
        self.assertTrue(manifest["valid"], manifest["rejection"])
        self.assertEqual(commands, ["pathbench setup open 32", "pathbench run warmup 3",
                                   "spark profiler start --thread *", "pathbench run measure 4",
                                   "spark profiler stop --save-to-file"])

    def test_allay_routing_manifest_and_phase_hash_independence(self):
        for same_ticks in (False, True):
            manifest, commands = self.exercise(scene="allay", entities=72, seed=-(2 ** 63), same_ticks=same_ticks)
            self.assertTrue(manifest["valid"], manifest["rejection"])
            self.assertEqual(commands, [f"pathbench allay 72 {-(2 ** 63)}", "pathbench run warmup 100",
                                        "spark profiler start --thread *",
                                        f"pathbench run measure {100 if same_ticks else 101}",
                                        "spark profiler stop --save-to-file"])
            self.assertNotEqual(manifest["warmup"]["schedule_hash"], manifest["measure"]["schedule_hash"])
            self.assertEqual(manifest["measure"]["schema"], 2)
            self.assertEqual(manifest["parameters"]["entities"], 72)

    def test_allay_repetition_compares_schedule_not_fingerprints(self):
        reference, _ = self.exercise(scene="allay")
        def mutate(phase, data):
            data.update(query_signatures=[phase], checksum=phase, requests=0)
        manifest, _ = self.exercise(scene="allay", reference=reference, mutate=mutate)
        self.assertTrue(manifest["valid"], manifest["rejection"])
        for phase in ("warmup", "measure"):
            changed = copy.deepcopy(reference)
            changed[phase]["schedule_hash"] = "changed"
            with self.subTest(phase=phase):
                manifest, _ = self.exercise(scene="allay", reference=changed)
                self.assertFalse(manifest["valid"])
                self.assertIn(f"{phase} schedule_hash differs between repetitions", manifest["rejection"])

    def test_allay_repetition_compares_identity_and_artifacts(self):
        reference, _ = self.exercise(scene="allay", nt_config='{}')
        for key in ("schema", "scene", "ticks", "entities", "seed", "layers", "drop_interval_ticks"):
            changed = copy.deepcopy(reference)
            changed["measure"][key] = "changed"
            with self.subTest(key=key):
                manifest, _ = self.exercise(scene="allay", reference=changed, nt_config='{}')
                self.assertFalse(manifest["valid"])
                self.assertIn("identity differs", manifest["rejection"])
        for key, message in (("runtime_sha256", "Runtime artifacts"), ("mods", "Mod artifacts"),
                             ("nt_config", "NT config")):
            changed = copy.deepcopy(reference)
            if key == "mods":
                changed[key][0]["sha256"] = "changed"
            elif key == "nt_config":
                changed[key]["sha256"] = "changed"
            else:
                changed[key] = {}
            with self.subTest(key=key):
                manifest, _ = self.exercise(scene="allay", reference=changed, nt_config='{}', expect_launch=False)
                self.assertIn(message, manifest["rejection"])

    def test_allay_acknowledgement_and_invalid_result_are_rejected(self):
        manifest, commands = self.exercise(scene="allay", setup_response="ERROR: PATHBENCH READY")
        self.assertFalse(manifest["valid"])
        self.assertEqual(commands, ["pathbench allay 64 8675309"])
        manifest, _ = self.exercise(scene="allay", mutate=lambda phase, data: data.update(escaped=1))
        self.assertFalse(manifest["valid"])
        self.assertIn("escaped", manifest["rejection"])

    def test_allay_lost_response_is_not_replayed(self):
        manifest, commands = self.exercise(scene="allay", lost_response=True)
        self.assertFalse(manifest["valid"])
        self.assertEqual(commands.count("pathbench run warmup 100"), 1)
        self.assertNotIn("pathbench run measure 101", commands)

    def test_signature_drift_is_rejected(self):
        def mutate(phase, data):
            if phase == "measure":
                data["query_signatures"][0] = "changed"
        manifest, _ = self.exercise(mutate=mutate)
        self.assertFalse(manifest["valid"])
        self.assertIn("signatures changed", manifest["rejection"])

    def test_configured_server_jar_is_copied_and_rebased(self):
        manifest, _ = self.exercise(server_jar="minecraft-26.2.jar")
        self.assertTrue(manifest["valid"], manifest["rejection"])
        self.assertTrue(manifest["server_jar_source"].endswith("minecraft-26.2.jar"))

    def test_acknowledgement_must_match_exactly(self):
        manifest, commands = self.exercise(setup_response="ERROR: expected PATHBENCH READY")
        self.assertFalse(manifest["valid"])
        self.assertEqual(commands, ["pathbench setup open 32"])

    def test_signature_drift_between_repetitions_is_rejected(self):
        reference, _ = self.exercise()
        reference["warmup"]["query_signatures"][0] = "changed"
        manifest, _ = self.exercise(reference=reference)
        self.assertFalse(manifest["valid"])
        self.assertIn("between repetitions", manifest["rejection"])

    def test_checksum_drift_between_repetitions_is_rejected(self):
        reference, _ = self.exercise()
        reference["measure"]["checksum"] = "changed"
        manifest, _ = self.exercise(reference=reference)
        self.assertFalse(manifest["valid"])
        self.assertIn("checksum differs", manifest["rejection"])

    def test_identical_artifacts_in_fresh_sandbox_are_accepted(self):
        for config in (None, '{}'):
            with self.subTest(config=config):
                reference, _ = self.exercise(nt_config=config)
                manifest, _ = self.exercise(reference=reference, nt_config=config)
                self.assertTrue(manifest["valid"], manifest["rejection"])
                self.assertNotEqual(reference["mods"][0]["source"], manifest["mods"][0]["source"])

    def test_changed_runtime_and_mod_bytes_rejected_before_launch(self):
        reference, _ = self.exercise()
        for name in ("server.jar", "fabric-server-launch.jar", "libraries/dependency.jar", "bench.jar"):
            def change(home):
                with (home / name).open("ab") as stream:
                    stream.write(b"changed")  # ZIP readers allow trailing bytes; identity must still change.
            with self.subTest(name=name):
                manifest, _ = self.exercise(reference=reference, change_artifacts=change, expect_launch=False)
                self.assertIn("Mod artifacts" if name == "bench.jar" else "Runtime artifacts", manifest["rejection"])

    def test_changed_mod_filename_or_id_rejected_before_launch(self):
        original, _ = self.exercise()
        for key in ("file", "id"):
            reference = copy.deepcopy(original)
            reference["mods"][0][key] = "different"
            with self.subTest(key=key):
                manifest, _ = self.exercise(reference=reference, expect_launch=False)
                self.assertIn("Mod artifacts", manifest["rejection"])

    def test_changed_added_or_removed_nt_config_rejected_before_launch(self):
        for before, after in (('{}', '{"enabled":false}'), (None, '{}'), ('{}', None)):
            with self.subTest(before=before, after=after):
                reference, _ = self.exercise(nt_config=before)
                manifest, _ = self.exercise(reference=reference, nt_config=after, expect_launch=False)
                self.assertIn("NT config", manifest["rejection"])

    def test_nonfinite_json_preserves_rejection_manifest(self):
        manifest, _ = self.exercise(mutate=lambda phase, data: data.update(search_ms=float("nan")))
        self.assertFalse(manifest["valid"])
        self.assertIn("Invalid search_ms", manifest["rejection"])

    def test_malformed_measurement_still_preserves_profile(self):
        manifest, commands = self.exercise(malformed_measure=True)
        self.assertFalse(manifest["valid"])
        self.assertIn("JSONDecodeError", manifest["rejection"])
        self.assertIn("spark", manifest)
        self.assertIn("measure", manifest["results"])
        self.assertEqual(commands[-1], "spark profiler stop --save-to-file")

    def test_stale_spark_is_rejected(self):
        manifest, _ = self.exercise(stale_profile=True)
        self.assertFalse(manifest["valid"])
        self.assertIn("fresh Spark profile", manifest["rejection"])

    def test_async_spark_start_waits_for_fresh_log_and_archives_excerpt(self):
        for response in ("", "Starting a new profiler, please wait..."):
            with self.subTest(response=response):
                manifest, commands = self.exercise(spark_response=response, stale_spark_confirmation=True)
                self.assertTrue(manifest["valid"], manifest["rejection"])
                confirmation = manifest["spark_start"]
                self.assertEqual(confirmation["confirmed_by"], "console.log")
                self.assertGreater(confirmation["log_offset"], 0)
                self.assertIn("Starting a new profiler", confirmation["log_excerpt"])
                self.assertIn("Profiler is now running! (async)", confirmation["log_excerpt"])
                self.assertNotIn("Previous session", confirmation["log_excerpt"])
                self.assertEqual(commands.count("spark profiler start --thread *"), 1)

    def test_stale_spark_start_confirmation_is_rejected(self):
        for response in ("", "Starting a new profiler, please wait..."):
            with self.subTest(response=response):
                manifest, commands = self.exercise(spark_response=response, spark_confirmation=False,
                                                   stale_spark_confirmation=True)
                self.assertFalse(manifest["valid"])
                self.assertIn("fresh Spark startup confirmation", manifest["rejection"])
                self.assertNotIn("Profiler is now running", manifest["spark_start"]["log_excerpt"])
                self.assertNotIn("confirmed_by", manifest["spark_start"])
                self.assertNotIn("pathbench run measure 4", commands)
                self.assertEqual(commands.count("spark profiler start --thread *"), 1)

    def test_synchronous_spark_start_confirmation_needs_no_log_marker(self):
        manifest, _ = self.exercise(spark_response="Profiler is now running!", spark_confirmation=False)
        self.assertTrue(manifest["valid"], manifest["rejection"])
        self.assertEqual(manifest["spark_start"]["confirmed_by"], "rcon")

    def test_explicit_spark_start_error_is_rejected(self):
        for response in ("Error: unable to start profiler", "Profiler is already running!"):
            with self.subTest(response=response):
                manifest, commands = self.exercise(spark_response=response)
                self.assertFalse(manifest["valid"])
                self.assertIn("Spark did not confirm start", manifest["rejection"])
                self.assertNotIn("pathbench run measure 4", commands)

    def test_lost_command_response_is_not_replayed(self):
        manifest, commands = self.exercise(lost_response=True)
        self.assertFalse(manifest["valid"])
        self.assertEqual(commands.count("pathbench run warmup 3"), 1)
        self.assertNotIn("pathbench run measure 4", commands)
        self.assertEqual(manifest["commands"][1]["error"], "lost response")

    def test_wait_bounds_and_process_exit(self):
        with self.assertRaisesRegex(RuntimeError, "exited"):
            run.wait_for(Mock(poll=lambda: 1, returncode=1), lambda: True, 1, "startup")
        with patch.object(run.time, "monotonic", side_effect=[0, 0, 2]), patch.object(run.time, "sleep"), \
                self.assertRaises(TimeoutError):
            run.wait_for(Mock(poll=lambda: None), lambda: False, 1, "startup")

    def test_cleanup_escalates_only_owned_process(self):
        process = Mock(poll=lambda: None)
        process.wait.side_effect = [run.subprocess.TimeoutExpired("server", 30),
                                    run.subprocess.TimeoutExpired("server", 10), 0]
        run.stop(process)
        process.terminate.assert_called_once()
        process.kill.assert_called_once()
        process.stdin.close.assert_called_once()


if __name__ == "__main__":
    unittest.main()
