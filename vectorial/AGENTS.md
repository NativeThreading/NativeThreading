# Vectorial

## Test

```bash
./gradlew :vectorial:test
```

## Role

`vectorial` is an optional SoA and SIMD accelerator for consumers that can use
batched entity data. `SoAStore` maintains the entity field representation, and
the entity mixin keeps supported fields synchronized with that store.

It does not own entity ticking, scheduling, or parallel entity behavior. Keep
its work focused on data layout, field synchronization, generated field
metadata, and SIMD-friendly batch operations.

## Scope

Preserve the optional accelerator role. Modules may depend on its data and
operations without turning Vectorial into an entity-ticking subsystem.
