# Redstone

## Test

```bash
./gradlew :redstone:test
```

## Role

`redstone` parallelizes wire power propagation and batches diode ticks.
`RedstoneWireHelper` builds the wire graph and captures block signals on the
main thread, relaxes power values from that snapshot on workers, then applies
block updates and neighbor notifications on the main thread.

`DiodeTickBatcher` follows the same boundary: capture diode state and input on
the main thread, compute a transition from the snapshot, then apply state
changes and any rescheduled tick on the main thread.

## Scope

Do not read or mutate world state from worker computation. Keep mixins limited
to routing vanilla wire and diode work through these capture, compute, apply
pipelines.
