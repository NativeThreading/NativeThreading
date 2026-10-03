# Benchmark Suite

Two standalone measurement kits for Minecraft 26.3 servers. Nothing here is part
of the mod build: the root `build`, `assemble`, `check` and release tasks
neither build nor validate them, and no fixture jar ships in a release.

| Kit | Measures | Entry point |
| --- | --- | --- |
| `pathfinding/` | ground A* search corpus, live allay collection, mass block-update invalidation | `python -B benchmarks/pathfinding/run.py` |
| `tnt/` | a continuously replenished TNT chamber, observed passively | `python -B benchmarks/tnt/run.py` |

Both runners are standard-library Python: they build their own fixture mod, start
a disposable server, drive it over RCON, archive the raw evidence and validate it
before accepting a run. `pathfinding/ALLAY.md`, `pathfinding/BLOCK-UPDATES.md`
and `tnt/README.md` document the individual workloads.

## Requirements

Linux, `taskset`, Python 3.11+, a Java 26 runtime with the vector module, the
repository Gradle wrapper plus a JDK 25 toolchain, and an existing Fabric server
at `~/fabric-server` with an accepted `eula.txt`. Only its launcher, server jar,
libraries, Fabric API and Spark are copied. `--java`, `--server-home`, `--cpus`,
`--heap` and the port arguments override the machine defaults; see `--help`.

## Quick start

```bash
python -B -m unittest discover -s benchmarks/pathfinding -p 'test_*.py'
python -B -m unittest discover -s benchmarks/tnt -p 'test_*.py'
python -B benchmarks/pathfinding/run.py --dry-run
python -B benchmarks/pathfinding/run.py --label navigation            # 3 scenes x 3 JVMs
python -B benchmarks/tnt/run.py --mode ticks --warmup-ticks 100 --measure-ticks 60 --window-ticks 20
```

`--dry-run` prints the resolved parameters, the mod set and the stack without
building or launching anything. `--skip-build` reuses an already built fixture.

## Stacks

Every run copies Fabric API, Spark and the Lithium jar found in
`--server-home/mods`. That is the `base` stack and it is what a server actually
runs; `--no-lithium` selects the pure-vanilla oracle stack, which is what
differential and equivalence checks need. `--mod` adds an explicit jar and
replaces the discovered jar with the same mod ID, so a candidate build of
Lithium, Fabric API or NativeThreading is a replacement rather than a duplicate
error. Combining `--no-lithium` with an explicit Lithium `--mod` is rejected.

Each manifest records the resulting `stack`, the Lithium jar hash and the
effective `lithium.properties` (copied into the isolated server, because
`config/` is never inherited).

## Protocol

- **Measure at development scale.** The defaults are the short protocols used
  while iterating: search 400 warmup + 600 measured ticks (~30 s of load),
  block-updates 400 + 1200, allay 400 + 600, TNT 100 warmup + 60 measured ticks
  in 20-tick windows. Prefer more short pairs over one long run.
- **Compare stacks in interleaved pairs.** Run base, vanilla, base, vanilla and
  report per-pair ratios. Comparing stacks in separate batches attributes machine
  drift to the stack: one search scene measured 1.12x under interleaved pairs and
  1.47-1.60x when the stacks were run as separate batches.
- **Match the fixture hash.** A fixture change moves timings on its own. Compare
  the fixture jar `sha256` recorded in each manifest before reusing a number
  measured on another day; if it differs, rerun both sides.
- **Keep the workload identical.** Work counters, `checksum`/`query_signatures`
  (search), `schedule_hash` (allay) and explosion counts (TNT) must match across
  the runs being compared.

## Results

Sessions land in `benchmarks/*/results/<timestamp>-<id>/`, one directory per
session, holding `manifest.json` (commit, dirty diff, source hashes, mod hashes,
JVM flags, affinity, commands and responses), `console.log`, the world copy, the
Spark profile and the raw result JSON. With more than one repetition each run
gets its own `NN-scene-repeat/` directory inside the session.

Results are local artifacts and are gitignored: nothing is committed, and no
number produced here is a published claim. The copied server runtime
(`libraries/`, `server.jar`, `versions/`) is deleted once the JVM exits, so a
session is ~17-31 MB instead of ~160 MB; the manifest's `runtime_sha256` still
identifies exactly what was launched.

## Reading a run

Accept a run only when `valid` is true, then check the sample CV across
repetitions: valid means the integrity checks passed, not that variance is
acceptable. Judge an optimization by Spark sampled self-time together with the
measured latency, never by call-tree node counts or allocation bytes, and never
treat a rolling `last1m` MSPT as the measured interval.

## Safety

The fixture jars are test-only. They refuse to load without their system property
(`-Dpathbench.enabled=true`, `-Dtnt.observer.enabled=true`, supplied by the
runners) and their setup overwrites blocks in a disposable world — never install
one in a real world. The runners copy the source server and world rather than
linking them, and only stop the process they started.
