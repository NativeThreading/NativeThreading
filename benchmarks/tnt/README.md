# TNT Long-Run Observation

A standalone, passive observer of the existing TNT chamber, not a replacement
workload or production mod feature. It times actual server ticks and counts
completed TNT explosions while the original world/datapack runs normally.

## Reproduce The Larger Experiment

From the repository root:

```bash
python -B benchmarks/tnt/run.py --no-lithium --activate-classic --mode ticks \
  --warmup-ticks 200 --measure-ticks 3000 --window-ticks 200 --repeat 3
```

This builds the standalone observer once, then starts three independent JVMs
sequentially, each with a fresh copy of `~/fabric-server/world-tnt-backup`.
There are 9,000 measured ticks and 45 non-overlapping measured windows, plus
200 warmup ticks per JVM. On the tested unoptimized explosion path this takes
about 100 minutes. `--run-timeout-seconds` defaults to 7200 per run; a timeout
rejects the run rather than accepting a smaller sample.

`--activate-classic` invokes the copied pack's `tnt_chamber:build` exactly once
before observation. It clears/builds only the disposable copied world. Without
this explicit flag, the original world's mechanism is left alone, even if it
is inactive. Zero measured explosions reject the experiment.

Defaults also support wall-clock observation (`--mode time`): 120 warmup seconds,
900 measurement seconds, and 30-second windows. The larger tick-based experiment
is preferred here: under this heavy load, a 30-second window only has about
45 ticks, making its p99 effectively the maximum.

## Isolation

- Independent Gradle project under `benchmarks/tnt/mod`. No main-project settings,
  task graph, release packaging, or runtime configuration changes.
- Only the repository Gradle wrapper executable is reused. Build directly with
  `./gradlew -p benchmarks/tnt/mod clean assemble check`.
- The runner reuses tested RCON/provenance utilities from the neighboring
  pathfinding harness, but does not load its fixture mod.
- Automatically loaded mods: Fabric API, Spark, the Lithium jar found in
  `--server-home/mods`, and `tnt-observer`; the manifest records the `stack`
  label, the Lithium hash and the copied `config/lithium.properties`. Vanilla
  only is opt-in with `--no-lithium`. Carpet and NEP are never selected
  implicitly. Extra mods
  require `--mod`, which replaces the discovered jar with the same mod ID; an
  explicit NT configuration can be supplied with `--nt-config`.
- Source world, server jar, libraries and mods are copied, not hard-linked or
  symlinked into the original server. The existing server/world is never replaced.
- Loopback ports default to 25585/25595, with a random RCON password. Only the
  owned server process is stopped. Shutdown has a 120-second saving allowance.
- Repetitions check runtime/mod/world/config/JVM/affinity identities before
  launch. Driver/build source fingerprints must not change during the batch.

## Actual Chamber Mechanism

The inspected source is
`~/fabric-server/world-tnt-backup/datapacks/tnt-chamber/`.

Its `build.mcfunction` creates an obsidian outline at `(0,30,0)..(16,46,16)`,
summons the initial TNT at `(8,38,8)` with fuse 2, and schedules `cycle`.
The actual cycle commands are:

```mcfunction
fill 4 34 4 12 42 12 stone
fill 6 36 6 10 40 10 tnt
schedule function tnt_chamber:cycle 1t
```

The loop therefore refills the stone and 125 TNT blocks **every tick**. Existing
comments referring to two ticks, another room geometry or repeatedly summoning
a fuse do not describe these executable commands. The loaded pack is copied and
hashed; the runner does not change its loop or frequency.

The backup's load tag is under `data/tnt_chamber/tags/function/load.json`, not
Minecraft's automatic load-tag namespace. A restore-only pilot observed zero
explosions. Explicit activation is required for this backup in the tested setup.

Refilling, block destruction, TNT flight, damage/exposure checks, drops and
cleanup by normal game mechanics all remain part of the measured work. The
observer does not clear entities, reset fuses, restore blocks, consume RNG,
pause the game, or short-circuit explosion work during observation.

## Measurements

Two injection-only test mixins observe `MinecraftServer.tickServer` HEAD/RETURN
and `PrimedTnt.explode` RETURN. Tick time includes normal main-thread tick work,
not pacing sleep. Counts are for completed overworld TNT explode calls with
`tnt_explodes=true`; failed calls do not reach the return hook.

Every actual tick is retained in `server/tnt-ticks.csv`. Each window publishes a
line in `server/tnt-windows.jsonl` containing tick mean, population standard
deviation, nearest-rank p50/p95/p99/max, explosions per tick/second, end-window
entity/TNT/item/chunk/heap snapshots, and GC counter/time deltas. End-window
counts are not population averages or maxima within that window.

Window reduction, entity snapshots and file flushing occur after the tick timer
stops, and hence are not included in reported MSPT. They still consume wall time;
the reported window seconds and TPS include those boundary gaps. A completion
marker is written atomically only after all requested windows are durable.

Spark starts after the warmup window is published and stops after completion.
Its asynchronous start/stop has polling/RCON margins recorded in the manifest;
Spark's rolling snapshot MSPT is not the primary statistic. Profiles are saved
locally, not uploaded.

The analysis validates the exact number of windows/ticks, sequence, positive
elapsed time, explosions and consistency of raw timings with aggregate results.
It reports per-run and between-run variation, first-versus-last thirds and an
OLS slope using actual elapsed minutes. These are descriptive statistics: many
explosions in one tick are not independent samples, nor are adjacent windows.

## Artifacts And Analysis

Results are under ignored `benchmarks/tnt/results/<timestamp>-<id>/`. For repeats,
subdirectories `01`, `02`, `03` contain the copied worlds, logs, raw telemetry,
Spark profile, input hashes and per-run summaries. The top-level summary includes
mean MSPTs and between-run sample CV. Failures retain diagnostics and return
nonzero; invalid runs are never silently pooled into successful results.

Generate the four-panel time-series chart and extended descriptive analysis:

```bash
python -B benchmarks/tnt/analyze.py benchmarks/tnt/results/20260926T105430-127665c9
```

This writes derived `analysis.json`, `windows.csv`, and `timeline.svg` without
modifying raw telemetry or manifests. Sessions stay under `results/` as local
artifacts; no result is committed.

## Source Context And Tests

Minecraft 26.2, Fabric Loader 0.19.3, Loom 1.16.3, Fabric API 0.152.1+26.2, original
unobfuscated server-side names. Signatures were inspected in the main project's
previously generated, read-only Minecraft 26.2 source archive, produced by
`:fabric:genSources`. The observer has its own pinned dependencies and no
dependency on the production explosion module.

```bash
python -B -m unittest discover -s benchmarks/tnt -p 'test_*.py'
./gradlew -p benchmarks/tnt/mod check
```
