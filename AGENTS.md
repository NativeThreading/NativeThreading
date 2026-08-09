# NativeThreading

NativeThreading is a Minecraft 26.2 multi-module mod that parallelizes selected server workloads through mixins.

## Modules

- `core` provides shared execution, configuration, commands, and capture primitives. It does not own feature pipelines.
- `explosion` owns the explosion workload pipeline, including its main-thread capture, pure worker computation, and main-thread application.
- `fabric` and `neoforge` are aggregate loaders that package the workload modules for their platforms.

Keep feature-specific complexity in its owning workload module. In particular, explosion logic belongs in `explosion`, not `core`.

## Threading Boundary

Workers receive only immutable values or data captured on the main thread. Do not pass a generic `Level`, a live container, block entity, entity, or arbitrary mod callback to worker code. A subsystem captures inputs on the main thread, performs pure computation in workers, then applies world changes on the main thread.

Submitted `ParallelWorker` tasks enter a reentrant `SafeLevelAccess` scope. It is only a controlled worker-phase marker, not synchronization or permission for generic `Level`, container, entity, block-entity, or mod callback access. `runSafe` establishes the scope and always cleans it up when the task finishes.

## Architecture Discipline

Hard rules in `docs/architecture-discipline.md` apply to every change; violate one and the change does not land. Highlights:

- **M1/M2**: mixins are injection points only (no pipeline logic), single mixin class ≤ 250 lines.
- **C1/C2**: cross-blast reuse must be `static`; any cache bound to a world must key on the `ServerLevel` identity.
- **T3**: on worker failure, degrade serially with already-drawn/captured data — never let vanilla re-run work that consumed RNG or did partial work.
- **R1/R2**: RNG consumption must match vanilla draw-for-draw; the same RNG work must never run twice.
- **V1/V2**: optimizations default to bit-identical vanilla behavior; numeric paths need differential tests.
- **D1/D2**: no data without a consumer; no optimization without a profile basis and a test.
- **B1/B2**: benchmarks use fixed warmup and same-commit comparisons; judge by profile self-time, not allocation bytes.

**Code organization** (`docs/code-organization.md`, binding for any new feature): files split by thread domain + state ownership (Context / Stage / Cache / Helper / Mixin shell, template in §2.4); modules are core (primitives only) → workload (one pipeline each, zero cross-workload deps) → loader shells (§3); thread-safety layer is capture → worker-pure (immutable values / read-only views only) → apply, static caches answer the four questions (§4); §5 is the new-feature landing checklist. Agent-facing copy: `.agents/skills/native-threading-architecture/`.

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

Scopes: `core`, `explosion`, `build`, `docs`

## No Cross-Dependency

NativeThreading must not depend on NotEnoughPalette. Both mods are tested independently.
