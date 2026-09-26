# Allay Feasibility Results: 2026-09-26

## Verdict

**Feasible as an end-to-end flying-AI collection workload.** All eight formal
runs passed at 64, 128, 256 and 512 allays, two fresh JVMs/worlds per population.
All compartments collected diamonds through vanilla AI. No allays died or left
their assigned compartment, and no tracked diamond units were lost.

It is **not predominantly a pathfinder-only stress test**. At 512 allays,
PathFinder wall time accounted for about 13.47% of the measured tick-callback
interval, while the profile showed substantial game-event/listener, geometry,
entity sensing and movement work. Keep both this scenario and the ground-search
corpus when evaluating future pathfinding optimization.

## Configuration

- Minecraft 26.2 / Fabric Loader 0.19.3 / Fabric API 0.152.1+26.2.
- Spark 1.10.173; no NT, NEP, Lithium, or Carpet in the baseline.
- Intel i9-12900HX, affinity CPUs 0-15, Manjaro Linux.
- Arch OpenJDK 26.0.2.1, `-Xms2G -Xmx2G`, vector module, default GC.
- Fixed seed 8675309; eight compartments; one diamond per compartment per 20 ticks.
- 400 warmup ticks and 600 measured ticks, two repetitions per population.
- Game simulation frozen between phases; no extra AI warmup while Spark starts.
- Fixture jar SHA-256:
  `7866cfda4485952be5b1479af9c672d928e36b21eadb1438b6742be77c801d8c`.
- Harness source hash at formal launch:
  `563759aebc6a2ccb8317066c8098588f3dc084c762d26a2713f4698793a540ea`.
  This results document was added afterward. Individual source hashes, current
  commit and dirty diff are preserved in each run manifest.

All four groups used the same fixture binary. They ran sequentially without a
machine reboot or guaranteed thermal/background-service isolation. These are
development feasibility measurements, not controlled optimization speedup claims.

Commands (run sequentially):

```bash
python -B benchmarks/pathfinding/run.py --scene allay --entities 64 --label allay-64 --repeat 2
python -B benchmarks/pathfinding/run.py --skip-build --scene allay --entities 128 --label allay-128 --repeat 2
python -B benchmarks/pathfinding/run.py --skip-build --scene allay --entities 256 --label allay-256 --repeat 2
python -B benchmarks/pathfinding/run.py --skip-build --scene allay --entities 512 --label allay-512 --repeat 2
```

The archived first command used `--skip-build` too, reusing the clean-built jar
already verified by the preceding phase-handoff smoke. The commands above rebuild
the fixture once for a fresh reproduction.

## Results

Numbers are arithmetic means of two per-run statistics. Percentile columns are
means of per-run percentiles, not pooled percentiles. Each formal measurement
injected exactly 240 diamond units, independently of population.

| Allays | Mean tick interval ms | Tick p95 ms | Actual searches / 600 ticks | Picked units / 240 | Pickup fraction | Pickup p95 ticks | Navigating fraction |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 64 | 1.224 | 1.665 | 1,876.5 | 233.5 | 97.29% | 58.5 | 89.50% |
| 128 | 2.249 | 3.006 | 3,419 | 235.5 | 98.13% | 47.5 | 86.89% |
| 256 | 4.791 | 6.056 | 6,167 | 236 | 98.33% | 41 | 84.77% |
| 512 | 12.946 | 15.488 | 10,806 | 237.5 | 98.96% | 37 | 82.34% |

Every result satisfied `entity_ticks == entities * 600`; every measured handoff
had `gap_entity_ticks == 0`. In total, the formal measurement phases observed
1,152,000 entity ticks, 44,537 actual searches and 1,885 pickups from 1,920 newly
introduced diamond units. The remaining 35 units were still present at cutoff,
not lost. Warmup-carried pickups are accounted separately.

All populations used the identical measured drop-schedule fingerprint
`d087eb9b8f72401b`. Natural AI paths/counts were not forced to be identical.

| Allays | Total PathFinder wall ms / run | Mean query ms | Mean maintenance ms/tick | Mean tick CV |
| ---: | ---: | ---: | ---: | ---: |
| 64 | 120.128 | 0.0640 | 0.0254 | 1.45% |
| 128 | 239.859 | 0.0702 | 0.0241 | 0.82% |
| 256 | 512.105 | 0.0831 | 0.0411 | 1.58% |
| 512 | 1,046.096 | 0.0968 | 0.0669 | 0.57% |

The 256-allay search-total CV was 7.68%, and several tail/counter CVs exceeded 5%.
Only two runs were made per population; they demonstrate operation and scaling,
not a precise distribution or a statistically established performance difference.
Pickup latency is conditional on successful collection and must be interpreted
together with the outstanding cohorts. The cutoff has no extra drain period.

The average navigation-active fraction includes random wandering. Increasing
population from 64 to 512 increased search volume about 5.8 times, not eight
times. The diamond arrival rate remains fixed while entities compete and wander.

## Profile Evidence

Inspected the first formal 64-allay and 512-allay Spark profiles. For 512 allays:

`results/20260926T061625-e8ed0c24/01-allay-1/profile-2026-09-26_14.17.22.sparkprofile`

The profile sampled 30,508 ms of Server thread stacks; the parser classified
8,016 ms as active Java and 22,492 ms as native/idle. Classification is heuristic,
not an exact CPU-utilization measurement.

| Method | Self sampled ms | Share of parser-classified active Java |
| --- | ---: | ---: |
| `Vec3.add` | 640 | 7.98% |
| `Vec3i.distToLowCornerSqr` | 496 | 6.19% |
| `AABB.intersects` | 412 | 5.14% |
| `EuclideanGameEventListenerRegistry.getPostableListenerPosition` | 352 | 4.39% |
| `EuclideanGameEventListenerRegistry.visitInRangeListeners` | 252 | 3.14% |
| `VibrationSystem.Listener.getListenerSource` | 240 | 2.99% |

An inclusive `GameEventDispatcher.post` branch alone occupied 2,868 sampled ms,
compared with 596 and 376 ms in two separate outer PathFinder call branches.
Nested PathFinder overloads were **not added** to those outer values. Flying
node evaluation, nearest-living-entity sensing and entity pushing were present,
confirming the intended live AI/search/movement workload.

The lesson is not to disable vibration listeners or other vanilla work to improve
the graph: doing so would change this end-to-end workload. Use the profile to
understand the maximum visible benefit and possible non-pathfinding regressions.
No optimization was implemented or benchmarked against these numbers.

## Archives

Raw JSON, server logs/worlds, mod jars/hashes and Spark files remain locally under
ignored results directories:

| Population | Summary |
| ---: | --- |
| 64 | [20260926T060846-7a214da2](results/20260926T060846-7a214da2/summary.json) |
| 128 | [20260926T061122-e3c3f8c2](results/20260926T061122-e3c3f8c2/summary.json) |
| 256 | [20260926T061355-703c06a4](results/20260926T061355-703c06a4/summary.json) |
| 512 | [20260926T061625-e8ed0c24](results/20260926T061625-e8ed0c24/summary.json) |

The early `allay-smoke` run proved pickups and real merging but predates frozen
phase handoffs. `20260926T053813-c79ee16e` correctly rejected an off-by-one frozen
transition tick. Neither is a formal baseline. The corrected two-run handoff
smoke is `20260926T060515-570ea8b6`.

## Verification

- 58 Python runner/validation tests passed, preserving the original 38 tests.
- 19 pure Java tests passed for geometry/schedules/statistics and merge/pickup
  ledger conservation, including full/partial transfers and FIFO attribution.
- `validateMixinDiscipline`, `core:test`, and `explosion:test` passed.
- All three original search scenes passed a real-server regression smoke using
  the new fixture jar: `results/20260926T061949-a68dd722/summary.json`.
- All benchmark processes were shut down. The existing TNT server/world was not
  modified. No production pathfinding optimization or release-jar dependency was
  added.

See [ALLAY.md](ALLAY.md) for the exact workload, metric boundaries and limitations.
