# NativeThreading

NativeThreading is a Minecraft 26.2 multi-module mod that parallelizes selected server workloads through mixins.

## Modules

- `core` provides shared execution, configuration, commands, and capture primitives. It does not own feature pipelines.
- `explosion`, `hopper`, and `redstone` own their respective workload pipelines, including their main-thread capture, pure worker computation, and main-thread application.
- `vectorial` is an optional accelerator for Structure-of-Arrays storage and SIMD operations.
- `fabric` and `neoforge` are aggregate loaders that package the workload modules for their platforms.

Keep feature-specific complexity in its owning workload module. In particular, explosion logic belongs in `explosion`, not `core`.

## Threading Boundary

Workers receive only immutable values or data captured on the main thread. Do not pass a generic `Level`, a live container, block entity, entity, or arbitrary mod callback to worker code. A subsystem captures inputs on the main thread, performs pure computation in workers, then applies world changes on the main thread.

Submitted `ParallelWorker` tasks enter a reentrant `SafeLevelAccess` scope. It is only a controlled worker-phase marker, not synchronization or permission for generic `Level`, container, entity, block-entity, or mod callback access. `runSafe` establishes the scope and always cleans it up when the task finishes.

## Build and Tests

```bash
./gradlew build
./gradlew :core:test
./gradlew :explosion:test
./gradlew :hopper:test
./gradlew :redstone:test
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

Scopes: `core`, `explosion`, `hopper`, `redstone`, `vectorial`, `build`, `docs`

## No Cross-Dependency

NativeThreading must not depend on NotEnoughPalette. Both mods are tested independently.
