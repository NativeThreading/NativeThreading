#!/usr/bin/env python3
"""Isolated pathbench orchestration. Python standard library only; never deploys mods."""

import argparse
import datetime as dt
import hashlib
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
import struct
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
BENCH_JAR = ROOT / "benchmarks/pathfinding/mod/build/libs/pathfinding-benchmark-1.0.jar"
BUILD = ["./gradlew", "-p", "benchmarks/pathfinding/mod", "clean", "assemble", "check"]
SCENES = ("open", "maze", "blocked")
ALLAY_COUNTERS = ("spawned", "picked_up", "remaining", "lost", "carried_pickups", "merges",
                  "inventory_resets", "inventory_items_removed", "alive_end", "dead", "escaped",
                  "path_searches", "path_reached", "path_partial", "path_null")
ALLAY_DISTRIBUTIONS = ("query_ms", "tick_ms", "maintenance_ms",
                       "pickup_latency_ticks", "pickup_latency_ms")


def stamp():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def save(path, value):
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n")
    temp.replace(path)


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def exact(sock, size, deadline=None):
    data = bytearray()
    while len(data) < size:
        if deadline is not None:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("RCON deadline exceeded (do not replay commands)")
            sock.settimeout(remaining)
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise ConnectionError("RCON EOF (command outcome may be unknown; do not replay)")
        data.extend(chunk)
    return bytes(data)


def packet(sock, deadline=None):
    size, = struct.unpack("<i", exact(sock, 4, deadline))
    # Minecraft splits responses at 4096 characters, not UTF-8 bytes.
    if not 10 <= size <= 4096 * 4 + 10:
        raise ConnectionError(f"Invalid RCON frame size: {size}")
    body = exact(sock, size, deadline)
    if body[-2:] != b"\0\0" or b"\0" in body[8:-2]:
        raise ConnectionError("Invalid RCON terminator")
    ident, kind = struct.unpack("<ii", body[:8])
    return ident, kind, body[8:-2].decode("utf-8", errors="strict")


def send(sock, ident, kind, text):
    body = struct.pack("<ii", ident, kind) + text.encode() + b"\0\0"
    if len(body) > 4096 or "\0" in text:
        raise ValueError("Invalid outgoing RCON payload")
    sock.sendall(struct.pack("<i", len(body)) + body)


def rcon(port, password, command=None, timeout=120):
    # Only connection/auth probes may be retried by the caller, never commands.
    deadline = time.monotonic() + timeout
    with socket.create_connection(("127.0.0.1", port), timeout=timeout) as sock:
        send(sock, 1, 3, password)
        ident, kind, text = packet(sock, deadline)
        if (ident, kind, text) == (1, 0, ""):
            ident, kind, text = packet(sock, deadline)
        if ident == -1:
            raise PermissionError("RCON authentication rejected")
        if (ident, kind, text) != (1, 2, ""):
            raise ConnectionError("RCON authentication ID/type mismatch")
        if command is None:
            return ""
        send(sock, 2, 2, command)
        ident, kind, text = packet(sock, deadline)
        if (ident, kind) != (2, 0):
            raise ConnectionError("RCON response ID/type mismatch")
        # Wait for a reply before sending the delimiter: Minecraft's handler
        # need not accept two coalesced requests in one socket read.
        send(sock, 3, 2, "")
        parts = [text]
        for _ in range(256):
            ident, kind, text = packet(sock, deadline)
            if kind != 0 or ident not in (2, 3):
                raise ConnectionError("RCON response ID/type mismatch")
            if ident == 3:
                return "".join(parts)
            parts.append(text)
        raise ConnectionError("RCON response exceeds packet limit (do not replay commands)")


def mod_id(path):
    with zipfile.ZipFile(path) as jar:
        return json.loads(jar.read("fabric.mod.json"))["id"]


def select_mods(home, extra, benchmark=BENCH_JAR):
    automatic = {"fabric-api": [], "spark": []}
    for path in sorted((home / "mods").glob("*.jar")):
        try:
            ident = mod_id(path)
        except (OSError, KeyError, ValueError, zipfile.BadZipFile):
            continue
        if ident in automatic:
            automatic[ident].append(path)
    for ident, paths in automatic.items():
        if len(paths) != 1:
            raise ValueError(f"Expected exactly one {ident} jar in {home / 'mods'}: {paths}")
    selected = [paths[0] for paths in automatic.values()] + [benchmark] + extra
    ids, names = set(), set()
    for path in selected:
        ident = mod_id(path)
        if ident in ids or path.name in names:
            raise ValueError(f"Duplicate mod ID or filename: {path}")
        ids.add(ident)
        names.add(path.name)
    return selected


def properties(args, password):
    flat = {"biome": "minecraft:plains", "layers": [
        {"block": "minecraft:bedrock", "height": 1},
        {"block": "minecraft:dirt", "height": 2},
        {"block": "minecraft:grass_block", "height": 1}], "structure_overrides": []}
    values = {"server-ip": "127.0.0.1", "server-port": args.game_port,
              "rcon.port": args.rcon_port, "rcon.password": password,
              "enable-rcon": "true", "broadcast-rcon-to-ops": "false",
              "online-mode": "false", "enable-query": "false",
              "enable-status": "false", "enable-jmx-monitoring": "false",
              "management-server-enabled": "false", "max-players": 1,
              "level-name": "world", "level-seed": "8675309", "level-type": "minecraft:flat",
              "generator-settings": json.dumps(flat, separators=(",", ":")),
              "generate-structures": "false", "spawn-protection": 0,
              "view-distance": 2, "simulation-distance": 2,
              "pause-when-empty-seconds": 0, "difficulty": "peaceful",
              "gamemode": "creative", "max-tick-time": -1}
    return "".join(f"{key}={value}\n" for key, value in sorted(values.items()))


def validate(result, scene, ticks, requests, signatures=None):
    if not isinstance(result, dict):
        raise ValueError("Result must be an object")
    expected = {"schema": 1, "ticks": ticks, "requests_per_tick": requests,
                "requests": ticks * requests, "null_paths": 0,
                "reached": 0 if scene == "blocked" else ticks * requests,
                "partial": ticks * requests if scene == "blocked" else 0}
    for key, value in expected.items():
        if type(result.get(key)) is not int or result[key] != value:
            raise ValueError(f"Invalid {key}: expected {value}, got {result.get(key)!r}")
    if result.get("scene") != scene:
        raise ValueError("Scene mismatch")
    if type(result.get("nodes")) is not int or result["nodes"] < 0:
        raise ValueError("Invalid nodes")
    if not isinstance(result.get("checksum"), str) or not result["checksum"]:
        raise ValueError("Missing checksum")
    corpus = result.get("query_signatures")
    if (not isinstance(corpus, list) or len(corpus) != requests
            or any(not isinstance(item, str) or not item for item in corpus)):
        raise ValueError("Invalid query_signatures")
    if signatures is not None and corpus != signatures:
        raise ValueError("Query signatures changed")
    def number(value):
        return type(value) in (int, float) and math.isfinite(value) and value >= 0
    if not number(result.get("search_ms")):
        raise ValueError("Invalid search_ms")
    for key in ("query_ms", "batch_ms", "tick_ms"):
        values = result.get(key)
        if not isinstance(values, dict) or any(not number(values.get(k)) for k in
                                               ("mean", "p50", "p95", "p99", "max")):
            raise ValueError(f"Invalid {key}")
        if not (values["p50"] <= values["p95"] <= values["p99"] <= values["max"]
                and values["mean"] <= values["max"]):
            raise ValueError(f"Inconsistent {key}")
    return result


def validate_allay(result, ticks, entities, seed):
    """Validate current-phase cohorts; carried pickups do not enter their totals or latency."""
    if not isinstance(result, dict):
        raise ValueError("Allay result must be an object")
    expected = {"schema": 2, "ticks": ticks, "entities": entities, "seed": seed,
                "entity_ticks": entities * ticks, "gap_entity_ticks": 0,
                "layers": 8, "drop_interval_ticks": 20,
                "spawned": 8 * ((ticks + 19) // 20), "alive_end": entities,
                "dead": 0, "escaped": 0, "lost": 0}
    for key, value in expected.items():
        if type(result.get(key)) is not int or result[key] != value:
            raise ValueError(f"Invalid {key}: expected {value}, got {result.get(key)!r}")
    if result.get("scene") != "allay":
        raise ValueError("Scene mismatch")
    if not isinstance(result.get("schedule_hash"), str) or not result["schedule_hash"].strip():
        raise ValueError("Missing schedule_hash")

    def counters(row, keys, label):
        if not isinstance(row, dict) or any(type(row.get(k)) is not int or row[k] < 0 for k in keys):
            raise ValueError(f"Invalid {label} counters")

    def number(value):
        return type(value) in (int, float) and 0 <= value < math.inf

    counters(result, ALLAY_COUNTERS, "allay")
    if result["spawned"] != sum(result[k] for k in ("picked_up", "remaining", "lost")):
        raise ValueError("Allay cohort conservation failed")
    if result["path_searches"] != sum(result[k] for k in ("path_reached", "path_partial", "path_null")):
        raise ValueError("Allay path outcomes do not sum to path_searches")
    if ticks >= 100 and (result["path_searches"] == 0 or result["picked_up"] == 0):
        raise ValueError("Allay phase requires path_searches > 0 and picked_up > 0")
    if not number(result.get("search_ms")):
        raise ValueError("Invalid search_ms")
    fraction = result.get("navigation_active_fraction")
    if not number(fraction) or fraction > 1:
        raise ValueError("Invalid navigation_active_fraction")
    for key in ALLAY_DISTRIBUTIONS:
        values = result.get(key)
        if not isinstance(values, dict) or any(not number(values.get(k)) for k in
                                               ("mean", "p50", "p95", "p99", "max")):
            raise ValueError(f"Invalid {key}")
        if not (values["p50"] <= values["p95"] <= values["p99"] <= values["max"]
                and values["mean"] <= values["max"]):
            raise ValueError(f"Inconsistent {key}")

    if result["pickup_latency_ticks"]["max"] > ticks - 1:
        raise ValueError("Invalid pickup_latency_ticks: max exceeds ticks - 1")

    layers = result.get("per_layer")
    if not isinstance(layers, list) or len(layers) != 8:
        raise ValueError("Invalid per_layer: expected eight rows")
    layer_keys = ("spawned", "picked_up", "remaining", "lost", "alive_end")
    for layer, row in enumerate(layers):
        counters(row, layer_keys, f"per_layer[{layer}]")
        if (row["spawned"] != (ticks + 19) // 20
                or row["spawned"] != row["picked_up"] + row["remaining"] + row["lost"]
                or row["alive_end"] != entities // 8):
            raise ValueError(f"Invalid per_layer[{layer}] conservation or population")
        if ticks >= 100 and row["picked_up"] == 0:
            raise ValueError(f"per_layer[{layer}] requires picked_up > 0")
    for key in layer_keys:
        if sum(row[key] for row in layers) != result[key]:
            raise ValueError(f"per_layer {key} total mismatch")

    uncollected = result.get("uncollected")
    if not isinstance(uncollected, list):
        raise ValueError("Invalid uncollected")
    counts = [{"remaining": 0, "lost": 0} for _ in layers]
    for item in uncollected:
        counters(item, ("layer", "age_ticks"), "uncollected")
        if (item["layer"] >= 8 or item["age_ticks"] > ticks
                or not number(item.get("age_ms")) or item.get("status") not in ("remaining", "lost")):
            raise ValueError("Invalid uncollected entry")
        counts[item["layer"]][item["status"]] += 1
    if any(count[key] != row[key] for count, row in zip(counts, layers) for key in ("remaining", "lost")):
        raise ValueError("Uncollected cohort counts do not match per_layer")

    samples = result.get("samples")
    sample_ticks = list(range(20, ticks, 20)) + [ticks]
    if not isinstance(samples, list) or len(samples) != len(sample_ticks):
        raise ValueError("Invalid samples: expected every 20 ticks and the final tick")
    previous = {"path_searches": 0, "picked_up": 0}
    for tick, sample in zip(sample_ticks, samples):
        counters(sample, ("tick", "path_searches", "picked_up", "navigating", "remaining"), "sample")
        if sample["tick"] != tick or sample["navigating"] > entities:
            raise ValueError("Invalid sample tick or navigating population")
        for key in ("path_searches", "picked_up"):
            if not previous[key] <= sample[key] <= result[key]:
                raise ValueError(f"Non-cumulative sample {key}")
        if sample["picked_up"] + sample["remaining"] != 8 * ((tick + 19) // 20):
            raise ValueError("Sample cohort conservation failed")
        previous = sample
    if any(samples[-1][key] != result[key] for key in ("path_searches", "picked_up", "remaining")):
        raise ValueError("Final sample totals mismatch")
    return result


def provenance():
    def git(*args):
        return subprocess.check_output(["git", *args], cwd=ROOT).decode(errors="replace")
    paths = git("ls-files", "--cached", "--others", "--exclude-standard", "-z").split("\0")
    sources = {}
    for name in sorted(set(paths)):
        path = ROOT / name
        if path.is_file() and (name.startswith("benchmarks/pathfinding/")
                or name.startswith("gradle/") or name.startswith("buildSrc/")
                or path.suffix in (".gradle", ".kts", ".properties")
                or path.name in ("gradlew", "gradlew.bat")):
            if not set(path.relative_to(ROOT).parts) & {"build", ".gradle", "results", "__pycache__"}:
                sources[name] = sha(path)
    return {"commit": git("rev-parse", "HEAD").strip(),
            "dirty_status": git("status", "--porcelain=v1", "--untracked-files=all"),
            "diff": git("diff", "HEAD", "--binary"), "source_files_sha256": sources,
            "source_sha256": hashlib.sha256(json.dumps(sources, sort_keys=True).encode()).hexdigest()}


def wait_for(process, probe, timeout, description):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Server exited ({process.returncode}) while waiting for {description}")
        value = probe()
        if value:
            return value
        time.sleep(0.25)
    raise TimeoutError(f"Timed out waiting for {description}")


def stop(process):
    if process.poll() is None:
        try:
            process.stdin.write(b"stop\n")
            process.stdin.flush()
            process.wait(timeout=30)
        except (OSError, subprocess.TimeoutExpired):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
    try:
        process.stdin.close()
    except OSError:
        pass  # A terminated server may have closed the console pipe first.


def run_one(args, folder, scene, mods, origin, env, reference):
    folder.mkdir()
    server = folder / "server"
    password = secrets.token_hex(24)
    jvm = [f"-Xms{args.heap}", f"-Xmx{args.heap}", "--add-modules=jdk.incubator.vector",
           "-Dpathbench.enabled=true"]
    command = ["taskset", "-c", args.cpus, args.java, *jvm, "-jar", "fabric-server-launch.jar", "nogui"]
    manifest = {"schema": 1, "label": args.label, "scene": scene, "started_at": stamp(),
                "valid": False, "rejection": "incomplete", "provenance": origin,
                "parameters": vars(args) | {"scene": scene}, "command": command,
                "jvm_args": jvm, "affinity_requested": args.cpus,
                "platform": platform.platform(), "cpu": platform.processor(),
                "cpu_count": os.cpu_count(), "cpu_info": Path("/proc/cpuinfo").read_text(),
                "uptime": Path("/proc/uptime").read_text().strip(), "load": list(os.getloadavg()),
                "java_version": subprocess.check_output([args.java, "-version"], stderr=subprocess.STDOUT,
                                                         env=env, timeout=30).decode(errors="replace"),
                "ignored_java_environment": [key for key in os.environ if key not in env],
                "server_properties": properties(args, "<redacted>"), "mods": [], "commands": []}
    # argparse paths are normalized to strings in main, so manifests remain JSON values.
    save(folder / "manifest.json", manifest)
    process = None
    def command_once(text):
        entry = {"at": stamp(), "command": text}
        manifest["commands"].append(entry)
        save(folder / "manifest.json", manifest)
        try:
            entry["response"] = rcon(args.rcon_port, password, text, args.timeout)
            return entry["response"]
        except Exception as error:
            entry["error"] = str(error)
            raise
        finally:
            entry["completed_at"] = stamp()
            save(folder / "manifest.json", manifest)
    try:
        server.mkdir()
        home = Path(args.server_home)
        for name in ("fabric-server-launch.jar", "eula.txt"):
            shutil.copy2(Path(args.server_home) / name, server / name)
        server_jars = set()
        for name in ("fabric-server-launcher.properties", "launcher.properties"):
            source = home / name
            if source.exists():
                # Do not allow a reused launcher to refer back to the source server.
                settings = [line.strip() for line in source.read_text().splitlines()
                            if line.strip() and not line.lstrip().startswith(("#", "!"))]
                if len(settings) != 1 or not re.fullmatch(r"serverJar\s*=\s*[^\\]+\.jar", settings[0]):
                    raise ValueError(f"Unsafe/unrecognized launcher properties: {source}")
                server_jars.add((home / settings[0].split("=", 1)[1].strip()).resolve())
                (server / name).write_text("serverJar=server.jar\n")
        if len(server_jars) > 1:
            raise ValueError("Conflicting launcher serverJar settings")
        server_jar = next(iter(server_jars), home / "server.jar")
        manifest["server_jar_source"] = str(server_jar)
        shutil.copy2(server_jar, server / "server.jar")
        shutil.copytree(Path(args.server_home) / "libraries", server / "libraries", symlinks=False)
        manifest["runtime_sha256"] = {str(p.relative_to(server)): sha(p)
                                      for p in sorted(server.rglob("*")) if p.is_file()}
        (server / "mods").mkdir()
        for mod in mods:
            target = server / "mods" / mod.name
            shutil.copy2(mod, target)
            manifest["mods"].append({"source": str(mod), "file": str(target.relative_to(folder)),
                                     "id": mod_id(target), "sha256": sha(target)})
        if args.nt_config:
            (server / "config").mkdir()
            target = server / "config/nt.json"
            shutil.copy2(args.nt_config, target)
            manifest["nt_config"] = {"source": args.nt_config, "sha256": sha(target),
                                      "content": json.loads(target.read_text())}
        (server / "server.properties").write_text(properties(args, password))
        (server / "server.properties").chmod(0o600)
        if reference:
            if manifest["runtime_sha256"] != reference["runtime_sha256"]:
                raise ValueError("Runtime artifacts differ between repetitions")
            mod_keys = ("file", "id", "sha256")
            current_mods = sorted(tuple(mod[key] for key in mod_keys) for mod in manifest["mods"])
            previous_mods = sorted(tuple(mod[key] for key in mod_keys) for mod in reference["mods"])
            if current_mods != previous_mods:
                raise ValueError("Mod artifacts differ between repetitions")
            if manifest.get("nt_config", {}).get("sha256") != reference.get("nt_config", {}).get("sha256"):
                raise ValueError("NT config differs between repetitions")
        for port in (args.game_port, args.rcon_port):
            with socket.socket() as check:
                check.bind(("127.0.0.1", port))
        manifest["launch_at"] = stamp()
        save(folder / "manifest.json", manifest)  # Complete provenance before starting Java.
        with (folder / "console.log").open("wb") as log:
            process = subprocess.Popen(command, cwd=server, stdin=subprocess.PIPE,
                                       stdout=log, stderr=subprocess.STDOUT, env=env)
            manifest["pid"] = process.pid
            def ready():
                if not re.search(r'Done \([^)]+\)!', (folder / "console.log").read_text(errors="replace")):
                    return False
                try:
                    rcon(args.rcon_port, password, timeout=2)
                    return True
                except (OSError, ConnectionError):
                    return False
            wait_for(process, ready, args.timeout, "startup log and authenticated RCON")
            manifest["affinity_actual"] = sorted(os.sched_getaffinity(process.pid))
            setup = (f"pathbench allay {args.entities} {args.seed}" if scene == "allay"
                     else f"pathbench setup {scene} {args.requests}")
            if command_once(setup).strip() != "PATHBENCH READY":
                raise ValueError("Setup did not return PATHBENCH READY")
            warmup = None
            for phase, ticks in (("warmup", args.warmup_ticks), ("measure", args.measure_ticks)):
                output = server / f"pathbench-{phase}.json"
                if output.exists():
                    raise ValueError(f"Stale benchmark output: {output}")
                if phase == "measure":
                    previous = set(server.rglob("*.sparkprofile"))
                    profile_start = time.time_ns()
                    log_offset = (folder / "console.log").stat().st_size
                    manifest["spark_start"] = {"log_offset": log_offset, "log_excerpt": ""}
                    response = command_once("spark profiler start --thread *")
                    if re.search(r"\b(already|unable|error|failed|cannot|unknown command)\b", response, re.I):
                        raise ValueError(f"Spark did not confirm start: {response}")
                    def spark_running():
                        # Spark may acknowledge RCON before its async profiler has started.
                        with (folder / "console.log").open("rb") as console:
                            console.seek(log_offset)
                            excerpt = console.read().decode("utf-8", errors="replace")
                        manifest["spark_start"]["log_excerpt"] = excerpt
                        return re.search(r"\bProfiler is now running\b", excerpt, re.I)
                    if re.search(r"\bProfiler is now running\b", response, re.I):
                        spark_running()  # Preserve any fresh log even with a synchronous reply.
                        confirmed_by = "rcon"
                    else:
                        wait_for(process, spark_running, args.timeout, "fresh Spark startup confirmation")
                        confirmed_by = "console.log"
                    manifest["spark_start"].update(confirmed_by=confirmed_by, confirmed_at=stamp())
                if command_once(f"pathbench run {phase} {ticks}").strip() != "PATHBENCH STARTED":
                    raise ValueError(f"{phase} did not return PATHBENCH STARTED")
                wait_for(process, output.exists, max(args.timeout, ticks / 20 + args.timeout), phase)
                manifest.setdefault("results", {})[phase] = {"file": str(output.relative_to(folder)),
                                                            "sha256": sha(output)}
                if phase == "measure":
                    command_once("spark profiler stop --save-to-file")
                    last = None
                    def fresh_profile():
                        nonlocal last
                        files = [p for p in server.rglob("*.sparkprofile") if p not in previous
                                 and p.stat().st_mtime_ns >= profile_start and p.stat().st_size > 0]
                        if len(files) > 1:
                            raise ValueError("Ambiguous Spark output")
                        state = [(p, p.stat().st_size, p.stat().st_mtime_ns) for p in files]
                        stable = state and state == last
                        last = state
                        return files[0] if stable else None
                    profile = wait_for(process, fresh_profile, args.timeout, "fresh Spark profile")
                    archive = folder / profile.name
                    shutil.copy2(profile, archive)
                    manifest["spark"] = {"file": archive.name, "sha256": sha(archive),
                                         "started_ns": profile_start}
                result = json.loads(output.read_text())
                if scene == "allay":
                    validate_allay(result, ticks, args.entities, args.seed)
                    if reference and any(result[key] != reference[phase][key] for key in
                                         ("schema", "scene", "ticks", "entities", "seed", "layers", "drop_interval_ticks")):
                        raise ValueError(f"{phase} allay identity differs between repetitions")
                    if reference and result["schedule_hash"] != reference[phase]["schedule_hash"]:
                        raise ValueError(f"{phase} schedule_hash differs between repetitions")
                else:
                    validate(result, scene, ticks, args.requests,
                             warmup["query_signatures"] if warmup else None)
                    if warmup and ticks == warmup["ticks"] and result["checksum"] != warmup["checksum"]:
                        raise ValueError("Warmup/measure checksum differs for identical tick count")
                    if reference and (result["query_signatures"] != reference[phase]["query_signatures"]
                                      or result["checksum"] != reference[phase]["checksum"]):
                        raise ValueError(f"{phase} corpus/checksum differs between repetitions")
                manifest[phase] = result
                warmup = result
            manifest.update(valid=True, rejection=None)
    except (Exception, KeyboardInterrupt) as error:
        manifest["rejection"] = f"{type(error).__name__}: {error}"
        if isinstance(error, KeyboardInterrupt):
            raise
    finally:
        try:
            if process is not None:
                manifest["commands"].append({"at": stamp(), "transport": "stdin", "command": "stop"})
                stop(process)
                manifest["exit_code"] = process.returncode
                if process.returncode != 0:
                    manifest.update(valid=False, rejection=f"{manifest['rejection'] or ''}; server exited with {process.returncode}")
                if "spark" in manifest:
                    # A temporarily stalled writer must not yield an accepted partial archive.
                    if sha(profile) != manifest["spark"]["sha256"]:
                        shutil.copy2(profile, archive)
                        manifest["spark"]["sha256"] = sha(archive)
            settings = server / "server.properties"
            if settings.exists():
                settings.write_text(settings.read_text().replace(password, "<redacted>"))
        except Exception as error:
            manifest.update(valid=False, rejection=f"{manifest['rejection'] or ''}; cleanup: {error}")
        finally:
            manifest["finished_at"] = stamp()
            save(folder / "manifest.json", manifest)
    return manifest


def summarize(runs):
    groups = {}
    for scene in (*SCENES, "allay"):
        valid = [run for run in runs if run["valid"] and run["scene"] == scene]
        if not valid:
            continue
        metrics = {"search_ms": [r["measure"]["search_ms"] for r in valid]}
        distributions = ALLAY_DISTRIBUTIONS if scene == "allay" else ("query_ms", "batch_ms", "tick_ms")
        for kind in distributions:
            for stat in ("mean", "p50", "p95", "p99", "max"):
                metrics[f"{kind}.{stat}"] = [r["measure"][kind][stat] for r in valid]
        groups[scene] = {"repetitions": len(valid), "timings": {}}
        if scene == "allay":
            groups[scene]["schedule_hash"] = valid[0]["measure"]["schedule_hash"]
            for key in (*ALLAY_COUNTERS, "navigation_active_fraction"):
                metrics[key] = [r["measure"][key] for r in valid]
            metrics["pickup_fraction"] = [r["measure"]["picked_up"] / r["measure"]["spawned"] for r in valid]
        else:
            for key in ("query_signatures", "checksum"):
                groups[scene][key] = valid[0]["measure"][key]
        for name, values in metrics.items():
            mean = statistics.mean(values)
            cv = (100 * statistics.stdev(values) / mean if mean else 0) if len(values) > 1 else None
            groups[scene]["timings"][name] = {"mean": mean, "cv_percent": cv}
            if cv is not None and cv > 5:
                print(f"WARNING: {scene} {name} CV {cv:.2f}% exceeds 5%", file=sys.stderr)
    return {"schema": 1, "valid": bool(runs) and all(r["valid"] for r in runs), "scenes": groups,
            "note": "Descriptive repetitions only; no speedup claim. tick_ms is Java timed tick body, not Spark last1m.",
            "runs": [{"manifest": r["link"], "valid": r["valid"], "rejection": r["rejection"]} for r in runs]}


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    for name, default in (("warmup-ticks", 400), ("measure-ticks", 600), ("requests", 32), ("repeat", 3),
                          ("game-port", 25585), ("rcon-port", 25595), ("timeout", 300),
                          ("entities", 64), ("seed", 8675309)):
        p.add_argument("--" + name, type=int, default=default)
    p.add_argument("--scene", action="append", choices=(*SCENES, "allay"))
    p.add_argument("--label", default="baseline")
    p.add_argument("--mod", action="append", default=[], help="Explicit additional Fabric JAR; repeatable")
    p.add_argument("--nt-config")
    p.add_argument("--java", default="java")
    p.add_argument("--cpus", default="0-15")
    p.add_argument("--heap", default="2G")
    p.add_argument("--server-home", default=str(Path.home() / "fabric-server"))
    p.add_argument("--output", default=str(ROOT / "benchmarks/pathfinding/results"))
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--skip-build", action="store_true")
    return p


def main(argv=None):
    p = parser()
    # Distinguish an explicit search-only option from the search default.
    p.set_defaults(requests=None)
    args = p.parse_args(argv)
    args.scene = args.scene or list(SCENES)
    if "allay" in args.scene:
        if any(scene != "allay" for scene in args.scene):
            p.error("Cannot mix allay and search scenes")
        if args.requests is not None:
            p.error("--requests is search only; use --entities for allay")
        if args.warmup_ticks < 100 or args.measure_ticks < 100:
            p.error("Allay warmup and measure ticks must be at least 100")
    if args.entities < 64 or args.entities % 8:
        p.error("--entities must be at least 64 and divisible by 8")
    if not -(2 ** 63) <= args.seed < 2 ** 63:
        p.error("--seed must be a signed 64-bit integer")
    if args.requests is None:
        args.requests = 32
    if not (1 <= args.requests <= 256 and 1 <= args.warmup_ticks <= 12000
            and 1 <= args.measure_ticks <= 12000 and args.repeat > 0 and args.timeout > 0
            and 1 <= args.game_port <= 65535 and 1 <= args.rcon_port <= 65535
            and args.game_port != args.rcon_port):
        p.error("Invalid ticks (1..12000), requests (1..256), repetitions, timeout or distinct ports")
    if not re.fullmatch(r"[1-9][0-9]*[MGmg]", args.heap) or not re.fullmatch(r"\d+(?:-\d+)?(?:,\d+(?:-\d+)?)*", args.cpus):
        p.error("Invalid heap or CPU list")
    for key in ("server_home", "output", "nt_config"):
        if getattr(args, key):
            setattr(args, key, str(Path(getattr(args, key)).expanduser().resolve()))
    args.mod = [str(Path(mod).expanduser().resolve()) for mod in args.mod]
    if Path(args.output).is_relative_to(Path(args.server_home)):
        p.error("--output must not be inside --server-home")
    if "/" in args.java:
        args.java = str(Path(args.java).expanduser().resolve())
    if args.dry_run:
        print(json.dumps({"parameters": vars(args), "build": None if args.skip_build else BUILD,
                          "benchmark_jar": str(BENCH_JAR), "automatic_mod_ids": ["fabric-api", "spark"],
                          "fresh_jvms": len(args.scene) * args.repeat}, indent=2))
        return 0
    output = None
    runs = []
    try:
        home = Path(args.server_home)
        eula = [line.strip() for line in (home / "eula.txt").read_text().splitlines()
                if line.strip() and not line.lstrip().startswith(("#", "!"))]
        if len(eula) != 1 or not re.fullmatch(r"eula\s*=\s*true", eula[0]):
            raise ValueError("server-home must already contain an accepted eula=true")
        if args.nt_config and not any(mod_id(Path(mod)) == "native-threading" for mod in args.mod):
            raise ValueError("--nt-config requires an explicit NativeThreading --mod")
        output = Path(args.output) / (dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%S") + "-" + secrets.token_hex(4))
        output.mkdir(parents=True, mode=0o700)
        if not args.skip_build:
            with (output / "build.log").open("wb") as log:
                subprocess.run(BUILD, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
        mods = select_mods(home, [Path(mod) for mod in args.mod])
        origin = provenance()
        env = {k: v for k, v in os.environ.items() if k not in
               ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH")}
        references = {}
        for scene in args.scene:
            for repetition in range(1, args.repeat + 1):
                folder = output / f"{len(runs) + 1:02d}-{scene}-{repetition}"
                print(f"Running {folder.name}", flush=True)
                run = run_one(args, folder, scene, mods, origin, env, references.get(scene))
                verdict = "VALID: all checks passed" if run["valid"] else f"REJECTED: {run['rejection']}"
                print(f"{folder.name} {verdict}", flush=True)
                run["link"] = str((folder / "manifest.json").relative_to(output))
                runs.append(run)
                if run["valid"]:
                    references.setdefault(scene, run)
                save(output / "summary.json", summarize(runs))
        print(f"Results: {output}")
        return 0 if all(run["valid"] for run in runs) else 1
    except (Exception, KeyboardInterrupt) as error:
        print(f"Benchmark rejected: {type(error).__name__}: {error}", file=sys.stderr)
        if output is not None:
            summary = summarize(runs)
            summary.update(valid=False, rejection=f"{type(error).__name__}: {error}")
            save(output / "summary.json", summary)
        return 1


if __name__ == "__main__":
    def interrupt(signum, frame):
        raise KeyboardInterrupt(f"Signal {signum}")
    signal.signal(signal.SIGTERM, interrupt)
    sys.exit(main())
