# NativeThreading

NativeThreading is a Minecraft 26.2 multi-module mod that parallelizes selected server workloads through mixins.

## Modules

- `core` provides shared execution, configuration, commands, and capture primitives. It does not own feature pipelines.
- `explosion` owns the explosion workload pipeline, including its main-thread capture, pure worker computation, and main-thread application.
- `vectorial` provides Structure-of-Arrays entity storage backing the explosion entity queries.
- `fabric` and `neoforge` are aggregate loaders that package the workload modules for their platforms.

Keep feature-specific complexity in its owning workload module. In particular, explosion logic belongs in `explosion`, not `core`.

## Vectorial Agent Boundary

`vectorial` has two independent dependency chains. They must stay decoupled:

1. **SoA data fill (Mixin-only, agent-independent)** — `EntityMixin` registers/unregisters entities and `GeneratedSync.syncAll` writes the `SoAStore` field arrays every tick using the entity's getters. This path works whether or not the agent injected.
2. **Entity getter redirect (agent-dependent)** — `VectorialAgent`/`VectorialTransformer` rewrite `Entity` getters to read the SoA arrays first (`_sl >= 0 ? SoA value : this.field`). This is a pure performance optimization; the fallback to `this.field` is embedded in the generated getter body.

Agent injection failure (e.g. a future JVM forbidding dynamic agent attach) must never break explosion. `GeneratedSync` reads getters, so the SoA arrays stay correct regardless of injection; `SimdBatchOps` reads those arrays and therefore does not depend on `VectorialTransformer.isTransformed()`. Do not gate any workload pipeline on injection success.

## Threading Boundary

Workers receive only immutable values or data captured on the main thread. Do not pass a generic `Level`, a live container, block entity, entity, or arbitrary mod callback to worker code. A subsystem captures inputs on the main thread, performs pure computation in workers, then applies world changes on the main thread.

Submitted `ParallelWorker` tasks enter a reentrant `SafeLevelAccess` scope. It is only a controlled worker-phase marker, not synchronization or permission for generic `Level`, container, entity, block-entity, or mod callback access. `runSafe` establishes the scope and always cleans it up when the task finishes.

## Worker Phase Discipline

Parallelize one subsystem operation at a time. Its worker pool may run only one
homogeneous pure task kind during a phase; capture, compute, and apply phases
remain ordered. Do not batch or reorder separate operations merely to fill a
pool when their vanilla-observable world, entity, or callback order can differ.

If a worker result is used by this tick, block the main thread at the phase
boundary until the task completes before executing dependent work. Tasks whose
results are not part of this tick's observable state, such as pathfinding, are
the exception and may continue asynchronously.

## Build and Tests

```bash
./gradlew build
./gradlew :core:test
./gradlew :explosion:test
./gradlew :vectorial:test
```

Output: `build/libs/native-threading-*.jar`

## Commit Convention

[Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <description>

perf(core): add shared capture primitive
perf(explosion): improve ray tracing
fix(redstone): honor wire threshold override
chore(build): update Gradle wrapper
```

Scopes: `core`, `explosion`, `vectorial`, `build`, `docs`

## No Cross-Dependency

NativeThreading must not depend on NotEnoughPalette. Both mods are tested independently.
