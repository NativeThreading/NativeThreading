# Block-Update Navigation Spikes

This scene targets mass path invalidation: many world-added cows already follow
paths when nearby block collision shapes change. Vanilla selects affected
navigations and either recomputes immediately or defers through its normal
20-tick throttle. No benchmark call directly forces `recomputePath`.

## Run

```bash
python -B benchmarks/pathfinding/run.py --scene block-updates --entities 512 \
  --update-interval 40 --block-change collision \
  --warmup-ticks 400 --measure-ticks 1200 --repeat 3 --label updates-collision
```

Run the two controls separately with identical arguments except for change/label:

```bash
python -B benchmarks/pathfinding/run.py --skip-build --scene block-updates --entities 512 \
  --update-interval 40 --block-change same-shape \
  --warmup-ticks 400 --measure-ticks 1200 --repeat 3 --label updates-same-shape
python -B benchmarks/pathfinding/run.py --skip-build --scene block-updates --entities 512 \
  --update-interval 40 --block-change none \
  --warmup-ticks 400 --measure-ticks 1200 --repeat 3 --label updates-none
```

The new scene is exclusive; do not combine it with other `--scene` selections or
`--requests`. Entity count must be at least 64, but need not be divisible by eight.
`--update-interval` accepts 1..1200 ticks (default 40). Each phase must have at
least `max(100, interval + 21)` ticks and at most 12000.

Changing every tick is supported for exercising coalescing/throttling, but does
**not** imply that all entities will search every tick. The normal 40-tick cadence
makes the separate inline and delayed spikes easier to see.

## Fixture

- Species: adult cows, compatible with the peaceful test world; no hostile-target
  acquisition, sunlight burning, player/fake-player, or combat is required.
- Stone enclosure: x=0..64, z=0..32, floor y=79, ceiling y=85. Cows spawn with feet
  at y=80, in a seeded grid with safe body clearance.
- Cows are added to the real world and tick normally. `removeFreeWill()` removes
  natural goals/brain behaviors, **not** navigation, move control or physics.
- The test driver supplies an opposite end-of-room target only when navigation
  becomes done. Destinations alternate x=2.5 and x=61.5 at each cow's initial z.
  It does not stop or replace an in-progress path on every tick.
- Ground navigation and movement remain vanilla. Required path length is 64,
  with vanilla node multiplier 1; all possible navigation-region chunks are
  pre-generated and force-loaded (x=-5..8, z=-5..6) before warmup.
- Nine update positions are the product x={16,32,48}, z={8,16,24}, y=83.
  They sit above the cows' bodies and walking surface. The experiment is about
  conservative **nearby-block invalidation**, not crushing entities, trapping
  them in newly placed blocks, or requiring a physically blocked-route detour.
- Periodic writes use `Block.UPDATE_ALL`, including real neighbor/client update
  flags. Collision-shape change flows through `ServerLevel.sendBlockUpdated`.

| Change | Scheduled writes | Expected navigation invalidation |
| --- | --- | --- |
| `collision` | Air <-> stone at all nine positions | Predicate scans, requests and real searches |
| `same-shape` | Stone <-> andesite, both full-cube collision | Writes occur, collision-shape gate rejects navigation scan |
| `none` | No block writes, same scheduled event markers | No invalidation, patrol/movement baseline only |

The same-shape control starts with stone at those overhead positions; collision
and none start with air. Neither overhead arrangement obstructs the floor route.
Comparisons use matching inputs, not identical evolving world states: changed
path timing can change movement, crowding, stuck detection and subsequent patrol
requests. Do not interpret the entire mean-tick difference as pure search cost.

## Exact Trigger Path

Inspected in this standalone build's `./gradlew -p benchmarks/pathfinding/mod
genSources` output for Minecraft 26.2, Loom 1.16.3, Fabric Loader 0.19.3 and API
0.152.1+26.2, original unobfuscated server-side names:

```text
Level.setBlock(..., UPDATE_ALL)
  -> ServerLevel.sendBlockUpdated
     -> collision-shape NOT_SAME test
     -> PathNavigation.shouldRecomputePath for navigatingMobs
     -> PathNavigation.recomputePath
        -> immediate createPath / PathFinder.findPath
        OR hasDelayedRecomputation
           -> later PathNavigation.tick -> recomputePath -> findPath
```

`shouldRecomputePath` uses a proximity test based on the current mob/path-end
midpoint and remaining nodes, rather than requiring the edited block to lie on a
path node. `recomputePath` defers if game time since the previous recomputation
is <=20 or the navigation cannot update. This can synchronize a second wave of
searches **after** the placement tick.

Read-only generated source archive:

`mod/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2/minecraft-merged-043a8b3edf-26.2-sources.jar`

Relevant classes: `ServerLevel`, `PathNavigation`, `Mob`, `GoalSelector`, and `Cow`.
The additional test-only navigation mixin observes predicate/recompute entry and
return; the existing PathFinder probe records actual outer searches. Neither
changes method return values, cooldown fields, goals or block-update decisions.

## Phase And Metrics

World simulation freezes between phases, as in allay mode. Measurement begins on
the first normal entity-ticking frame. Entity ticks must equal `entities * ticks`,
and warmup-to-measure extra entity ticks must be zero.

Events occur at one-based ticks `interval, 2*interval, ... <= ticks-21`. The last
21 ticks are an in-phase drain for delayed work, with no new writes. Therefore
1200 measured ticks at interval 40 contain 29 events, not 30. Paths, positions and
the current block state are retained between warmup and measurement.

Schema 3 records every measured tick in `tick_samples`, including:

- Block writes and event marker, active paths before updates, and navigation count.
- Predicate checks/positive decisions and actual update-triggered recompute calls.
- Deferred retry attempts, actual inline/delayed searches, patrol searches and
  reachable/partial/null outcomes. A retry attempt is not an executed search.
- Outstanding requests and request-to-search-completion latency in ticks.
- Actual horizontal movement and patrol endpoints reached, in addition to held
  paths. An unfinished path alone must not be mistaken for a moving population.
- Tick-callback wall time, PathFinder wall time, and nested block-update time.

Validation checks positive predicates equal update requests and, on every tick:

```text
pending_now = pending_previous + update_requests - actual_recompute_searches
```

The phase starts with no pending requests and must end with none. Baseline
completion latency must be 0..21 ticks. Controls require zero invalidations;
collision mode requires real invalidations/searches. **Every mode**, including
controls, must have at least half the population navigating at events on average
and a moving fraction >=0.25. Population death or escape invalidates a run.

Aggregate timing cohorts are deliberately separate:

| Metric | Cohort / Boundary |
| --- | --- |
| `update_tick_ms` | Scheduled event ticks, even in the none control |
| `non_update_tick_ms` | Other ticks, including delayed search spikes |
| `recompute_tick_ms` | Ticks with at least one real invalidation-triggered search |
| `quiet_tick_ms` | Ticks without such searches; may still have patrol searches |
| `block_update_ms` | Per-event writes/notifications, including nested immediate searches |
| `query_ms` / `search_ms` | Outer PathFinder calls, excluding navigation-region construction |

Nested timers must not be added. Tick time includes fixture driving/accounting
and real entity work, excludes pacing sleep and final JSON serialization, and is
the Fabric callback interval rather than a full `tickServer` method timer.
Empty control recompute cohorts have zero distributions and a null spike ratio.
The ratio is descriptive, not a pure causal estimate of removable overhead.

## Analysis

After running all three modes:

```bash
python -B benchmarks/pathfinding/analyze_updates.py \
  SESSION_COLLISION SESSION_SAME_SHAPE SESSION_NONE --output DERIVED_DIRECTORY
```

The analyzer verifies archived hashes and matching cohort controls, then writes
pooled/per-run `summary.json`, all measured `tick_samples.csv`, and `timeline.svg`.
The chart previews the first repeat's first 240 measured ticks per mode; the
statistics and CSV use the complete dataset. Red markers indicate scheduled
updates, so delayed bursts can be distinguished from the trigger itself.

See [BLOCK-UPDATES-RESULTS.md](BLOCK-UPDATES-RESULTS.md) for the real-server
verification. This is a specialized scripted-navigation fixture, not natural
cow behavior or a replacement for the existing live-allay collection benchmark.
Future asynchronous navigation will need instrumentation at actual completion;
the current probes require main-thread callbacks and do not authorize worker
access to these live entities/worlds.
