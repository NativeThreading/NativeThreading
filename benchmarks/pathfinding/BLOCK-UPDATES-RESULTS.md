# Block-Update Spike Verification: 2026-09-26

## Result

The new workload reproduces **mass navigation invalidation and deferred search
spikes**. All nine formal runs passed: three modes, three fresh JVM/world repeats
per mode, 512 cows, 400 warmup ticks and 1,200 measured ticks per run. This is
10,800 measured ticks in total, with 29 event markers per run at interval 40.

The collision group performed 51,031 actual invalidation-triggered searches.
The same-shape and no-write controls performed zero. All 190 collision ticks
exceeding 50 ms contained actual recompute searches; 121 of them were **not**
block-update event ticks. Both controls had zero ticks above 50 ms.

This is a scripted patrol on real cows and vanilla navigation/physics, not natural
cow goal behavior. The overhead blocks sit above the walking route, exercising
vanilla's conservative nearby-block invalidation rather than physically trapping
the animals. See [BLOCK-UPDATES.md](BLOCK-UPDATES.md) for the exact fixture.

## Controls

- Minecraft 26.2, Fabric Loader 0.19.3, API 0.152.1+26.2, Spark 1.10.173.
- Only API, Spark and the independent benchmark mod loaded; no NT/NEP/Lithium.
- i9-12900HX, CPUs 0-15, OpenJDK 26.0.2.1, 2 GiB heap, vector module.
- Identical seed 8675309, cow population, update schedule, JVM settings, and
  runtime/mod binaries, checked by the analyzer across all three groups.
- Fixture jar SHA-256:
  `f4f78a071512457eb66de477524fe18a88b29820e5b53e4955101d91755a8758`.
- All runs started from new flat worlds. No machine reboot or thermal isolation
  was performed. Groups were run collision, same-shape, then none, not interleaved.
- Each event toggled nine blocks for collision/same-shape. The none group retained
  the same markers but performed no writes. Final 21 ticks drained delayed work.

This experiment verifies the mechanism and workload, not an optimization speedup
or an instrumentation-free server latency distribution.

## Pooled Tick Timings

Each mode pools 3,600 raw measurement ticks. Percentiles below are nearest-rank
over those raw values, **not** averages of per-run percentiles. All values are ms.

| Mode | Mean | Median | p95 | p99 | Maximum | Ticks >50 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Collision shape changes | 11.107 | 7.410 | 51.236 | 76.429 | 105.838 | 190 |
| Same-shape replacement | 8.061 | 7.881 | 9.620 | 11.549 | 40.854 | 0 |
| No writes | 8.170 | 7.978 | 9.721 | 11.542 | 42.580 | 0 |

| Cohort | Samples | Mean ms | p95 ms |
| --- | ---: | ---: | ---: |
| Collision: actual-recompute ticks | 258 | 58.874 | 89.601 |
| Collision: quiet ticks | 3,342 | 7.419 | 8.837 |
| Collision: scheduled event ticks | 87 | 61.245 | 94.012 |
| Same-shape: scheduled event ticks | 87 | 8.003 | 9.307 |
| None: scheduled marker ticks | 87 | 8.225 | 9.699 |

The within-collision recompute/quiet mean ratio is **7.94x**. Quiet ticks may still
include patrol searches, collisions and all other entity work. The ratio is not
the isolated cost of A* or a guaranteed removable overhead fraction.

The block-write timer itself, which includes nested immediate recomputation, had
pooled event mean/p95 **53.817/86.249 ms** for collision and **0.088/0.184 ms** for
same-shape. None has no writes and therefore a zero block-write timer, not zero
whole-tick cost. Do not add these nested timings to tick or search totals.

## Actual Work

Totals across three measured runs per mode:

| Counter | Collision | Same-shape | None |
| --- | ---: | ---: | ---: |
| Changed blocks | 783 | 783 | 0 |
| `shouldRecomputePath` checks | 400,896 | 0 | 0 |
| Positive predicates / update requests | 51,031 | 0 | 0 |
| Actual recompute searches | 51,031 | 0 | 0 |
| Immediate searches | 17,868 | 0 | 0 |
| Delayed searches | 33,163 | 0 | 0 |
| Deferred retry attempts | 392,556 | 0 | 0 |
| Patrol searches | 3,002 | 5,395 | 5,428 |

The 400,896 checks equal `783 changed collision shapes * 512 registered cows`.
Deferred attempts must not be reported as searches: most only recheck the vanilla
cooldown. Every request/completion/pending transition balanced, and each phase
ended with zero outstanding requests.

Every cow tick was accounted for, with no extra phase-gap entity ticks, deaths,
or escapes. At **every scheduled event**, all 512 cows held an active path.
Per-run mean active-navigation fractions were 99.70-99.84%; moving fractions were
99.90-99.95%. Movement includes normal pushing as well as following paths, so the
fixture also counted completed patrol legs: 1,740 / 1,041 / 987 across the three
groups, respectively. A leg is counted when done navigation is within two blocks
of the preceding destination before the driver issues the opposite endpoint.

## Delayed Burst Example

First collision event, measured ticks 40 and 60:

| Repeat | Requests at tick 40 | Immediate searches | Pending afterward | Tick 40 ms | Delayed searches at tick 60 | Tick 60 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 838 | 428 | 410 | 78.410 | 410 | 81.201 |
| 2 | 818 | 423 | 395 | 96.701 | 395 | 80.964 |
| 3 | 818 | 424 | 394 | 92.558 | 394 | 83.293 |

Across ticks 41-59, those requests remained pending and retried, but no actual
recompute search occurred. Tick 60 had **no block-update event**, executed the
deferred searches, and reduced pending to zero. Tick 61-65 remained clear.

All three runs' per-request latency aggregates reported p95, p99 and maximum of
20 ticks. These are individual phase aggregates, not pooled latency quantiles.
The largest observed spike was **105.838 ms at tick 140 of repeat 2**, with 316
delayed searches and no update event that tick.

## Interpretation And Profile

This reproduces both the initial placement spike and the cooldown-delayed spike.
Looking only at the placement tick misses most of the >50 ms affected ticks.

Collision run 1's Spark profile shows `ServerLevel.sendBlockUpdated` at 1,540
inclusive sampled ms with its inline `PathNavigation.recomputePath` branch at
1,536 ms. A separate recompute branch accounts for 3,012 ms, consistent with the
deferred work observed in raw ticks. The same-shape control profile has patrol
PathFinder work, but no sampled invalidation/recompute branch. These nested
inclusive times must not be added as independent costs.

Fixture self-time was 264 sampled ms (2.08% of parser-classified active Java time)
in collision run 1. Instrumentation also uses JDK collections, so that is not a
complete overhead estimate and is not subtracted from the timing results.

Run-mean tick CVs were 5.71% / 0.88% / 1.04% for collision/same-shape/none. The
collision mean exceeded none by 35.94%, but that whole-tick difference is not a
pure causal cost estimate: paths, crowding, stuck detection and completed legs
evolve differently. Collision's median and quiet-tick mean were actually lower
than both controls. The repeat-consistent attribution and tail pattern, rather
than just the overall mean, are the evidence for this specialized workload.

## Artifacts And Checks

- [Collision session](results/20260926T150842-80fb1152/summary.json)
- [Same-shape session](results/20260926T151446-67f3077e/summary.json)
- [No-write session](results/20260926T152032-1fefae93/summary.json)
- [Timeline preview](results/20260926T150842-80fb1152/update-analysis/timeline.svg)
- [Full pooled analysis](results/20260926T150842-80fb1152/update-analysis/summary.json)
- [All 10,800 raw measurement ticks](results/20260926T150842-80fb1152/update-analysis/tick_samples.csv)

The earlier session `20260926T145913-68354802` is not pooled: two measured runs
failed the old 30-second graceful-shutdown timeout during world saving and were
correctly rejected. The wait is now 120 seconds, still with bounded owned-process
termination and unchanged nonzero-exit rejection. All formal runs exited normally.

An every-tick-change smoke at 64 cows also passed request accounting and drain
checks: `20260926T144717-8896c990`. It is not included in the interval-40 timing
comparison. Existing live-allay and all three synchronous search scenes passed
real-server regression checks after the new hooks were added.

The production build remains untouched. All fixtures, observation mixins and
analysis code stay in the standalone benchmark kit; raw runtime artifacts remain
local and ignored by git.
