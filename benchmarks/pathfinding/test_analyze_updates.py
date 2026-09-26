"""Offline analysis tests. No builds or Minecraft processes are launched."""

import copy
import csv
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


analysis = load("analyze_updates")
fixtures = load("test_run")


class AnalysisTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def session(self, mode="collision", name=None, repeats=1):
        session = self.root / (name or mode)
        for repeat in range(1, repeats + 1):
            folder = session / f"{repeat:02d}-block-updates-{repeat}"
            (folder / "server").mkdir(parents=True)
            manifest = dict(valid=True, scene="block-updates",
                            parameters=dict(scene="block-updates", block_change=mode, entities=64,
                                            seed=8675309, warmup_ticks=80, measure_ticks=100, update_interval=40),
                            runtime_sha256={"server.jar": "a" * 64},
                            mods=[dict(id="pathfinding-benchmark", sha256="b" * 64),
                                  dict(id="fabric-api", sha256="c" * 64)], results={})
            for phase, ticks in (("warmup", 80), ("measure", 100)):
                data = fixtures.update_result(ticks=ticks, change=mode)
                manifest[phase] = data
                raw = json.dumps(data).encode()
                file = f"server/pathbench-{phase}.json"
                (folder / file).write_bytes(raw)
                manifest["results"][phase] = dict(file=file, sha256=hashlib.sha256(raw).hexdigest())
            (folder / "manifest.json").write_text(json.dumps(manifest))
        return session

    def edit_manifest(self, session, change):
        path = sorted(session.glob("*/manifest.json"))[0]
        manifest = json.loads(path.read_text())
        change(manifest)
        path.write_text(json.dumps(manifest))

    def edit_phase(self, session, change, phase="measure", repeat=0):
        path = sorted(session.glob("*/manifest.json"))[repeat]
        manifest = json.loads(path.read_text())
        data = manifest[phase]
        change(data)
        raw = json.dumps(data).encode()
        (path.parent / manifest["results"][phase]["file"]).write_bytes(raw)
        manifest["results"][phase]["sha256"] = hashlib.sha256(raw).hexdigest()
        path.write_text(json.dumps(manifest))

    def test_nearest_rank_without_mutating_array(self):
        values = list(range(100, 0, -1))
        original = values[:]
        self.assertEqual(analysis.distribution(values), dict(count=100, mean=50.5, p50=50, p95=95, p99=99, max=100))
        self.assertEqual(values, original)
        self.assertEqual(analysis.distribution([7])["p99"], 7)
        self.assertIsNone(analysis.distribution([])["mean"])

    def test_three_modes_repeats_outputs_and_originals_preserved(self):
        sessions = [self.session(mode, repeats=3) for mode in analysis.MODES]
        before = {path: path.read_bytes() for session in sessions for path in session.rglob("*.json")}
        summary = analysis.analyze(sessions)
        output = sessions[0] / "update-analysis"
        self.assertEqual(set(p.name for p in output.iterdir()), set(analysis.OUTPUTS))
        for path, content in before.items():
            self.assertEqual(path.read_bytes(), content)
        group = summary["groups"]["collision"]
        self.assertEqual(group["run_count"], 3)
        self.assertEqual(group["pooled"]["distributions"]["tick_ms"]["count"], 300)
        self.assertEqual(group["pooled"]["counters_sum"]["recompute_searches"], 6)
        self.assertEqual(group["pooled"]["distributions"]["block_update_ms"]["count"], 3)
        self.assertEqual(group["pooled"]["distributions"]["recompute_tick_ms"]["count"], 6)
        self.assertEqual(group["pooled"]["distributions"]["quiet_tick_ms"]["mean"], 1)
        self.assertEqual(group["runs"][0]["actual_recompute_ratio"], 2 / 3)
        self.assertEqual(group["runs"][0]["pending_end"], 0)
        self.assertEqual(group["run_means"]["moving_fraction"], 0.5)
        self.assertEqual(group["block_update_event_ms_vs_none"]["difference"], 0.1)
        for mode in ("same-shape", "none"):
            self.assertIsNone(summary["groups"][mode]["runs"][0]["actual_recompute_ratio"])
            self.assertIsNone(summary["groups"][mode]["block_update_event_ms_vs_none"]["ratio"])
        with (output / "tick_samples.csv").open() as stream:
            rows = list(csv.DictReader(stream))
        self.assertEqual(len(rows), 900)
        self.assertEqual(rows[39]["update_event"], "True")
        self.assertEqual(rows[39]["mspt"], "10")
        self.assertEqual(rows[40]["delayed_searches"], "1")
        svg = ET.parse(output / "timeline.svg")
        ns = {"s": "http://www.w3.org/2000/svg"}
        self.assertEqual(len(svg.findall(".//s:polyline", ns)), 6)
        analysis.analyze(sessions)  # Only derived files may be overwritten.
        self.assertEqual(json.loads((output / "summary.json").read_text())["groups"], summary["groups"])

    def test_pooled_percentiles_not_mean_of_percentiles_and_no_state_lockstep(self):
        session = self.session("none", repeats=2)
        def change(data):
            data["schedule_hash"] = "different evolving state"
            for row in data["tick_samples"]:
                row["mspt"] = 100
            for key in ("tick_ms", "update_tick_ms", "non_update_tick_ms", "quiet_tick_ms"):
                data[key] = dict(mean=100, p50=100, p95=100, p99=100, max=100)
        self.edit_phase(session, change, repeat=1)
        summary = analysis.analyze([session])
        group = summary["groups"]["none"]
        self.assertEqual(group["pooled"]["distributions"]["tick_ms"]["mean"], 50.5)
        self.assertEqual(group["pooled"]["distributions"]["tick_ms"]["p95"], 100)
        self.assertEqual([r["distributions"]["tick_ms"]["p95"] for r in group["runs"]], [1, 100])
        self.assertNotIn("p95", group["run_means"])

    def test_cohort_constant_controls_and_binaries(self):
        first = self.session(name="first")
        mutations = [lambda m, k=key: m["parameters"].__setitem__(k, m["parameters"][k] + 1)
                     for key in analysis.CONTROLS]
        mutations += [lambda m: m["runtime_sha256"].update({"server.jar": "d" * 64}),
                      lambda m: m["mods"][0].update(sha256="d" * 64),
                      lambda m: m["mods"][1].update(sha256="d" * 64)]
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                other = self.session("none", name=f"other-{index}")
                self.edit_manifest(other, mutate)
                with self.assertRaisesRegex(ValueError, "Cohort controls differ"):
                    analysis.analyze([first, other], self.root / "out")
                self.assertFalse((self.root / "out").exists())

    def test_paths_are_not_cohort_controls(self):
        first, second = self.session(), self.session("none")
        def relocate(m):
            m["parameters"].update(output="elsewhere", server_home="other-machine", label="new")
            m["runtime_sha256"] = {"relocated/server.jar": "a" * 64}
            m["mods"][0].update(source="elsewhere.jar", file="renamed.jar")
        self.edit_manifest(second, relocate)
        analysis.analyze([first, second])

    def test_hash_mismatch_rejected_for_both_phases(self):
        for phase in ("warmup", "measure"):
            with self.subTest(phase=phase):
                session = self.session(name=phase)
                source = next(session.glob(f"*/server/pathbench-{phase}.json"))
                source.write_bytes(source.read_bytes() + b"\n")
                with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                    analysis.analyze([session])

    def test_wrong_mode_invalid_manifest_and_misleading_aggregates(self):
        mutations = [lambda m: m.update(valid=1), lambda m: m.update(scene="allay"),
                     lambda m: m["parameters"].update(block_change="none"),
                     lambda m: m.update(block_change="same-shape"),
                     lambda m: m["measure"]["tick_ms"].update(mean=0)]
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                session = self.session(name=str(index))
                self.edit_manifest(session, mutate)
                with self.assertRaises(ValueError):
                    analysis.analyze([session])
        session = self.session(name="false-aggregate")
        self.edit_phase(session, lambda data: data["tick_ms"].update(mean=0))
        with self.assertRaisesRegex(ValueError, "Raw ticks disagree"):
            analysis.analyze([session])

    def test_validator_checks_warmup_and_measure(self):
        session = self.session()
        with patch.object(analysis.run, "validate_updates", wraps=analysis.run.validate_updates) as validate:
            analysis.analyze([session])
        self.assertEqual([call.args[1:] for call in validate.call_args_list],
                         [(80, 64, 8675309, 40, "collision"), (100, 64, 8675309, 40, "collision")])

    def test_output_safety_duplicate_and_empty_inputs(self):
        session = self.session()
        folder = next(session.glob("*/manifest.json")).parent
        for output in (session, session.parent, folder / "generated"):
            with self.subTest(output=output), self.assertRaises(ValueError):
                analysis.analyze([session], output)
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            analysis.analyze([session, session])
        with self.assertRaisesRegex(ValueError, "No run manifests"):
            analysis.analyze([self.root / "missing"])
        output = self.root / "safe"
        output.mkdir()
        (output / "summary.json").symlink_to(folder / "manifest.json")
        with self.assertRaisesRegex(ValueError, "Unsafe derived output"):
            analysis.analyze([session], output)

    def test_preview_240_ticks_but_full_statistics(self):
        data = fixtures.update_result(ticks=400)
        original = copy.deepcopy(data)
        svg = analysis.timeline([dict(mode="collision", run="session/run", measure=data)])
        self.assertIn("400 full ticks", svg)
        root = ET.fromstring(svg)
        lines = root.findall(".//{http://www.w3.org/2000/svg}polyline")
        self.assertEqual(len(lines[0].attrib["points"].split()), 240)
        self.assertEqual(analysis.summarize([data])["distributions"]["tick_ms"]["count"], 400)
        self.assertEqual(data, original)

    def test_default_output_symlink_cannot_overwrite_session_summary(self):
        session = self.session()
        original = session / "summary.json"
        original.write_text("original session summary")
        (session / "update-analysis").symlink_to(session, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "Output must not equal or contain"):
            analysis.analyze([session])
        self.assertEqual(original.read_text(), "original session summary")

    def test_cli_error_and_explicit_output(self):
        session = self.session()
        analysis.main([str(session), "--output", str(self.root / "derived")])
        self.assertTrue((self.root / "derived/summary.json").is_file())
        with patch("sys.stderr", new_callable=io.StringIO), self.assertRaises(SystemExit) as error:
            analysis.main([str(self.root / "missing")])
        self.assertEqual(error.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
