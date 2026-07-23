# Hopper

## Test

```bash
./gradlew :hopper:test
```

## Role

`hopper` replaces hopper ticking with a two-phase pipeline. `LevelMixin`
collects hoppers while preserving ordinary block-entity ticks on the main
thread.

`HopperParallelHelper` captures hopper, container, and item-entity state on the
main thread, computes a transfer plan from those snapshots on workers, then
validates and executes the plan on the main thread. Keep all inventory and
world mutation in the apply phase.

## Scope

Changes here must preserve the capture, compute, apply boundary and vanilla
fallback behavior when parallel hopper processing is disabled or fails.
