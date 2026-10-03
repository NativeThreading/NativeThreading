# Pathfinding Benchmark

A test-only, in-server baseline for Minecraft 26.3 ground navigation. It calls
the actual `GroundPathNavigation.createPath` / `PathFinder` / `WalkNodeEvaluator`
implementation, not a reimplementation of A*. No production mixins, parallel
pathfinding, or dependency on NotEnoughPalette are introduced.

For live AI, flight, and pickup measurements, see the separate
[layered allay end-to-end mode](ALLAY.md). The defaults below describe the
original synchronous ground-search corpus.

For mass path invalidation after nearby block placement, see the separate
[block-update spike scene](BLOCK-UPDATES.md), including same-shape and no-write controls.

## Run Locally

From the NativeThreading repository root:

```bash
python -B -m unittest discover -s benchmarks/pathfinding -p 'test_*.py' -v
python -B benchmarks/pathfinding/run.py --dry-run
python -B benchmarks/pathfinding/run.py --no-lithium --label vanilla-navigation
```

The last command builds the test mod and runs all three scenes, three fresh JVMs
per scene. Every run uses 400 warmup ticks, then 600 measured ticks, with 32
requests per tick. At 20 TPS these phases take 20 and 30 seconds; startup,
generation, shutdown, and profile saving take additional time. The number of
requests, rather than wall-clock runtime, stays fixed if the server overloads.

Requirements: Linux, `taskset`, Python 3.11+, Java 26 (runtime, vector module),
the repository Gradle wrapper and Java 25 toolchain (build), and an existing
Minecraft 26.3 Fabric server at `~/fabric-server` with an accepted EULA.
Only its launcher, server jar, libraries, Fabric API and Spark are copied.
`--java`, `--server-home`, `--cpus`, `--heap`, and port arguments override the
machine defaults; see `--help`. Default affinity is CPUs `0-15`, heap is 2 GiB.

For a short integration check, not a performance result:

```bash
python -B benchmarks/pathfinding/run.py --label smoke \
  --warmup-ticks 100 --measure-ticks 100 --repeat 1
```

To exercise the complete supported corpus (256 requests per tick):

```bash
python -B benchmarks/pathfinding/run.py --label corpus-check \
  --requests 256 --warmup-ticks 2 --measure-ticks 2 --repeat 1
```

## Isolation

Each invocation creates `results/<timestamp>-<id>/`; each repetition has its own
`server/` directory, new flat world, fixed seed `8675309`, and JVM. The existing
TNT world, mods, configuration, and server process are never modified. Ports bind
to loopback only (game 25585, RCON 25595), with a random RCON password. Runtime
files are copied, not hard-linked or symlinked into the source server. Shutdown
only targets the process owned by this run. This is workload isolation, not a
security sandbox for untrusted mods.

The default stack is **vanilla + Lithium**: Fabric API, Spark and the Lithium jar
found in `--server-home/mods` are copied automatically, the fixture mod is
added on top, and the copy of `config/lithium.properties` is archived in the
manifest. Lithium is the default because it prunes collision, shape,
explosion-raycast and node-evaluation work that would otherwise be charged to
these workloads, so pure vanilla is the opt-in oracle stack: `--no-lithium`
selects it for differential/equivalence checks. [../README.md](../README.md)
carries the stack and pairing rules for the whole suite.

Add a candidate explicitly:

```bash
./gradlew clean assemble
python -B benchmarks/pathfinding/run.py --label candidate \
  --mod /absolute/path/to/native-threading-COMMIT.jar \
  --nt-config /absolute/path/to/nt.json
```

Supply the exact jar, not a wildcard picking an arbitrary build. `--mod`
replaces the discovered jar with the same mod ID, so a specific Lithium or
Fabric API build replaces the automatic one instead of colliding with it;
combining `--no-lithium` with an explicit Lithium `--mod` is rejected. The
runner archives its bytes/hash and optional configuration. `--skip-build` only skips
building the fixture mod; it never builds candidate mods implicitly. Repeat with
identical benchmark code, parameters, Java, affinity and profiler settings on
both sides. Prefer module on/off on the same candidate commit when a future
pathfinding module supports it. Reboot/cool the machine before final comparisons;
do not interpret an interactive desktop run as a controlled speedup experiment.

The fixture is a standalone Gradle build with its own settings and pinned
dependencies. It is not a subproject or included build of NativeThreading.
The root `build`, `assemble`, `check`, and release tasks do not build or validate
it. Only the Gradle wrapper executable is reused:

```bash
./gradlew -p benchmarks/pathfinding/mod clean assemble check
```

The runner uses this same standalone command, including fixture tests and mixin
validation. Its jar is `mod/build/libs/pathfinding-benchmark-1.0.jar`. It is not included in
release jars and refuses to load without `-Dpathbench.enabled=true`. Never install
it in a real world: fixture setup overwrites blocks at x/z 0..32, y 79..84 and
force-loads chunks -5..6 in both axes. The runner supplies a disposable world.

## Workload

All scenes share a 33 by 33 stone floor at y=79 and five-block-high perimeter.
The arena is above the flat world's terrain. All navigation-region chunks are
generated and force-loaded before warmup (144 explicit chunks, plus vanilla
ticket dependencies). Setup is outside measurement.

| Scene | Obstacles | Required result |
| --- | --- | --- |
| `open` | No interior walls | Every path reaches its target |
| `maze` | Wall x=8 with gate z=9..12; wall x=16 with gate z=19..22 | Every path reaches its target after detouring |
| `blocked` | Solid wall x=16 joining the perimeter | Every search returns a non-null partial path, none reaches |

For query index `i`, the detached adult zombie starts at
`(2.5 + (i / 16) % 4, 80, 2.5 + i % 16)` (integer division), targets
`(28 - (i / 64) % 3, 80, 16 + i % 8)`, and has `onGround=true`,
`follow_range=64`, exact reach range 0 and vanilla visited-node multiplier 1.
The navigation node budget is refreshed after setting the attribute (1024).
Indices 192..255 repeat the first 64 spatial queries with separate mob/navigation
instances; default 32 uses 32 distinct start/target pairs.

Mobs are constructed but never added to the world or ticked. Their AI, spawning
factory, movement, combat, and RNG-driven goals do not run. Every tick issues the
same ordered queries, clearing the active path first to prevent the navigation
cached-path fast return. Requests execute serially on the server thread. The
world is peaceful and never pauses when empty. No client or fake player is needed.

## Metrics And Checks

`pathbench-warmup.json` and `pathbench-measure.json` contain:

- `requests`, `reached`, `partial`, `null_paths`, `nodes`: exact workload/results.
- `search_ms`: sum of wall-clock durations of `createPath` calls, including
  navigation-region construction and node-evaluator preparation/cleanup. This is
  not CPU self-time and includes pauses occurring within calls.
- `query_ms`: mean, nearest-rank p50/p95/p99/max of individual calls.
- `batch_ms`: distribution of the benchmark callback per tick, including
  stopping navigation, timers, counters, and result fingerprints.
- `tick_ms`: distribution between this fixture's Fabric start/end server-tick
  callbacks. It excludes tick pacing sleep, but includes the batch and intervening
  server work. It is not an exact vanilla `tickServer` timer with arbitrary mods.
- `query_signatures`: one 64-bit fingerprint per corpus entry; includes reach
  status, target, node count, every node's coordinates, path type, and raw float
  bits of malus/g/h/f/walked distance. Every repetition of a query is checked.
- `checksum`: ordered fingerprint reduction across all measured requests.

The runner rejects wrong counts, null results, wrong scene reachability,
malformed/nonfinite timing fields, changed signatures between warmup/measurement
or repetitions, changed runtime/mod/config hashes between repetitions, and
missing/stale profiles. A fingerprint is a regression signal, not a collision-free
proof or a replacement for future differential tests on full path data.

Spark records **time**, not allocation, on all threads. Measurement starts only
after a fresh confirmation that profiling is running. `--save-to-file` keeps the
profile local; the runner does not upload it. The saved Spark window includes
small start/stop/polling margins and final JSON serialization outside the timed
Java loop. Use the `BenchmarkRunStage.startTick` subtree to isolate the load.
Do not use Spark's rolling `last1m` MSPT as the exact measured interval, or count
call-tree nodes as if they were sampled time. Judge optimization hotspots by
sampled method **self-time**, alongside inclusive search cost and measured latency.

Each run's `manifest.json` archives commit, dirty diff, harness-source hashes,
runtime/mod hashes, config, Java/JVM flags, affinity, CPU info, uptime/load,
commands/responses, results and profile hashes. `console.log`, copied mods,
world and `.sparkprofile` remain for inspection. RCON secrets are redacted on
shutdown; the results directory is private and ignored by git. Failures remain
archived with a rejection reason and a nonzero runner exit.

`summary.json` groups valid repetitions and gives mean and sample CV for timing
statistics. CV above 5% prints a warning; one repetition has no CV. A valid run
means integrity checks passed, not that variance or external interference is
acceptable. Inspect both the validity flag and CV before comparison.

## Scope Of Future Optimization

This is the synchronous **search boundary** baseline, not a server AI benchmark.
It covers ground-node search, world reads/collision evaluation, region setup and
path reconstruction. It deliberately does not cover swimming/flying navigation,
doors/hazards, moving targets, natural goal scheduling, path following, world
invalidation, or mod callbacks.

An asynchronous implementation cannot simply return a pending/null path here and
claim reduced latency. Add a separate end-to-end benchmark for submission,
capture, worker queue/compute, completion latency, stale-result rejection and
main-thread application under live AI. Keep this corpus for synchronous oracle
and snapshot/compute differential testing. No future worker may inherit these
live `Mob`/`ServerLevel` references from the fixture.

## Source Context

Verified against this project's `./gradlew :fabric:genSources` output: Minecraft
26.3, Fabric Loader 0.19.5, Loom 1.18.2, Fabric API 0.161.0+26.3; server side,
original unobfuscated Mojang names, no separate mapping dependency. Read-only
generated source archive:

`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-7e9a32a5b8/26.3/minecraft-merged-7e9a32a5b8-26.3-sources.jar`

Reference classes: `PathNavigation`, `GroundPathNavigation`, `PathFinder`, `Path`,
and `EntityTypes`. This generated source is not committed or added as a dependency.

The fixture was ported from the 26.2 harness (Minecraft 26.2, Fabric Loader 0.19.3,
Loom 1.16.3, Fabric API 0.152.1+26.2); `PathFinder.findPath`, `PathNavigation`
(`shouldRecomputePath`/`recomputePath`/`mob`), `Allay.pickUpItem` and
`ItemEntity.merge` were re-verified against the official 26.3 server JAR. Results
recorded under `results/` before the port are 26.2 measurements; re-run both sides
before comparing across the port.
