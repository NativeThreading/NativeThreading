# Explosion Parallelization

Module of [NativeThreading](../README.md): parallel explosion computation for
Minecraft 26.3 servers. Ray tracing and entity damage/exposure run on worker
threads; chunk reads and all world/entity writes stay on the main thread.

Install the NativeThreading release, not this directory — the root README covers
installation, performance, commands and configuration.

## Build and test

From the repository root:

```bash
./gradlew :explosion:test
./gradlew :explosion:build
```

The module is also a standalone Gradle build (own settings and wrapper) for
working on explosion in isolation; the included build is what the release uses.

## Design

Pipeline phases, class map, invariants, config keys and tests:
[`docs/dev/explosion-pipeline.md`](../docs/dev/explosion-pipeline.md).

Module-local agent notes: [`AGENTS.md`](AGENTS.md).
