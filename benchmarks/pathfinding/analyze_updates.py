#!/usr/bin/env python3
"""Analyze archived schema-3 block-updates sessions without running Minecraft.

Usage: python3 analyze_updates.py SESSION [SESSION ...] [--output DIRECTORY]
Only derived files are written; the default is SESSION/update-analysis.
"""

import argparse
import csv
import hashlib
from html import escape
import importlib.util
import json
import math
from pathlib import Path
import statistics


spec = importlib.util.spec_from_file_location("pathbench_run", Path(__file__).with_name("run.py"))
run = importlib.util.module_from_spec(spec)
spec.loader.exec_module(run)

MODES = ("collision", "same-shape", "none")
CONTROLS = ("entities", "seed", "warmup_ticks", "measure_ticks", "update_interval")
COUNTERS = tuple(k for k in run.UPDATE_COUNTERS if k not in ("alive_end", "pending_end"))
OUTPUTS = ("summary.json", "tick_samples.csv", "timeline.svg")


def distribution(values):
    """Nearest-rank percentiles; never sort the caller's samples in place."""
    ordered = sorted(values)
    return {"count": len(ordered), "mean": statistics.mean(ordered) if ordered else None,
            **{f"p{q}": ordered[math.ceil(len(ordered) * q / 100) - 1] if ordered else None
               for q in (50, 95, 99)}, "max": ordered[-1] if ordered else None}


def ratio(numerator, denominator):
    return numerator / denominator if denominator else None


def summarize(measurements):
    rows = [row for data in measurements for row in data["tick_samples"]]
    counters = {key: sum(data[key] for data in measurements) for key in COUNTERS}
    entities = measurements[0]["entities"]
    return {
        "distributions": {
            "tick_ms": distribution(r["mspt"] for r in rows),
            "block_update_ms": distribution(r["block_update_ms"] for r in rows if r["update_event"]),
            "update_tick_ms": distribution(r["mspt"] for r in rows if r["update_event"]),
            "non_update_tick_ms": distribution(r["mspt"] for r in rows if not r["update_event"]),
            "recompute_tick_ms": distribution(r["mspt"] for r in rows if r["recompute_searches"]),
            "quiet_tick_ms": distribution(r["mspt"] for r in rows if not r["recompute_searches"]),
        },
        "counters_sum": counters,
        "actual_recompute_ratio": ratio(counters["recompute_searches"], counters["recompute_calls"]),
        "pending_end_sum": sum(data["pending_end"] for data in measurements),
        "navigation_active_fraction": statistics.mean(r["navigating"] for r in rows) / entities,
        "moving_fraction": statistics.mean(r["moving"] for r in rows) / entities,
        "distance_moved_sum": math.fsum(r["distance_moved"] for r in rows),
        "distance_moved_per_tick_mean": statistics.mean(r["distance_moved"] for r in rows),
        "search_ms_sum": math.fsum(r["search_ms"] for r in rows),
    }


def load_sessions(sessions):
    records, seen = [], set()
    reference = None
    for session in sessions:
        manifests = sorted(session.glob("*/manifest.json"))
        if not manifests:
            raise ValueError(f"No run manifests in {session}")
        for path in manifests:
            path = path.resolve()
            if path in seen:
                raise ValueError(f"Duplicate input manifest: {path}")
            seen.add(path)
            try:
                raw_manifest = path.read_bytes()
                manifest = json.loads(raw_manifest)
                if manifest["valid"] is not True or manifest["scene"] != "block-updates":
                    raise ValueError("Expected valid=true and scene=block-updates")
                parameters = manifest["parameters"]
                mode = parameters["block_change"]
                if mode not in MODES or manifest.get("block_change", mode) != mode:
                    raise ValueError("Invalid/mismatched block_change")
                if parameters["scene"] != "block-updates":
                    raise ValueError("Parameter scene mismatch")
                controls = {key: parameters[key] for key in CONTROLS}
                if any(type(value) is not int or (key != "seed" and value <= 0)
                       for key, value in controls.items()):
                    raise ValueError("Invalid cohort controls")
                # Compare binary identity, not machine-specific source/archive paths.
                runtime = sorted(value for name, value in manifest["runtime_sha256"].items()
                                 if name.endswith(".jar"))
                mods = sorted((mod["id"], mod["sha256"]) for mod in manifest["mods"])
                hashes = [*runtime, *(digest for _, digest in mods)]
                if (not runtime or not {"pathfinding-benchmark", "fabric-api"} <= {m[0] for m in mods}
                        or any(not isinstance(h, str) or len(h) != 64
                               or any(c not in "0123456789abcdef" for c in h) for h in hashes)):
                    raise ValueError("Missing/invalid runtime or benchmark/API binary hashes")
                controls.update(runtime_jar_sha256=runtime, mods=mods,
                                nt_config_sha256=manifest.get("nt_config", {}).get("sha256"),
                                java_version=manifest.get("java_version"),
                                jvm_args=manifest.get("jvm_args"),
                                affinity_actual=manifest.get("affinity_actual"))
                if reference is not None and controls != reference:
                    differing = [key for key in controls if controls[key] != reference[key]]
                    raise ValueError(f"Cohort controls differ: {', '.join(differing)}")
                reference = controls
                phases, sources = {}, {}
                for phase in ("warmup", "measure"):
                    archive = manifest["results"][phase]
                    source = (path.parent / archive["file"]).resolve()
                    if not source.is_relative_to(path.parent):
                        raise ValueError(f"{phase} archive escapes run directory")
                    raw = source.read_bytes()
                    if hashlib.sha256(raw).hexdigest() != archive["sha256"]:
                        raise ValueError(f"{phase} SHA-256 mismatch")
                    data = json.loads(raw)
                    run.validate_updates(data, parameters[f"{phase}_ticks"], parameters["entities"],
                                         parameters["seed"], parameters["update_interval"], mode)
                    if manifest[phase] != data:
                        raise ValueError(f"{phase} manifest copy disagrees with archive")
                    phases[phase] = data
                    sources[phase] = dict(archive)
                records.append({"mode": mode, "run": str(path.parent), "manifest": str(path),
                                "manifest_sha256": hashlib.sha256(raw_manifest).hexdigest(),
                                "results": sources, "measure": phases["measure"]})
            except (KeyError, TypeError, AttributeError, OSError, ValueError) as error:
                raise ValueError(f"{path}: {error}") from error
    return reference, records


def make_summary(controls, records):
    groups = {}
    for mode in MODES:
        selected = [record for record in records if record["mode"] == mode]
        if not selected:
            continue
        runs = []
        for record in selected:
            data = record["measure"]
            runs.append({key: value for key, value in record.items() if key != "measure"} | {
                **summarize([data]), "pending_end": data["pending_end"],
                "reported_distributions": {key: data[key] for key in run.UPDATE_DISTRIBUTIONS},
            })
        # Only means/scalars are averaged. Percentiles always come from raw pooled samples.
        mean_keys = ("actual_recompute_ratio", "pending_end", "navigation_active_fraction",
                     "moving_fraction", "distance_moved_per_tick_mean", "search_ms_sum")
        means = {key: statistics.mean(r[key] for r in runs) if all(r[key] is not None for r in runs)
                 else None for key in mean_keys}
        means.update(tick_ms_mean=statistics.mean(r["distributions"]["tick_ms"]["mean"] for r in runs),
                     block_update_event_ms_mean=statistics.mean(
                         r["distributions"]["block_update_ms"]["mean"] for r in runs),
                     counters={key: statistics.mean(r["counters_sum"][key] for r in runs) for key in COUNTERS})
        groups[mode] = {"run_count": len(runs), "runs": runs, "run_means": means,
                        "pooled": summarize([r["measure"] for r in selected])}
    baseline = groups.get("none", {}).get("pooled", {}).get("distributions", {}).get("block_update_ms", {}).get("mean")
    for group in groups.values():
        event_mean = group["pooled"]["distributions"]["block_update_ms"]["mean"]
        group["block_update_event_ms_vs_none"] = {
            "mean": event_mean, "none_mean": baseline,
            "difference": event_mean - baseline if baseline is not None else None,
            "ratio": ratio(event_mean, baseline),
        }
    return {"schema": 1, "scene": "block-updates", "controls": controls, "groups": groups,
            "notes": [
                "All statistics and CSV rows use full measurement phases; warmup is validated but excluded.",
                "Percentiles use nearest rank over raw samples, never averages of run percentiles.",
                "run_means are unweighted arithmetic means of per-run values; pooled uses all raw ticks.",
                "reported_distributions preserve individual phase summaries; query/latency samples are not pooled.",
                "actual_recompute_ratio = recompute_searches / recompute_calls; zero denominator is null.",
                "block_update_ms uses scheduled event ticks, including no-change events in none.",
                "quiet_tick_ms means no actual recompute search, not necessarily no patrol or other work.",
                "Matching inputs do not imply lockstep world state, paths, or timing statistics.",
                "Event-time differences are descriptive, not significance tests or causal whole-tick cost estimates.",
                "timeline.svg previews the first 240 ticks of the first listed repeat per mode; axes are separate.",
            ]}


def timeline(records):
    selected = [next((r for r in records if r["mode"] == mode), None) for mode in MODES]
    selected = [r for r in selected if r is not None]
    height = 85 + len(selected) * 370
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="{height}" viewBox="0 0 1200 {height}">',
             '<rect width="100%" height="100%" fill="white"/>',
             '<g font-family="sans-serif" font-size="12" fill="#182633">',
             '<text x="20" y="24" font-size="18">Block updates: raw measurement timeline</text>',
             '<text x="20" y="45">First repeat per mode, first 240 ticks only; summary and CSV use all samples.</text>',
             '<text x="20" y="64">Red dashed lines: scheduled events (none does not change blocks). Separate y-axes; shared scales across modes.</text>']
    previews = [r["measure"]["tick_samples"][:240] for r in selected]
    last_tick = max(rows[-1]["tick"] for rows in previews)
    scales = {key: max(1, max(row[key] for rows in previews for row in rows))
              for key in ("mspt", "path_searches")}
    for index, (record, rows) in enumerate(zip(selected, previews)):
        top = 90 + index * 370
        label = f'{record["mode"]}: {Path(record["run"]).parent.name}/{Path(record["run"]).name}'
        parts.append(f'<text x="20" y="{top}" font-size="15">{escape(label)} ({len(record["measure"]["tick_samples"])} full ticks)</text>')
        for panel, (key, unit, color) in enumerate((("mspt", "MSPT (ms/tick)", "#1765a3"),
                                                    ("path_searches", "Path searches / tick", "#087f63"))):
            y = top + 32 + panel * 160
            maximum = scales[key]
            def x(tick):
                return 85 + (tick - 1) * 1090 / max(1, last_tick - 1)
            parts.append(f'<text x="85" y="{y - 10}">{unit}</text>')
            for fraction in (0, 0.5, 1):
                yy = y + 105 * (1 - fraction)
                parts.append(f'<path d="M85 {yy} H1175" stroke="#dce2e7"/>')
                parts.append(f'<text x="76" y="{yy + 4}" text-anchor="end">{maximum * fraction:.3g}</text>')
            for row in rows:
                if row["update_event"]:
                    parts.append(f'<path d="M{x(row["tick"]):.2f} {y} v105" stroke="#ce3030" stroke-dasharray="4 3"/>')
            points = " ".join(f'{x(row["tick"]):.2f},{y + 105 * (1 - row[key] / maximum):.2f}' for row in rows)
            parts.append(f'<polyline points="{points}" fill="none" stroke="{color}" stroke-width="1.2"/>')
            for tick in sorted({1, last_tick, *range(40, last_tick, 40)}):
                parts.append(f'<text x="{x(tick):.2f}" y="{y + 123}" text-anchor="middle">{tick}</text>')
            parts.append(f'<text x="630" y="{y + 140}" text-anchor="middle">Measurement tick</text>')
    return "\n".join([*parts, "</g></svg>\n"])


def analyze(sessions, output=None):
    sessions = [Path(session).resolve() for session in sessions]
    if not sessions:
        raise ValueError("At least one session is required")
    output = (Path(output) if output is not None else sessions[0] / "update-analysis").resolve()
    if any(session.is_relative_to(output) for session in sessions):
        raise ValueError("Output must not equal or contain an input session")
    controls, records = load_sessions(sessions)
    if any(output.is_relative_to(Path(record["run"])) for record in records):
        raise ValueError("Output must not be inside an archived run")
    for name in OUTPUTS:
        target = output / name
        if target.is_symlink() or (target.exists() and (not target.is_file() or target.stat().st_nlink > 1)):
            raise ValueError(f"Unsafe derived output: {target}")
    summary = make_summary(controls, records)
    svg = timeline(records)
    columns = list(dict.fromkeys(key for record in records for row in record["measure"]["tick_samples"] for key in row))
    if {"mode", "run"} & set(columns):
        raise ValueError("Raw columns conflict with CSV provenance columns")
    columns.remove("tick")
    output.mkdir(parents=True, exist_ok=True)
    (output / "summary.json").write_text(json.dumps(summary, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    with (output / "tick_samples.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=["mode", "run", "tick", *columns])
        writer.writeheader()
        for record in records:
            for row in record["measure"]["tick_samples"]:
                writer.writerow({"mode": record["mode"], "run": record["run"], **row})
    (output / "timeline.svg").write_text(svg, encoding="utf-8")
    return summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sessions", nargs="+", type=Path, help="Archived session directories containing run manifests")
    parser.add_argument("--output", type=Path, help="Derived output directory (default: first session/update-analysis)")
    args = parser.parse_args(argv)
    try:
        analyze(args.sessions, args.output)
    except (OSError, ValueError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
