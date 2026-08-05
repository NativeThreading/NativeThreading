# Core

`core` owns shared execution, configuration, commands, and capture primitives for NativeThreading. It does not own workload pipelines. Feature modules own the capture, compute, and application details for their subsystems.

## Build and Tests

```bash
./gradlew :core:test
./gradlew :core:build
```

## Ownership

- `ParallelWorker` and `ParallelThreadPool` provide shared execution primitives.
- `ParallelConfig` and its storage types provide shared configuration sections in `config/nt.json`.
- `ParallelCommand` provides shared command registration.
- Capture helpers such as `ChunkGrid` and deferred-write primitives support subsystem pipelines.

Keep explosion, hopper, and redstone behavior in their modules. Do not move feature-specific scheduling, state, or computation into `core`.

## Worker Boundary

Each subsystem follows this boundary:

1. Capture the required inputs on the main thread.
2. Run pure computation on workers using only captured or immutable data.
3. Apply world changes on the main thread.

Generic `Level` access is not supported in worker code. Do not pass live levels, containers, block entities, entities, or arbitrary mod callbacks to workers. Deferred writes must be drained and applied on the main thread.

Submitted `ParallelWorker` tasks enter a reentrant `SafeLevelAccess` scope. It is only a controlled worker-phase marker, not synchronization or permission for generic `Level`, container, entity, block-entity, or mod callback access. `runSafe` establishes the scope and always cleans it up when the task finishes.

## Commands and Configuration

Register command extensions through `ParallelCommand.registerSubCommand()`. Define each configuration section with `ParallelConfig` and keep its data under `config/nt.json`.

## Compatibility

Core supports the Fabric and NeoForge aggregate loaders. Keep it server-side and avoid client code or client mixins.

## No Cross-Dependency

NativeThreading must not depend on NotEnoughPalette. Both mods are tested independently.
