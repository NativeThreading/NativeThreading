# Layered Allay End-to-End Benchmark

This mode exercises real, world-added allays through vanilla sensing, Brain
behaviors, flying path search, movement, and item pickup. It does not submit
synthetic path requests or replace their results. It complements, rather than
replaces, the existing synchronous ground-search corpus.

## Run

From the repository root:

```bash
python -B benchmarks/pathfinding/run.py --no-lithium --scene allay --entities 64 --label allay-64
```

`--entities` must be at least 64 and divisible by 8. `--seed` defaults to 8675309
and accepts a signed 64-bit integer. Allay mode cannot be mixed with search
scenes in one invocation, and `--requests` is only for the search corpus.
Default phases remain 400 warmup and 600 measurement ticks, repeated three times
in fresh JVMs/worlds. Both allay phases must contain at least 100 ticks.

Short feasibility check:

```bash
python -B benchmarks/pathfinding/run.py --no-lithium --scene allay --entities 64 \
  --warmup-ticks 100 --measure-ticks 200 --repeat 1 --label allay-smoke
```

Run 64, 128, 256, and 512 entities sequentially, not simultaneously on the same
machine. Use the existing `--mod` / `--nt-config` options for an explicitly chosen
candidate. `--no-lithium` keeps the archived baseline stack of Fabric API, Spark
and the fixture; the harness default also loads Lithium.
Original `~/fabric-server` files are never modified.

## Geometry And Load

- Arena occupies chunk (0,0), x/z 0..15, y 64..128 inclusive.
- Stone walls cover x/z 0 and 15. Platforms are at y=64,72,...,128: nine planes
  define eight sealed compartments, each with a 14 x 14 x 7 interior.
- Exactly `n/8` allays inhabit each compartment. Spawn positions account for
  walls/platforms, and setup checks block collisions before adding an entity.
- Each allay holds one plain diamond; no player or note-block deposit target is
  assigned. `mob_griefing=true` is required. AI remains enabled.
- At each active tick start, every nonempty pickup inventory stack with count
  greater than one is clamped to one. Empty inventories stay empty; the separate
  main-hand filter item is not repeatedly replaced.
- At phase ticks 1,21,41,..., one plain diamond is spawned in each compartment,
  at a seeded random interior x/y/z. It has zero initial velocity, zero pickup
  delay and normal gravity, collision, merging and lifetime. No item components
  are changed to suppress merging or alter inventory stacking.
- A phase of `T` ticks therefore introduces `8 * ceil(T / 20)` diamonds. This is
  one batch per simulated second, not a wall-clock arrival timer under low TPS.

The arena is one chunk, but its navigation reads are not. Allay navigation sets
required path length 48; with the normal 8-block region offset, the 56-block
radius fits inside chunks -4..4 on both axes. Those 81 chunks are generated and
force-loaded before warmup, with additional vanilla ticket dependencies. The
benchmark measures the surrounding loaded-world overhead too.

There is no measured-time cleanup of unpicked diamonds. Lost/despawned diamonds
invalidate a run. Very long phases can encounter the normal 6000-tick item
lifetime and are not guaranteed to pass. Retaining growing backlog is deliberate:
deleting it would hide an overloaded or broken collection pipeline.

## Phase Boundaries

After setup and after each phase, the fixture freezes game simulation with the
vanilla tick-rate manager. RCON and profiler setup can proceed while entities
and items remain frozen. At the next phase, the fixture resumes simulation and
waits for `runsNormally()` before counting its first tick. This avoids counting
the transition tick while entity ticking is still disabled.

The first measured START callback snapshots entity tick counters. The result must
contain `entity_ticks == n * T`, and `gap_entity_ticks == 0` between warmup and
measurement. Thus extra live-AI warmup cannot accidentally accrue while Python
polls a file or Spark starts. Warmup allays, inventories, brains and outstanding
diamonds persist into measurement; no teleport, navigation reset or artificial
goal is inserted at the boundary.

Seeded allay spawn positions and phase-specific diamond schedules are repeatable.
The drop schedule is independent of entity count. A schedule fingerprint is
checked across repeated runs of each phase. **Vanilla AI itself is not made
bit-deterministic**: entity RNGs, sensor scan offsets, UUIDs and tie ordering are
not overridden. Query counts and pickups may differ across runs and require
statistical interpretation, unlike the synchronous corpus's exact path checksums.

## Accounting And Metrics

Each phase emits schema 2 to `pathbench-<phase>.json`. Existing archive manifests,
mod/runtime hashes, fresh Spark checks, redaction and process cleanup still apply.

| Field | Meaning |
| --- | --- |
| `spawned`, `picked_up`, `remaining`, `lost` | Diamond units from this phase's cohort; conservation is enforced |
| `carried_pickups` | Older warmup-cohort units collected during this phase, excluded from current-cohort latency |
| `per_layer` | Eight separate cohort counters and live population counts |
| `uncollected` | Every outstanding/lost current-cohort unit and its age at measurement cutoff |
| `pickup_latency_ticks`, `pickup_latency_ms` | Spawn-to-successful-pickup nearest-rank distributions, not path-request latency |
| `path_searches`, `path_reached`, `path_partial`, `path_null` | Actual outer `PathFinder.findPath` calls and outcomes for fixture allays |
| `search_ms`, `query_ms` | Wall-clock time inside that PathFinder boundary; includes preparation/search/cleanup, excludes region construction |
| `navigation_active_fraction` | Fraction of allay-tick samples with navigation in progress, including wandering |
| `tick_ms` | Wall time between fixture START/END server-tick callbacks, including live AI and fixture callbacks; excludes pacing sleep |
| `maintenance_ms` | Start/end fixture maintenance and sampling only; not all hook overhead |
| `inventory_resets`, `inventory_items_removed` | Capacity intervention count and number of collected units removed |
| `merges` | Observed merges involving tracked diamond stacks |
| `dead`, `escaped`, `alive_end` | Population and assigned-compartment integrity |
| `samples` | Per-20-tick cumulative searches/pickups and navigation/backlog gauges, plus final tick |

The three test-only mixins observe PathFinder entry/return, Allay pickup return,
and ItemEntity merge return. They do not cancel or replace vanilla behavior.
The ledger transfers individual birth records between merged stacks. Partial
pickups attribute indistinguishable units in FIFO order; merger removal is never
counted as pickup or loss. The ledger has independent unit tests.

Path searches include wandering and retries, not only diamond-directed searches.
Path reachability means the returned search result can reach its requested node,
not that the allay subsequently arrived there. Partial/null paths can be natural
AI outcomes, so they are measured rather than universally rejected.

Validation requires real searches and pickups in every compartment, exact entity
ticks, zero simulation-gap entity ticks, no death/escape/loss, and consistent
cohort/sample counts. Uncollected units must always be considered alongside pickup
latency: a low latency among successful pickups alone can hide failed collection.
There is no drain period after measurement that selectively extends the test.

`search_ms` is not directly comparable to the synchronous mode's `createPath`
timer. `maintenance_ms` excludes the observation hooks within vanilla work.
Do not subtract it from tick time to claim an uninstrumented server result.
Neither the Fabric callback interval nor Spark's rolling snapshot MSPT should be
misrepresented as an exact whole-`tickServer` instrumented interval.

## Interpretation

Fixed eight-diamond-per-20-tick arrival rate does not promise search load linear
in population. More allays compete for fewer targets, trigger perception scans,
push/collide with each other, and wander. Inspect actual search counts and Spark
self-time to determine whether a run is search-bound or entity/AI-bound.

This fixture validates a real sensing-to-pickup pipeline. It does not yet measure
submission-to-application latency for a future asynchronous pathfinding system.
The current observation hooks explicitly require main-thread callbacks; do not
pass the live fixture world/entities into workers to make it "parallel".

## Source And Tests

Source context is the same Gradle-generated Minecraft 26.2 archive documented in
[README.md](README.md), original unobfuscated names, Fabric Loader 0.19.3,
Loom 1.16.3, Fabric API 0.152.1+26.2, server side. Additional inspected classes:
`Allay`, `AllayAi`, `NearestItemSensor`, `GoToWantedItem`, `MoveToTargetSink`,
`FlyingPathNavigation`, `InventoryCarrier`, `ItemEntity`, `TickRateManager`,
`ServerTickRateManager`, and `MinecraftServer`.

```bash
python -B -m unittest discover -s benchmarks/pathfinding -p 'test_*.py'
./gradlew -p benchmarks/pathfinding/mod check
```

Adding observation mixins changes the fixture jar, so compare only runs that
share a fixture hash: rerun both comparison sides rather than treating an older
jar's timings as a same-harness baseline. Sessions stay under `results/` as
local artifacts; no result is committed.
