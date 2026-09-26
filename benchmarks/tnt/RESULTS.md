# TNT Long-Run Results: 2026-09-26

## Conclusion

The measured workload supports the user's expectation of a sustained, broadly
stable load in this **continuously replenished** TNT chamber. The earlier concern
that a destructible room would progressively empty out does not describe this
room's every-tick refill mechanism.

That conclusion needs one qualification: stable explosion throughput per tick
does not mean perfectly stationary MSPT. Timing showed modest window variation
and a downward shift within the longer runs. No progressive TNT/item buildup or
loss of explosion activity was observed in the measured counters.

## Sample And Controls

- Three independently restarted JVMs, sequentially run on the same machine.
- Same archived initial world, observer/runtime/mod bytes, JVM flags and affinity.
- Each run: 200 warmup ticks, then **3,000 measured ticks**, in 200-tick windows.
- Total: **9,000 measured ticks, 45 non-overlapping windows, 1,109,440 explosions**.
- Measured time totals 5,655.467 seconds (94.26 minutes); with warmup/startup the
  whole batch took approximately 100 minutes.
- Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.152.1+26.2, Spark 1.10.173.
- No NT, NEP, Lithium or Carpet loaded. These numbers describe the unoptimized
  Fabric/vanilla explosion path, not NativeThreading's current performance.
- Intel i9-12900HX, CPUs 0-15, OpenJDK 26.0.2.1, `-Xms2G -Xmx2G`, vector module.
- No reboot, thermal isolation or CPU-frequency trace was taken. Normal desktop
  background services remained; no causal claim about JIT/thermals is possible.
- Observer jar SHA-256:
  `d75f002b01bb2bda39f9cfaaa7fff1899fd2b03730a613521b4b9e748a6dccae`.
- Harness source fingerprint at launch:
  `754e360410dee1a38c45379b6df765003e4668f542d67893d52a44570e668e9b`.
  Analysis tooling and this documentation were added after measurement.

The source pack was explicitly activated once before each observation. During
measurement it continued its original every-tick stone/TNT refill. Nothing was
additionally cleared, reset, or teleported by the observer or runner.

## MSPT

| Run | Actual measured seconds | Mean ms | p95 ms | p99 ms | Window-mean sample CV | Last 5 windows vs first 5 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 01 | 1,907.165 | 635.684 | 781.363 | 845.819 | 4.373% | -7.839% |
| 02 | 1,899.926 | 633.269 | 774.631 | 845.078 | 2.186% | -2.408% |
| 03 | 1,848.376 | 616.088 | 746.156 | 804.655 | 2.762% | -4.401% |

All three runs satisfied exact tick/window counts and input-integrity checks.
Between-run mean MSPT sample CV was **1.701%**.

Pooled actual 9,000-tick statistics, not means of per-window percentiles:

| Statistic | Milliseconds |
| --- | ---: |
| Mean | 628.347 |
| p50 | 624.554 |
| p95 | 768.588 |
| p99 | 836.823 |
| Maximum | 1,023.670 |
| Population standard deviation | 81.832 |

Individual ticks vary more than smoothed windows: pooled population CV is
13.023%, versus roughly 2-4% for each run's window means. Every measured tick
exceeded 50 ms; this is intentionally an overloaded workload, not a 20-TPS test.

## Workload Stability

| Counter | Observation |
| --- | --- |
| Mean explosions/tick across windows | 123.271 |
| Window explosions/tick range | 121.470-124.380 |
| Window explosions/tick sample CV | **0.489%** |
| Explosion/tick first-to-last-third change, runs 01/02/03 | -0.029% / -0.687% / +0.380% |
| End-window TNT counts | Mostly about 2,300-2,500, no sustained accumulation |
| End-window TNT first-to-last-third change | +0.837% / -2.100% / -0.541% |
| End-window item counts | 0-6 |
| Loaded chunk count | 961 in every measured window |
| Measured windows without explosions | 0 |

Run 02 had a localized end-window TNT count of 2,185 in window 13, followed by
recovery. It was not a monotonic population decline. The snapshots cannot prove
that every intermediate entity/spatial state was identical, but the measurements
do not support either runaway accumulation or gradual workload exhaustion.

The large entity population was primarily **primed TNT**, not thousands of item
drops. Counting all entities without classifying them would obscure that point.

## Timing Drift And Limits

Run 01 changes to a lower MSPT level around measured minute 20; run 03 also has
a lower later level. Dividing window MSPT by explosions/tick does not remove the
shift: normalized first-to-last-third changes are -7.816%, -1.734%, -4.765%.
This normalization includes refilling, entity motion and other tick work; it is
not a direct timer of the explosion implementation.

GC accounted for approximately 0.114%, 0.114%, 0.121% of measured wall time
(2,180 / 2,171 / 2,237 ms). These totals do not establish a cause for the shifts.
Neither JIT compilation nor thermal/frequency conditions were instrumented.

Window MSPT lag-1 correlations were 0.848, 0.326 and 0.715. Adjacent windows should
not be treated as 45 independent experimental replicates. There are three JVM
replicates; the window statistics and OLS trends are descriptive, without an
independence-based confidence interval or significance claim.

Practical implication: this chamber is suitable for a sustained explosion
benchmark. Keep the workload rather than rejecting it on an assumed drift risk.
For optimization comparisons, use fixed work/warmup budgets, repeated runs and
balanced on/off ordering. A single 20-30-second observation cannot reliably
distinguish a small speedup from the timing shifts measured here.

## Profile And Observer Checks

The first full Spark profile contains 32 time windows and confirms a real heavy
explosion workload. Its leading Server thread self-time method was
`PalettedContainer.get` (234,448 sampled ms, 12.40% of parser-classified active
Java time), followed by geometry, palette/bit storage and chunk access methods.
The observer itself accounted for 32 sampled ms self-time in that profile.

MSPT results above come from exact tick records, not the profiler's rolling
snapshot. Window reduction, snapshots and output flushing run after the tick
timer and are excluded from recorded MSPT, though they still consume wall time.
The profiler has asynchronous start/stop margins around the measured interval.

## Artifacts

Local batch: `results/20260926T105430-127665c9/` (ignored by git).

- [Four-panel time-series chart](results/20260926T105430-127665c9/timeline.svg)
- [Batch summary](results/20260926T105430-127665c9/summary.json)
- [Detailed analysis](results/20260926T105430-127665c9/analysis.json)
- [All 45 measured windows](results/20260926T105430-127665c9/windows.csv)
- [Run 01](results/20260926T105430-127665c9/01/summary.json)
- [Run 02](results/20260926T105430-127665c9/02/summary.json)
- [Run 03](results/20260926T105430-127665c9/03/summary.json)

Each run retains its exact world/mod/config inputs, console log, raw tick CSV,
window JSONL, manifest and local Spark profile. Earlier pilots are not pooled:
the restore-only pilot was correctly rejected for zero explosions, and short
tick-mode pilots only verified counters and repeat handling.

The observer remains a standalone build and all experiment processes have exited.
The original server/world and production build configuration were not modified.
