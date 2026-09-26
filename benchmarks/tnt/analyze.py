#!/usr/bin/env python3
"""Descriptive analysis of an accepted 3 x (200 warmup + 3000 measured) TNT batch.

Usage: python3 -B benchmarks/tnt/analyze.py BATCHDIR
Only analysis.json, windows.csv and timeline.svg are overwritten.
"""

import argparse
import csv
import hashlib
import importlib.util
import io
import json
import math
from pathlib import Path
import statistics
import sys
from xml.sax.saxutils import escape


SERIES = ("mean_ms", "explosions_per_tick", "tnt_entities", "item_entities",
          "entities", "loaded_chunks", "mspt_per_explosion")


def pearson(x, y):
    if len(x) != len(y):
        raise ValueError("Correlation lengths differ")
    if len(x) < 2:
        return None
    a, b = statistics.mean(x), statistics.mean(y)
    xx, yy = sum((v - a) ** 2 for v in x), sum((v - b) ** 2 for v in y)
    if not xx or not yy:
        return None
    return max(-1.0, min(1.0, sum((v - a) * (w - b) for v, w in zip(x, y))
                         / math.sqrt(xx * yy)))


def summarize(values):
    if not values:
        raise ValueError("Cannot summarize an empty series")
    mean = statistics.mean(values)
    stddev = statistics.pstdev(values)
    return {"n": len(values), "mean": mean, "min": min(values), "max": max(values),
            "range": max(values) - min(values), "population_stddev": stddev,
            "population_cv_percent": 100 * stddev / mean if mean else None,
            "sample_cv_percent": 100 * statistics.stdev(values) / mean
            if len(values) > 1 and mean else None}


def time_series(values):
    result = summarize(values)
    first, last = statistics.mean(values[:5]), statistics.mean(values[-5:])
    result.update(first5_mean=first, last5_mean=last,
                  last5_vs_first5_percent=100 * (last / first - 1) if first else None,
                  lag1_pearson=pearson(values[:-1], values[1:]))
    return result


def tick_distribution(values):
    ordered = sorted(values)
    return {**summarize(values), **{f"p{q}": ordered[math.ceil(len(values) * q / 100) - 1]
                                  for q in (50, 95, 99)}}


def validate_counts(rows, ticks):
    if len(rows) != 16 or len(ticks) != 3200:
        raise ValueError("Expected 16 total windows and 3200 raw ticks per run")
    for i, row in enumerate(rows):
        if (row["index"] != i or row["ticks"] != 200
                or row["phase"] != ("warmup" if i == 0 else "measure")):
            raise ValueError("Expected one warmup and 15 measured 200-tick windows")


def analyze_batch(folder):
    # Reuse only the runner's read-only report validation, never launch/provenance code.
    spec = importlib.util.spec_from_file_location("tnt_report_reader", Path(__file__).with_name("run.py"))
    runner = importlib.util.module_from_spec(spec)
    bytecode = sys.dont_write_bytecode
    try:
        sys.dont_write_bytecode = True
        spec.loader.exec_module(runner)
    finally:
        sys.dont_write_bytecode = bytecode
    hashes = {}

    def read(relative):
        data = (folder / relative).read_bytes()
        hashes[relative] = hashlib.sha256(data).hexdigest()
        return data.decode("utf-8")

    def accepted(value, name):
        if value.get("valid") is not True or value.get("rejection") is not None:
            raise ValueError(f"Not an accepted valid report: {name}")

    batch = json.loads(read("summary.json"))
    origin = json.loads(read("provenance.json"))
    accepted(batch, "batch")
    if (batch["requested_repeats"] != 3 or batch["completed_repeats"] != 3
            or len(batch["runs"]) != 3 or batch["mode"] != "ticks"
            or runner.schedule(batch["parameters"]) != ("ticks", 200, 3000, 200)):
        raise ValueError("Expected exactly three completed fixed-tick long runs")
    reference = None
    all_windows, all_ticks, runs = [], [], []
    for number, entry in enumerate(batch["runs"], 1):
        name = f"{number:02d}"
        accepted(entry, name)
        if entry["manifest"] != f"{name}/manifest.json" or entry["summary"] != f"{name}/summary.json":
            raise ValueError("Expected run directories 01/02/03 in order")
        manifest = json.loads(read(entry["manifest"]))
        summary = json.loads(read(entry["summary"]))
        accepted(manifest, f"{name} manifest")
        accepted(summary, f"{name} summary")
        if manifest["parameters"] != batch["parameters"] or manifest["provenance"] != origin:
            raise ValueError(f"{name}: inconsistent recorded parameters/source provenance")
        identity = manifest["identity"]
        if not identity.get("runtime_sha256") or not identity.get("source_world_sha256") or not identity.get("mods"):
            raise ValueError(f"{name}: missing recorded input hashes")
        expected = {"runtime_sha256": manifest["runtime_sha256"],
                    "source_world_sha256": manifest["source_world"]["files_sha256"],
                    "mods": sorted([[m["id"], m["file"], m["sha256"]] for m in manifest["mods"]]),
                    "nt_config_sha256": manifest.get("nt_config", {}).get("sha256")}
        expected.update({key: manifest[key] for key in
                         ("parameters", "command", "java_version", "server_properties")})
        if any(identity[key] != value for key, value in expected.items()):
            raise ValueError(f"{name}: manifest disagrees with its recorded input identity")
        current = (identity, manifest["affinity_actual"])
        if reference is not None and current != reference:
            raise ValueError(f"{name}: recorded input identities/hashes or affinity differ between runs")
        reference = current
        server = f"{name}/server"
        rows = [json.loads(line) for line in read(f"{server}/tnt-windows.jsonl").splitlines()]
        ticks = [{"tick_index": int(r["tick_index"]), "elapsed_seconds": float(r["elapsed_seconds"]),
                  "mspt": float(r["mspt"]), "explosions": int(r["explosions"])}
                 for r in csv.DictReader(io.StringIO(read(f"{server}/tnt-ticks.csv")))]
        complete = json.loads(read(f"{server}/tnt-complete.json"))
        validate_counts(rows, ticks)
        if (complete["ticks"] != 3200 or complete["windows"] != 16 or complete["mode"] != "ticks"
                or not math.isclose(complete["elapsed_seconds"], rows[-1]["end_seconds"], abs_tol=1e-6)):
            raise ValueError(f"{name}: completion record disagrees with raw data")
        checked = runner.read_report(folder / server, manifest)
        accepted(checked, f"{name} raw report")
        for key in ("measured_ticks", "measured_windows", "explosions", "measured_seconds", "mode", "targets"):
            if summary[key] != checked[key]:
                raise ValueError(f"{name}: summary disagrees with raw data: {key}")
        for key, value in checked["tick_ms"].items():
            if not math.isclose(summary["tick_ms"][key], value, rel_tol=1e-9, abs_tol=1e-6):
                raise ValueError(f"{name}: summary tick distribution mismatch: {key}")
        if entry["measured_ticks"] != 3000 or not math.isclose(entry["mean_mspt"], checked["tick_ms"]["mean"]):
            raise ValueError(f"{name}: batch entry disagrees with raw data")
        measured = [t["mspt"] for t in ticks[200:]]
        windows = rows[1:]
        origin_seconds = windows[0]["start_seconds"]
        for row in windows:
            raw = [t["mspt"] for t in ticks[row["index"] * 200:(row["index"] + 1) * 200]]
            distribution = tick_distribution(raw)
            for key in ("p50", "p95", "p99"):
                if not math.isclose(distribution[key], row[f"{key}_ms"], abs_tol=1e-6):
                    raise ValueError(f"{name}: raw/window percentile mismatch")
            row.update(run=name, measured_window=row["index"],
                       measured_start_minutes=(row["start_seconds"] - origin_seconds) / 60,
                       measured_end_minutes=(row["end_seconds"] - origin_seconds) / 60,
                       measured_mid_minutes=((row["start_seconds"] + row["end_seconds"]) / 2 - origin_seconds) / 60,
                       mspt_per_explosion=row["mean_ms"] / row["explosions_per_tick"]
                       if row["explosions_per_tick"] else None)
        series = {key: time_series([w[key] for w in windows])
                  if all(w[key] is not None for w in windows) else None for key in SERIES}
        seconds = sum(w["seconds"] for w in windows)
        gc_ms = sum(w["gc_ms"] for w in windows)
        means = [w["mean_ms"] for w in windows]
        runs.append({"run": name, "measured_ticks": len(measured), "measured_windows": len(windows),
                     "explosions": sum(t["explosions"] for t in ticks[200:]),
                     "measured_seconds": seconds, "tick_time_ms": sum(measured),
                     "tick_ms": tick_distribution(measured), "series": series,
                     "gc": {"ms": gc_ms, "count": sum(w["gc_count"] for w in windows),
                            "wall_time_fraction": gc_ms / (seconds * 1000)},
                     "correlation": {"mspt_vs_explosions_per_tick": pearson(means, [w["explosions_per_tick"] for w in windows]),
                                     "mspt_vs_tnt_end_count": pearson(means, [w["tnt_entities"] for w in windows])}})
        all_windows.extend(windows)
        all_ticks.extend(measured)
    return {"schema": 1, "batch": folder.name, "validated": True,
            "validation": "All three archived valid markers, input identities/hashes, source provenance, "
                          "parameters, counts, raw sequences, window totals/percentiles and summaries agree. "
                          "Recorded hashes compared only; current source, runtime and world files not rehashed.",
            "methods": {"schedule": "Per run: 200 warmup ticks excluded; 3000 measured ticks; 15 x 200-tick windows.",
                        "percentiles": "Nearest rank on actual measured raw ticks, not averaged window percentiles.",
                        "dispersion": "Population SD/CV describe raw ticks; sample CV uses n-1 for windows/run means. CV is percent.",
                        "drift": "Unweighted mean of first five versus last five measured windows; change = 100*(last/first-1).",
                        "dependence": "Pearson correlations and lag-1 window correlations within each run are descriptive only. "
                                      "Adjacent windows are not assumed independent; no significance tests or confidence intervals.",
                        "snapshots": "Entity/chunk counts are end-window snapshots, not time-averaged populations. GC is a window delta; fraction uses measured wall time.",
                        "normalization": "mspt_per_explosion = window mean MSPT / explosions per tick. "
                                         "Includes fill/apply/physics and all other tick work, not pure explosion cost.",
                        "undefined": "Zero denominator or constant-series correlation is null; normalized series is null if any window has zero explosions.",
                        "causality": "No speedup, JIT or thermal cause is inferred from these observations."},
            "input_sha256": hashes, "runs": runs,
            "pooled": {"measured_ticks": len(all_ticks), "tick_ms": tick_distribution(all_ticks),
                       "explosions": sum(r["explosions"] for r in runs),
                       "measured_seconds": sum(r["measured_seconds"] for r in runs),
                       "between_run_mean_ms": summarize([r["tick_ms"]["mean"] for r in runs]),
                       "window_explosions_per_tick": summarize([w["explosions_per_tick"] for w in all_windows])}}, all_windows


def timeline(windows):
    colors = ("#0072B2", "#D55E00", "#009E73")
    panels = (("MSPT (ms): solid mean, dashed p95", ("mean_ms", "p95_ms")),
              ("Explosions per tick", ("explosions_per_tick",)),
              ("TNT entities (end-window count)", ("tnt_entities",)),
              ("Item entities (end-window count)", ("item_entities",)))
    svg = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1100 1140" role="img">',
           '<title>TNT benchmark measured-window timeline</title>',
           '<desc>Three runs, 15 measured windows each. Warmup excluded. Descriptive observations only.</desc>',
           '<rect width="1100" height="1140" fill="white"/>',
           '<g font-family="sans-serif" font-size="13" fill="#263238">',
           '<text x="90" y="30" font-size="22">TNT benchmark: measured workload and tick time</text>',
           '<text x="90" y="55">200 warmup ticks excluded per run | 200 ticks/window | 15 measured windows/run</text>',
           '<text x="90" y="78">Time starts at measurement; values plotted at window midpoints. Y axes use panel-specific ranges.</text>']
    for i, color in enumerate(colors):
        svg.append(f'<text x="{90 + 130 * i}" y="102" fill="{color}">Run {i + 1:02d}</text>')
    xmax = math.ceil(max(w["measured_end_minutes"] for w in windows) / 5) * 5
    for panel, (label, keys) in enumerate(panels):
        top, height = 150 + panel * 240, 160
        values = [w[key] for w in windows for key in keys]
        low, high = min(values), max(values)
        pad = max((high - low) * 0.12, high * 0.01, 0.1)
        low, high = max(0, low - pad), high + pad
        svg.append(f'<text x="90" y="{top - 16}" font-size="16">{escape(label)}</text>')
        for i in range(5):
            y, value = top + height * i / 4, high - (high - low) * i / 4
            svg.append(f'<path d="M90 {y} H1060" stroke="#e2e6e9"/>')
            svg.append(f'<text x="80" y="{y + 4}" text-anchor="end">{value:.2f}</text>')
        for minute in range(0, xmax + 1, 5):
            x = 90 + 970 * minute / xmax
            svg.append(f'<path d="M{x} {top} v{height}" stroke="#eef0f2"/>')
            svg.append(f'<text x="{x}" y="{top + height + 22}" text-anchor="middle">{minute}</text>')
        for number, color in enumerate(colors, 1):
            rows = [w for w in windows if w["run"] == f"{number:02d}"]
            for index, key in enumerate(keys):
                points = [(90 + 970 * w["measured_mid_minutes"] / xmax,
                           top + height * (high - w[key]) / (high - low)) for w in rows]
                dash = ' stroke-dasharray="6 4"' if index else ""
                coordinates = " ".join(f"{x:.2f},{y:.2f}" for x, y in points)
                svg.append(f'<polyline points="{coordinates}" fill="none" stroke="{color}" stroke-width="2"{dash}/>')
                for row, (x, y) in zip(rows, points):
                    title = f'Run {number:02d}, window {row["index"]}, {key}: {row[key]:.4f}'
                    svg.append(f'<circle cx="{x:.2f}" cy="{y:.2f}" r="2.5" fill="{color}"><title>{escape(title)}</title></circle>')
        svg.append(f'<text x="575" y="{top + height + 44}" text-anchor="middle">Run-relative measured elapsed minutes</text>')
    svg.append('<text x="90" y="1120">Connected windows are temporally ordered observations, not independent replicates or causal evidence.</text></g></svg>')
    return "\n".join(svg) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("batchdir", type=Path)
    args = parser.parse_args()
    try:
        report, windows = analyze_batch(args.batchdir)
        csv_text = io.StringIO(newline="")
        writer = csv.DictWriter(csv_text, fieldnames=list(windows[0]))
        writer.writeheader()
        writer.writerows(windows)
        outputs = {"analysis.json": json.dumps(report, indent=2, sort_keys=True, allow_nan=False) + "\n",
                   "windows.csv": csv_text.getvalue(), "timeline.svg": timeline(windows)}
        for name in outputs:
            path = args.batchdir / name
            if path.is_symlink() or (path.exists() and (not path.is_file() or path.stat().st_nlink != 1)):
                raise ValueError(f"Refusing linked/non-regular output: {path}")
        for name, text in outputs.items():
            (args.batchdir / name).write_text(text, encoding="utf-8", newline="")
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f"Analysis refused: {error}\n")
    print(f"Validated {report['pooled']['measured_ticks']} measured ticks; wrote analysis.json, windows.csv, timeline.svg")


if __name__ == "__main__":
    main()
