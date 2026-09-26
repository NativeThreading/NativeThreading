#!/usr/bin/env python3
"""Observe a copied TNT world, without replacing its workload. Standard library only."""

import argparse
import csv
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import platform
import re
import secrets
import shutil
import signal
import socket
import statistics
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("tnt_pathbench_helpers", ROOT / "benchmarks/pathfinding/run.py")
base = importlib.util.module_from_spec(spec)
bytecode_disabled = sys.dont_write_bytecode
try:
    sys.dont_write_bytecode = True  # Even --dry-run must not create the imported helper's __pycache__.
    spec.loader.exec_module(base)
finally:
    sys.dont_write_bytecode = bytecode_disabled
BUILD = ["./gradlew", "-p", "benchmarks/tnt/mod", "clean", "assemble", "check"]
BENCH_JAR = ROOT / "benchmarks/tnt/mod/build/libs/tnt-observer-1.0.jar"
OUTPUTS = ("tnt-windows.jsonl", "tnt-ticks.csv", "tnt-complete.json")
COUNTS = ("ticks", "explosions", "entities", "tnt_entities", "item_entities", "loaded_chunks", "gc_count")
NUMBERS = ("start_seconds", "end_seconds", "seconds", "mean_ms", "p50_ms", "p95_ms", "p99_ms",
           "max_ms", "stddev_ms", "tps_over_window", "explosions_per_tick",
           "explosions_per_second", "heap_used_mb", "gc_ms")


def redact(value):
    if isinstance(value, dict):
        return {k: "<redacted>" if re.search(r"password|secret|token|credential|api.?key", k, re.I)
                else redact(v) for k, v in value.items()}
    if isinstance(value, list):
        return [redact(v) for v in value]
    return value


def properties(args, password):
    values = dict(line.split("=", 1) for line in base.properties(args, password).splitlines())
    # No flat-world fixture, peaceful override, or forced gamerule changes.
    for key in ("level-seed", "level-type", "generator-settings", "generate-structures",
                "difficulty", "gamemode", "view-distance", "simulation-distance"):
        values.pop(key)
    values.update({"enable-command-block": "true", "max-chained-neighbor-updates": "1000000"})
    return "".join(f"{key}={value}\n" for key, value in sorted(values.items()))


def provenance():
    origin = base.provenance()
    paths = [ROOT / "benchmarks/pathfinding/run.py", Path(__file__), Path(__file__).with_name("test_run.py")]
    paths += [p for p in (ROOT / "benchmarks/tnt/mod").rglob("*") if p.is_file()
              and not {"build", ".gradle"}.intersection(p.relative_to(ROOT).parts)]
    origin["source_files_sha256"].update({str(p.relative_to(ROOT)): base.sha(p) for p in paths})
    origin["source_sha256"] = hashlib.sha256(json.dumps(origin["source_files_sha256"], sort_keys=True).encode()).hexdigest()
    return origin


def prepare(args, folder, mods, password, manifest):
    home, source, server = Path(args.server_home), Path(args.source_world), folder / "server"
    if not (source / "level.dat").is_file():
        raise ValueError("Source world must contain level.dat; refusing to generate a replacement world")
    eula = [s.strip() for s in (home / "eula.txt").read_text().splitlines()
            if s.strip() and not s.lstrip().startswith(("#", "!"))]
    if len(eula) != 1 or not re.fullmatch(r"eula\s*=\s*true", eula[0]):
        raise ValueError("server-home must already contain an accepted eula=true")
    server.mkdir()
    for name in ("fabric-server-launch.jar", "eula.txt"):
        shutil.copy2(home / name, server / name)
    jars = set()
    for name in ("fabric-server-launcher.properties", "launcher.properties"):
        path = home / name
        if path.exists():
            lines = [s.strip() for s in path.read_text().splitlines()
                     if s.strip() and not s.lstrip().startswith(("#", "!"))]
            if len(lines) != 1 or not re.fullmatch(r"serverJar\s*=\s*[^\\]+\.jar", lines[0]):
                raise ValueError(f"Unsafe launcher properties: {path}")
            jars.add((home / lines[0].split("=", 1)[1].strip()).resolve())
            (server / name).write_text("serverJar=server.jar\n")
    if len(jars) > 1:
        raise ValueError("Conflicting launcher serverJar settings")
    jar = next(iter(jars), home / "server.jar")
    manifest["server_jar_source"] = str(jar)
    shutil.copy2(jar, server / "server.jar")
    shutil.copytree(home / "libraries", server / "libraries", symlinks=False)
    manifest["runtime_sha256"] = {str(p.relative_to(server)): base.sha(p)
                                  for p in sorted(server.rglob("*")) if p.is_file()}
    # copy2/copytree materialize independent files, including symlink targets, never hardlinks.
    shutil.copytree(source, server / "world", symlinks=False)
    manifest["source_world"] = {"path": str(source), "files_sha256": {
        str(p.relative_to(server / "world")): base.sha(p)
        for p in sorted((server / "world").rglob("*")) if p.is_file()}}
    manifest["source_world"]["note"] = "Hashes of the exact prelaunch world copy; source must be an offline backup."
    (server / "mods").mkdir()
    manifest["mods"] = []
    for mod in mods:
        target = server / "mods" / mod.name
        shutil.copy2(mod, target)
        manifest["mods"].append({"source": str(mod), "file": str(target.relative_to(folder)),
                                 "id": base.mod_id(target), "sha256": base.sha(target)})
    if args.nt_config:
        (server / "config").mkdir()
        target = server / "config/nt.json"
        shutil.copy2(args.nt_config, target)
        manifest["nt_config"] = {"source": args.nt_config, "sha256": base.sha(target),
                                  "content": redact(json.loads(target.read_text()))}
    (server / "server.properties").write_text(properties(args, password))
    (server / "server.properties").chmod(0o600)


def schedule(parameters):
    # Missing mode denotes an archived report from the original time-only driver.
    mode = parameters.get("mode", "time")
    keys = (("warmup_seconds", "duration_seconds", "window_seconds") if mode == "time"
            else ("warmup_ticks", "measure_ticks", "window_ticks"))
    warmup, duration, window = (parameters.get(k) for k in keys)
    if (mode not in ("time", "ticks") or any(type(v) is not int for v in (warmup, duration, window))
            or not 0 <= warmup <= 3600 or not 1 <= duration <= 86400 or not 1 <= window <= 600
            or warmup % window or duration % window):
        raise ValueError("Invalid window parameters: warmup 0..3600, measure 1..86400, window 1..600; require whole windows")
    return mode, warmup, duration, window


def observer_command(args):
    mode, warmup, duration, window = schedule(vars(args))
    return f"tntobserve {'ticks' if mode == 'ticks' else 'start'} {warmup} {duration} {window}"


def check_sources(origin):
    for name, expected in origin["source_files_sha256"].items():
        path = ROOT / name
        if not path.is_file() or base.sha(path) != expected:
            raise ValueError(f"Driver/build source changed since batch provenance capture: {name}")


def repeat_identity(manifest):
    identity = {"runtime_sha256": manifest["runtime_sha256"],
                "source_world_sha256": manifest["source_world"]["files_sha256"],
                "mods": sorted((m["id"], m["file"], m["sha256"]) for m in manifest["mods"]),
                "nt_config_sha256": manifest.get("nt_config", {}).get("sha256"),
                "parameters": manifest["parameters"], "command": manifest["command"],
                "java_version": manifest["java_version"], "server_properties": manifest["server_properties"],
                "affinity_available": sorted(os.sched_getaffinity(0))}
    return json.loads(json.dumps(identity))  # Freeze nested values independently of mutable args/manifests.


def validate_windows(rows, warmup, duration, window, complete=True, mode="time"):
    if mode not in ("time", "ticks"):
        raise ValueError("Invalid observation mode")
    expected = (warmup + duration) // window
    if len(rows) > expected or (complete and len(rows) != expected):
        raise ValueError(f"Window count: expected {expected}, got {len(rows)}")
    previous = 0.0
    for i, row in enumerate(rows):
        if (not isinstance(row, dict) or type(row.get("index")) is not int or row["index"] != i
                or row.get("phase") != ("warmup" if i < warmup // window else "measure")):
            raise ValueError(f"Invalid window index/phase at {i}")
        if any(type(row.get(k)) is not int or row[k] < 0 for k in COUNTS):
            raise ValueError(f"Invalid window counters at {i}")
        if any(type(row.get(k)) not in (int, float) or not math.isfinite(row[k]) or row[k] < 0 for k in NUMBERS):
            raise ValueError(f"Invalid window numbers at {i}")
        if (not row["ticks"] or row["seconds"] <= 0
                or not math.isclose(row["start_seconds"], previous, abs_tol=1e-5)
                or not math.isclose(row["seconds"], row["end_seconds"] - previous, abs_tol=1e-5)
                or (mode == "time" and not (0 <= row["end_seconds"] - (i + 1) * window <= 1 + row["max_ms"] / 1000))
                or (mode == "ticks" and row["ticks"] != window)
                or not (row["p50_ms"] <= row["p95_ms"] <= row["p99_ms"] <= row["max_ms"])
                or row["mean_ms"] > row["max_ms"]
                or row["tnt_entities"] + row["item_entities"] > row["entities"]):
            raise ValueError(f"Inconsistent window boundaries/distribution at {i}")
        for key, expected_rate in (("tps_over_window", row["ticks"] / row["seconds"]),
                                   ("explosions_per_tick", row["explosions"] / row["ticks"]),
                                   ("explosions_per_second", row["explosions"] / row["seconds"])):
            if not math.isclose(row[key], expected_rate, rel_tol=1e-4, abs_tol=1e-4):
                raise ValueError(f"Inconsistent {key} at {i}")
        previous = row["end_seconds"]


def analyze(rows, ticks, warmup, duration, window, mode="time"):
    validate_windows(rows, warmup, duration, window, mode=mode)
    if len(ticks) != sum(r["ticks"] for r in rows):
        raise ValueError("Raw tick count does not match windows")
    previous, offset, measured = -1.0, 0, []
    first_index = ticks[0]["tick_index"]
    if first_index not in (0, 1):
        raise ValueError("Raw tick indices must start at 0 or 1")
    for row in rows:
        batch = ticks[offset:offset + row["ticks"]]
        for i, tick in enumerate(batch, offset):
            if (type(tick["tick_index"]) is not int or tick["tick_index"] != i + first_index
                    or type(tick["explosions"]) is not int or tick["explosions"] < 0
                    or any(type(tick[k]) not in (int, float) or not math.isfinite(tick[k]) or tick[k] < 0
                           for k in ("elapsed_seconds", "mspt"))
                    or tick["elapsed_seconds"] <= previous
                    or tick["elapsed_seconds"] < row["start_seconds"] - 1e-5
                    or tick["elapsed_seconds"] > row["end_seconds"] + 1e-5):
                raise ValueError("Invalid raw tick sequence/boundaries")
            previous = tick["elapsed_seconds"]
        if (sum(t["explosions"] for t in batch) != row["explosions"]
                or not math.isclose(statistics.mean(t["mspt"] for t in batch), row["mean_ms"], abs_tol=1e-4)
                or not math.isclose(max(t["mspt"] for t in batch), row["max_ms"], abs_tol=1e-4)):
            raise ValueError("Raw tick data disagrees with window totals/distribution")
        offset += row["ticks"]
        if row["phase"] == "measure":
            measured.extend(t["mspt"] for t in batch)
    windows = [r for r in rows if r["phase"] == "measure"]
    if mode == "ticks" and len(measured) != duration:
        raise ValueError(f"Measured tick target: expected {duration}, got {len(measured)}")
    means = [r["mean_ms"] for r in windows]
    mean = statistics.mean(means)
    third = len(windows) // 3
    first = statistics.mean(means[:third]) if third else None
    last = statistics.mean(means[-third:]) if third else None
    x = [(r["start_seconds"] + r["end_seconds"]) / 120 for r in windows]
    center = statistics.mean(x)
    denominator = sum((v - center) ** 2 for v in x)
    slope = sum((v - center) * (y - mean) for v, y in zip(x, means)) / denominator if denominator else None
    ordered = sorted(measured)
    zero = [r["index"] for r in windows if not r["explosions"]]
    explosions = sum(r["explosions"] for r in windows)
    return {"schema": 1, "mode": mode, "valid": explosions > 0,
            "rejection": None if explosions else "No measured explosions: original world workload may be inactive; no stability claim.",
            "note": "Descriptive single-run observations, not evidence of workload stability or a speedup. "
                    "Window thirds use floor(N/3) windows; unavailable below three. "
                    "Counts/chunks/heap are end-window snapshots; GC is a window delta. "
                    "Spark has asynchronous boundary margins; see manifest timestamps.",
            "window_ticks" if mode == "ticks" else "window_seconds": window,
            "targets": {"unit": mode, "warmup": warmup, "measure": duration, "window": window},
            "measured_windows": len(windows), "measured_ticks": len(measured),
            "measured_seconds": sum(r["seconds"] for r in windows),
            "explosions": explosions, "zero_explosion_windows": zero,
            "warnings": ["Some measured windows have zero explosions; workload continuity is unproven."] if zero else [],
            "tick_ms": {"mean": sum(r["mean_ms"] * r["ticks"] for r in windows) / len(measured),
                        **{f"p{q}": ordered[math.ceil(len(ordered) * q / 100) - 1] for q in (50, 95, 99)},
                        "max": ordered[-1], "stddev": statistics.pstdev(measured),
                        "ticks_over_50_ms": sum(t > 50 for t in measured)},
            "window_mean_ms": {"mean": mean, "min": min(means), "max": max(means),
                               "sample_cv_percent": 100 * statistics.stdev(means) / mean
                               if len(means) > 1 and mean else None,
                               "first_third": first, "last_third": last,
                               "last_vs_first_percent": 100 * (last / first - 1) if first else None,
                               "ols_ms_per_minute": slope,
                               "ols_percent_per_minute": 100 * slope / mean if slope is not None and mean else None}}


def read_report(server, manifest):
    if (not isinstance(manifest, dict) or type(manifest.get("schema")) is not int
            or manifest["schema"] != 1 or type(manifest.get("observer_started_ns")) is not int):
        raise ValueError("Invalid manifest: missing observer start provenance")
    parameters = manifest.get("parameters", {})
    if not isinstance(parameters, dict):
        raise ValueError("Invalid manifest: window parameters")
    mode, warmup, duration, window = schedule(parameters)
    if manifest.get("mode", mode) != mode:
        raise ValueError("Manifest observation mode mismatch")
    for name in OUTPUTS:
        path = server / name
        if not path.is_file() or path.stat().st_mtime_ns < manifest["observer_started_ns"]:
            raise ValueError(f"Missing/stale observer output: {name}")
    completion = json.loads((server / OUTPUTS[2]).read_text())
    if not isinstance(completion, dict):
        raise ValueError("Malformed completion JSON")
    if completion.get("mode", "time") != mode:
        raise ValueError("Completion observation mode mismatch")
    text = (server / OUTPUTS[0]).read_text()
    if not text.endswith("\n"):
        raise ValueError("Incomplete final window line")
    rows = [json.loads(line) for line in text.splitlines()]
    with (server / OUTPUTS[1]).open(newline="") as stream:
        reader = csv.DictReader(stream)
        if not {"tick_index", "elapsed_seconds", "mspt", "explosions"}.issubset(reader.fieldnames or []):
            raise ValueError("Missing raw tick CSV columns")
        ticks = [{"tick_index": int(r["tick_index"]), "elapsed_seconds": float(r["elapsed_seconds"]),
                  "mspt": float(r["mspt"]), "explosions": int(r["explosions"])} for r in reader]
    return analyze(rows, ticks, warmup, duration, window, mode=mode)


def classic_activation_acknowledged(reply):
    text = reply.strip()
    if text == "Running function tnt_chamber:build":
        return True
    return re.fullmatch(r"Executed \d+ command(?:s|\(s\))? from function 'tnt_chamber:build'", text) is not None


def run(args, folder, mods, origin=None, reference=None):
    server, console = folder / "server", folder / "console.log"
    mode, warmup, duration, window = schedule(vars(args))
    password = secrets.token_hex(24)
    env = {k: v for k, v in os.environ.items() if k not in
           ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH")}
    command = ["taskset", "-c", args.cpus, args.java, f"-Xms{args.heap}", f"-Xmx{args.heap}",
               "--add-modules=jdk.incubator.vector", "-Dtnt.observer.enabled=true",
               "-jar", "fabric-server-launch.jar", "nogui"]
    manifest = {"schema": 1, "mode": mode, "valid": False, "rejection": "incomplete", "started_at": base.stamp(),
                "parameters": vars(args), "provenance": origin if origin is not None else provenance(),
                "command": command, "commands": [],
                "platform": platform.platform(), "cpu_info": Path("/proc/cpuinfo").read_text(),
                "cpu_count": os.cpu_count(), "affinity_requested": args.cpus,
                "uptime": Path("/proc/uptime").read_text(), "load": list(os.getloadavg()),
                "ignored_java_environment": [k for k in os.environ if k not in env],
                "java_version": subprocess.check_output([args.java, "-version"], stderr=subprocess.STDOUT,
                                                         env=env, timeout=30).decode(errors="replace"),
                "server_properties": properties(args, "<redacted>")}
    process, profile, summary = None, None, None
    deadline = None

    def remaining(limit):
        seconds = deadline - time.monotonic()
        if seconds <= 0:
            raise TimeoutError("Per-run wall-clock watchdog expired")
        return min(limit, seconds)

    def wait_for(probe, timeout, description):
        return base.wait_for(process, probe, remaining(timeout), description)

    def save_manifest():
        base.save(folder / "manifest.json", manifest)

    def command_once(text):
        entry = {"at": base.stamp(), "command": text}
        manifest["commands"].append(entry)
        save_manifest()
        try:
            entry["response"] = base.rcon(args.rcon_port, password, text, timeout=remaining(120))
            return entry["response"]
        except Exception as error:
            entry["error"] = str(error)
            raise
        finally:
            entry["completed_at"] = base.stamp()
            save_manifest()

    observed = []

    def progress():
        path = server / OUTPUTS[0]
        text = path.read_text() if path.exists() else ""
        lines = text.splitlines(keepends=True)
        rows = [json.loads(line) for line in lines if line.endswith("\n")]
        if rows[:len(observed)] != observed:
            raise ValueError("Observer windows changed or were truncated")
        validate_windows(rows, warmup, duration, window, complete=False, mode=mode)
        for row in rows[len(observed):]:
            print(f"{row['phase']} window {row['index']}: {row['mean_ms']:.3f} MSPT, "
                  f"p95 {row['p95_ms']:.3f}, {row['ticks']} ticks, {row['explosions']} explosions", flush=True)
            observed.append(row)
        return len(observed)

    def start_spark():
        manifest["spark_start"] = {"at": base.stamp(), "log_offset": console.stat().st_size,
                                    "requested_ns": time.time_ns(), "observed_windows": len(observed)}
        start = manifest["spark_start"]
        reply = command_once("spark profiler start --thread *")
        if re.search(r"\b(already|unable|error|failed|cannot|unknown command)\b", reply, re.I):
            raise ValueError(f"Spark start rejected: {reply}")

        def running():
            progress()
            with console.open("rb") as stream:
                stream.seek(start["log_offset"])
                start["log_excerpt"] = stream.read().decode(errors="replace")
            return re.search(r"\bProfiler is now running\b", reply + start["log_excerpt"], re.I)

        wait_for(running, 30, "fresh Spark startup confirmation")
        start["confirmed_at"] = base.stamp()
        start["confirmed_ns"] = time.time_ns()
        start["note"] = ("Asynchronous start after warmup row publication; first measured ticks may be missed. "
                         "Polling is 250ms plus RCON/async startup, not a guaranteed 500ms margin. "
                         "With zero warmup Spark starts before observation; stop also has a polling/RCON margin.")
        save_manifest()

    try:
        save_manifest()
        prepare(args, folder, mods, password, manifest)
        check_sources(manifest["provenance"])
        manifest["identity"] = repeat_identity(manifest)
        if reference is not None:
            changed = [key for key in manifest["identity"] if manifest["identity"][key] != reference[key]]
            if changed:
                raise ValueError(f"Repetition identity changed before launch: {', '.join(changed)}")
        for port in (args.game_port, args.rcon_port):
            with socket.socket() as check:
                check.bind(("127.0.0.1", port))
        manifest["launch_at"] = base.stamp()
        deadline = time.monotonic() + args.run_timeout_seconds
        save_manifest()  # Full world/runtime/mod/source provenance is durable before Java starts.
        with console.open("wb") as log:
            process = subprocess.Popen(command, cwd=server, stdin=subprocess.PIPE,
                                       stdout=log, stderr=subprocess.STDOUT, env=env)
            manifest["pid"] = process.pid

            def ready():
                if not re.search(r"Done \([^)]+\)!", console.read_text(errors="replace")):
                    return False
                try:
                    base.rcon(args.rcon_port, password, timeout=remaining(2))
                    return True
                except (OSError, ConnectionError):
                    return False

            wait_for(ready, 300, "startup and authenticated RCON")
            manifest["affinity_actual"] = sorted(os.sched_getaffinity(process.pid))
            shutil.copy2(console, folder / "initial-server.log")
            if args.activate_classic:
                reply = command_once("function tnt_chamber:build")
                if not classic_activation_acknowledged(reply):
                    raise ValueError(f"Classic activation not acknowledged: {reply}")
            if any((server / name).exists() for name in OUTPUTS) or list(server.rglob("*.sparkprofile")):
                raise ValueError("Stale observer/Spark outputs before start")
            if warmup == 0:
                start_spark()
            manifest["observer_started_ns"] = time.time_ns()
            manifest["observer_start_at"] = base.stamp()
            reply = command_once(observer_command(args))
            if reply != "TNT_OBSERVER_STARTED":
                raise ValueError(f"Observer did not return exact TNT_OBSERVER_STARTED: {reply!r}")
            if warmup:
                wait_for(lambda: progress() >= warmup // window,
                         args.run_timeout_seconds if mode == "ticks" else warmup + 120, "warmup windows")
                if (server / OUTPUTS[2]).exists():
                    raise ValueError("Observer completed before measured-phase Spark start")
                start_spark()
            wait_for(lambda: (progress(), (server / OUTPUTS[2]).exists())[1],
                     args.run_timeout_seconds if mode == "ticks" else duration + 120, "all observer windows")
            manifest["observer_complete_seen_at"] = base.stamp()
            command_once("spark profiler stop --save-to-file")
            last = None

            def fresh_profile():
                nonlocal last
                files = list(server.rglob("*.sparkprofile"))
                if len(files) > 1:
                    raise ValueError("Ambiguous Spark output")
                state = [(p, p.stat().st_size, p.stat().st_mtime_ns) for p in files]
                stable = state and state == last and state[0][1] > 0 and state[0][2] >= manifest["spark_start"]["requested_ns"]
                last = state
                return files[0] if stable else None

            profile = wait_for(fresh_profile, 120, "fresh Spark profile")
            summary = read_report(server, manifest)
            manifest.update(valid=summary["valid"], rejection=summary["rejection"])
    except (Exception, KeyboardInterrupt) as error:
        manifest.update(valid=False, rejection=f"{type(error).__name__}: {error}")
    finally:
        try:
            if process is not None:
                manifest["commands"].append({"at": base.stamp(), "transport": "stdin", "command": "stop"})
                # Large TNT worlds need time to save. The shared helper now also allows 120 seconds;
                # this driver keeps its own explicit graceful/TERM/KILL policy.
                if process.poll() is None:
                    try:
                        process.stdin.write(b"stop\n")
                        process.stdin.flush()
                    except OSError:
                        pass
                    try:
                        process.wait(timeout=120)
                    except subprocess.TimeoutExpired:
                        pass
                if process.poll() is None:
                    manifest.update(valid=False, rejection="Graceful shutdown exceeded 120 seconds")
                    process.terminate()
                    try:
                        process.wait(timeout=30)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
                base.stop(process)  # Already exited: close the console pipe only.
                manifest["exit_code"] = process.returncode
                if process.returncode:
                    manifest.update(valid=False, rejection=f"{manifest['rejection'] or ''}; server exit {process.returncode}")
            if summary is not None:
                summary = read_report(server, manifest)  # Recheck durable telemetry after the writer exits.
                if not summary["valid"]:
                    manifest.update(valid=False, rejection=summary["rejection"])
            if profile is not None:
                shutil.copy2(profile, folder / profile.name)
                manifest["spark"] = {"file": profile.name, "sha256": base.sha(folder / profile.name)}
            manifest["raw_outputs"] = {str(p.relative_to(folder)): base.sha(p)
                                       for p in sorted(server.glob("tnt-*")) if p.is_file()}
        except Exception as error:
            manifest.update(valid=False, rejection=f"{manifest['rejection'] or ''}; cleanup: {error}")
        try:
            settings = server / "server.properties"
            if settings.exists():
                settings.write_text(re.sub(r"(?im)^([^\n=]*(?:password|secret|token|credential)[^\n=]*=).*$",
                                           r"\1<redacted>", settings.read_text()).replace(password, "<redacted>"))
                manifest["effective_server_properties"] = settings.read_text()
            configs = server / "config"
            manifest["active_config_sha256"] = {str(p.relative_to(server)): base.sha(p)
                                                for p in configs.rglob("*") if p.is_file()}
            manifest["redacted_config_files"] = []
            for path in configs.rglob("*.json"):
                try:
                    original = json.loads(path.read_text())
                except (ValueError, UnicodeError):
                    continue
                clean = redact(original)
                if clean != original:
                    base.save(path, clean)
                    manifest["redacted_config_files"].append(str(path.relative_to(server)))
        except Exception as error:
            manifest.update(valid=False, rejection=f"{manifest['rejection'] or ''}; configuration archive: {error}")
        try:
            check_sources(manifest["provenance"])
        except Exception as error:
            manifest.update(valid=False, rejection=f"{manifest['rejection'] or ''}; source integrity: {error}")
        manifest["finished_at"] = base.stamp()
        save_manifest()
        summary = summary or {"schema": 1, "mode": mode}
        summary.update(valid=manifest["valid"], rejection=manifest["rejection"], manifest="manifest.json")
        base.save(folder / "summary.json", summary)
    return manifest


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    for name, default in (("warmup-seconds", 120), ("duration-seconds", 900), ("window-seconds", 30),
                          ("warmup-ticks", 200), ("measure-ticks", 3000), ("window-ticks", 200),
                          ("repeat", 1), ("run-timeout-seconds", 7200),
                          ("game-port", 25585), ("rcon-port", 25595)):
        p.add_argument("--" + name, type=int, default=default)
    p.add_argument("--mode", choices=("time", "ticks"), default="time")
    p.add_argument("--server-home", default=str(Path.home() / "fabric-server"))
    p.add_argument("--source-world")
    p.add_argument("--output", default=str(ROOT / "benchmarks/tnt/results"))
    p.add_argument("--java", default="java")
    p.add_argument("--cpus", default="0-15")
    p.add_argument("--heap", default="2G")
    p.add_argument("--mod", action="append", default=[], help="Explicit extra Fabric jar; never selected implicitly")
    p.add_argument("--nt-config")
    p.add_argument("--activate-classic", action="store_true", help="Run source tnt_chamber:build ONCE before observation (clears/rebuilds chamber)")
    p.add_argument("--skip-build", action="store_true")
    p.add_argument("--dry-run", action="store_true")
    return p


def summarize_batch(args, runs):
    means = [r["mean_mspt"] for r in runs if r["valid"]]
    mean = statistics.mean(means) if means else None
    return {"schema": 1, "mode": args.mode, "parameters": vars(args), "runs": runs,
            "requested_repeats": args.repeat, "completed_repeats": len(runs),
            "valid": len(runs) == args.repeat and all(r["valid"] for r in runs),
            "rejection": next((r["rejection"] for r in runs if not r["valid"]),
                              None if len(runs) == args.repeat else "incomplete"),
            "mean_mspt": mean, "run_mean_mspt": means,
            "sample_cv_percent": 100 * statistics.stdev(means) / mean if len(means) > 1 and mean else None,
            "note": "Descriptive independent JVM repetitions; statistics exclude rejected runs. No stability or speedup claim."}


def main(argv=None):
    p = parser()
    args = p.parse_args(argv)
    try:
        schedule(vars(args))
    except ValueError as error:
        p.error(str(error))
    if args.repeat <= 0 or args.run_timeout_seconds <= 0:
        p.error("Repeat and run-timeout-seconds must be positive")
    if not (1 <= args.game_port <= 65535 and 1 <= args.rcon_port <= 65535 and args.game_port != args.rcon_port):
        p.error("Invalid/distinct ports required")
    if not re.fullmatch(r"[1-9][0-9]*[MGmg]", args.heap) or not re.fullmatch(r"\d+(?:-\d+)?(?:,\d+(?:-\d+)?)*", args.cpus):
        p.error("Invalid heap or CPU list")
    for key in ("server_home", "source_world", "output", "nt_config"):
        if getattr(args, key):
            setattr(args, key, str(Path(getattr(args, key)).expanduser().resolve()))
    args.source_world = str(Path(args.source_world or Path(args.server_home) / "world-tnt-backup").resolve())
    args.mod = [str(Path(m).expanduser().resolve()) for m in args.mod]
    if "/" in args.java:
        args.java = str(Path(args.java).expanduser().resolve())
    if any(Path(args.output).is_relative_to(Path(source)) for source in (args.server_home, args.source_world)):
        p.error("Output must be outside the source server/world")
    if args.dry_run:
        print(json.dumps({"parameters": vars(args), "build": None if args.skip_build else BUILD,
                          "observer_jar": str(BENCH_JAR), "automatic_mod_ids": ["fabric-api", "spark"],
                          "workload": "activate source tnt_chamber:build once" if args.activate_classic else "preserve original world",
                          "fresh_jvms": args.repeat, "observer_command": observer_command(args)}, indent=2))
        return 0
    folder = None
    runs = []
    try:
        if args.nt_config and not any(base.mod_id(Path(m)) == "native-threading" for m in args.mod):
            raise ValueError("--nt-config requires an explicit NativeThreading --mod")
        folder = Path(args.output) / (base.dt.datetime.now(base.dt.timezone.utc).strftime("%Y%m%dT%H%M%S") + "-" + secrets.token_hex(4))
        folder.mkdir(parents=True, mode=0o700)
        if not args.skip_build:
            with (folder / "build.log").open("wb") as log:
                subprocess.run(BUILD, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
        mods = base.select_mods(Path(args.server_home), [Path(m) for m in args.mod], benchmark=BENCH_JAR)
        origin = provenance()
        base.save(folder / "provenance.json", origin)
        reference = None
        if args.repeat > 1:
            base.save(folder / "summary.json", summarize_batch(args, runs))
        print(f"Results: {folder}; {args.repeat} independent run(s), mode={args.mode}", flush=True)
        for repetition in range(1, args.repeat + 1):
            target = folder if args.repeat == 1 else folder / f"{repetition:02d}"
            if args.repeat > 1:
                target.mkdir(mode=0o700)
            print(f"Run {repetition}/{args.repeat}: {target}", flush=True)
            result = run(args, target, mods, origin=origin, reference=reference)
            report = json.loads((target / "summary.json").read_text())
            runs.append({"manifest": str((target / "manifest.json").relative_to(folder)),
                         "summary": str((target / "summary.json").relative_to(folder)),
                         "valid": result["valid"], "rejection": result["rejection"],
                         "mean_mspt": report.get("tick_ms", {}).get("mean"),
                         "measured_ticks": report.get("measured_ticks")})
            if args.repeat > 1:
                base.save(folder / "summary.json", summarize_batch(args, runs))
            print(f"Run {repetition}: {'VALID' if result['valid'] else 'REJECTED: ' + result['rejection']}", flush=True)
            if not result["valid"]:
                break  # Do not spend more hours on a rejected batch or resume after an interrupt.
            reference = reference if reference is not None else result["identity"]
        valid = len(runs) == args.repeat and all(r["valid"] for r in runs)
        print(f"{'VALID' if valid else 'REJECTED'}; results: {folder}", flush=True)
        return 0 if valid else 1
    except (Exception, KeyboardInterrupt) as error:
        rejection = f"{type(error).__name__}: {error}"
        print(f"Benchmark rejected: {rejection}", file=sys.stderr)
        if folder:
            summary = summarize_batch(args, runs) if args.repeat > 1 else {"schema": 1, "mode": args.mode}
            summary.update(valid=False, rejection=rejection)
            base.save(folder / "summary.json", summary)
            print(f"REJECTED; results: {folder}", flush=True)
        return 1


if __name__ == "__main__":
    def interrupt(signum, frame):
        raise KeyboardInterrupt(f"Signal {signum}")
    signal.signal(signal.SIGTERM, interrupt)
    sys.exit(main())
