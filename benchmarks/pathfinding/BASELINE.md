# Local Baseline: 2026-09-26

## Result

All **9/9 full runs passed** the workload-integrity checks and produced fresh
local Spark profiles. This is a synchronous vanilla ground-navigation baseline,
not an optimization comparison or a live mob-AI benchmark.

Command, from the repository root:

```bash
python -B benchmarks/pathfinding/run.py --label vanilla-navigation-baseline
```

Local archive (not tracked in git):

[`results/20260926T043826-911c995e/summary.json`](results/20260926T043826-911c995e/summary.json)

Each scene had three fresh JVMs/worlds, 400 warmup ticks and 600 measured ticks,
32 requests per tick. Each measured run completed exactly 19,200 searches;
172,800 measured searches in total (plus 115,200 warmup searches).

## Environment

- CPU: Intel Core i9-12900HX; process affinity CPUs 0-15, 16 logical CPUs.
- OS: Manjaro Linux, kernel 7.2.4-lqx4-1-lqx, amd64.
- Runtime: Arch Linux OpenJDK **26.0.2.1**, not the older JDK in the machine notes.
- JVM: `-Xms2G -Xmx2G --add-modules=jdk.incubator.vector -Dpathbench.enabled=true`;
  default collector identified by Spark as G1GC.
- Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.152.1+26.2, Spark 1.10.173.
- Only API, Spark and the benchmark mod were explicitly loaded; no NT, NEP,
  Lithium or Carpet in this baseline. Fabric's count of 44 includes nested API mods.
- Source commit: `a364c0d84011993f44fcbbf7e7219f8049dda4b8`, plus uncommitted
  benchmark additions and the opt-in settings change recorded in manifests.
- Fixture jar SHA-256:
  `d14353b1a914b370acc15000cd494ba6f0115f892333f19c2db6f72aedef62c8`.
- Harness-source aggregate SHA-256 at launch:
  `0aaf45500d221b6406220b179e5bbd660a18d634e116c56da0020ed18fcac571`.
  This report was added afterward; individual source hashes are also archived.

No machine reboot, thermal reset, or desktop/background-service isolation was
performed for this run. Treat these as development reference numbers. Use a
controlled cool/rebooted machine and same-commit on/off comparisons before
publishing speedup claims.

## Timings

All times are milliseconds. Values below are arithmetic means of the three
per-run statistics; a mean of per-run percentiles is **not** a pooled percentile.
CV is sample standard deviation divided by the mean across the three runs.

| Scene | Mean/query | Query p95 | Mean batch/tick | Mean tick body | Tick-body p95 | Search-time CV |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Open | 0.105738 | 0.125448 | 3.400953 | 3.635670 | 3.703056 | 2.99% |
| Maze | 0.169838 | 0.237760 | 5.452065 | 5.659137 | 5.973152 | 2.57% |
| Blocked | 0.754847 | 0.819897 | 24.170147 | 24.372673 | 24.720882 | 0.46% |

The blocked scene is the useful sustained high-search-cost workload. The open
scene provides a cheap-request control; maze adds reachable detours.

Mean costs were repeatable within 5%, but not all tail statistics were:

- Open: query maximum CV 164.92%; batch/tick p99 CV about 5.33%/5.34%.
- Maze: query p99 CV 6.14%; query maximum CV 48.75%.
- Blocked: all reported timing-statistic CVs were below 1.32%.

Do not infer stable maximum latency from only three desktop JVM runs. Pauses,
JIT/GC and OS scheduling can affect wall-clock timers; this run does not establish
which caused each outlier. Do not silently discard those outliers or conflate
`valid=true` with low variance.

## Correctness

Open and maze returned reachable, non-null paths for every measured request.
Blocked returned non-null partial paths for every request and reached no target.
All per-query node/float fingerprints stayed identical within each phase,
between warmup and measurement, and across the three scene repetitions.

Expected ordered checksums for **32 requests x 600 ticks**, this corpus and MC
version only:

| Scene | Checksum |
| --- | --- |
| Open | `8433ddc9d7967a5` |
| Maze | `eb8fbe92f0d25d25` |
| Blocked | `53aa99f767163c25` |

The full 256-request supported corpus also passed all three scenes with the same
fixture binary and two warmup/two measured ticks. This is an integration check,
not another timing baseline:

[`results/20260926T045207-03573984/summary.json`](results/20260926T045207-03573984/summary.json)

Earlier `smoke`, `maze-debug`, and `corpus-check` archives include deliberately
rejected setup/protocol/corpus iterations. They are diagnostics, not performance
comparators. In particular, the original three-gate maze could exhaust vanilla's
search budget for geometrically reachable goals. The final two-gate corpus was
validated without increasing the visited-node multiplier or changing vanilla A*.

## Profile Evidence

Inspected the first full-run profile of each scene using the local Spark parser:

| Scene / Run | File | `PathFinder.findPath` inclusive sampled ms | `WalkNodeEvaluator.getNeighbors` inclusive sampled ms |
| --- | --- | ---: | ---: |
| Open / 01 | `profile-2026-09-26_12.39.23.sparkprofile` | 1,748 | 1,648 |
| Maze / 04 | `profile-2026-09-26_12.43.17.sparkprofile` | 2,644 | 2,540 |
| Blocked / 07 | `profile-2026-09-26_12.47.12.sparkprofile` | 14,420 | 13,512 |

Files are at the top of each run directory in the full archive. `findPath` has
two nested overloads; the table uses the outer one, **not their sum**. Inclusive
frames also overlap with their descendants and must not be added together.
Sampled values and Java wall timers have different measurement semantics.

Blocked run 07 sampled 30,264 ms of Server thread stacks. The parser classified
14,404 ms as active Java time and 15,860 ms as native/idle. This classifier is not
a precise CPU-utilization measurement and can exclude active native work.

Top self-time evidence for that run, percentages relative to the parser's active
Java total:

| Method | Self sampled ms | Active-Java share |
| --- | ---: | ---: |
| `PalettedContainer.get` | 1,944 | 13.50% |
| `IdentityHashMap.get` | 1,572 | 10.91% |
| `LevelChunk.getBlockState` | 800 | 5.55% |
| `LevelChunkSection.getBlockState` | 600 | 4.17% |
| `LinearPalette.valueFor` | 560 | 3.89% |
| `SimpleBitStorage.cellIndex` | 496 | 3.44% |
| `PathfindingContext.getPathTypeFromState` | 392 | 2.72% |
| `WalkNodeEvaluator.checkNeighbourBlocks` | 384 | 2.67% |
| `WalkNodeEvaluator.getNeighbors` | 320 | 2.22% |

The inner A* `PathFinder.findPath` overload itself had only 108 ms self-time in
blocked run 07. Neighbor evaluation and its world/block-state reads are the
prominent cost, rather than the A* method body alone. This supports investigating
the cost and semantic requirements of capturing path types/collision inputs for
pure worker computation; it does **not** show that copying entire chunk regions
will be profitable. Measure capture, compute, queuing and apply separately before
choosing that design. No optimization has been implemented from this evidence.

Spark's snapshot MSPT was around 0.22-0.27 ms despite the timed load. That snapshot
is not the 600-tick interval and must not replace the benchmark's tick-body
metrics. Profiles span approximately 30 seconds plus control margins; inspect
the `BenchmarkRunStage.startTick` subtree rather than treating metadata as the
workload measurement.

## Verification

- Fixture clean build and real Fabric loading succeeded.
- All 38 Python runner tests passed, including short RCON reads, authentication,
  async Spark confirmation, stale data, result drift and binary/config drift.
- `./gradlew validateMixinDiscipline :core:test :explosion:test` passed.
- `git diff --check` passed.
- Benchmark processes were shut down by the runner; no source TNT server/world
  was replaced or reconfigured.

See [README.md](README.md) for metric definitions, rerun commands and the limits
of using this synchronous corpus to assess future asynchronous pathfinding.
