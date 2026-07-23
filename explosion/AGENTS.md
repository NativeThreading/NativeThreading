# Explosion

## Test

```bash
./gradlew :explosion:test
```

## Role

`explosion` owns parallel explosion processing. Keep its full control flow in
this module: eligibility checks, main-thread snapshot capture, pure worker
computation, main-thread world and entity application, and the vanilla fallback
path. Do not move this coordination into `core`.

`ServerExplosionMixin` is the integration boundary. `ExplosionHelper` provides
the precomputed ray data and collision helpers used by the explosion pipeline.
Worker code must consume captured data only. World mutation, entity damage, and
knockback application stay on the main thread.

## Scope

This module handles explosion ray tracing and entity damage.
