# Vectorial

Structure-of-Arrays (SoA) entity data for the explosion pipeline.

## Why this module exists

Explosion entity damage needs a tight, allocation-free scan over every entity
in the blast AABB (position + bounding box + primed-TNT flag) on the worker
threads. `SoAStore` keeps those fields in per-field `double[]` arrays updated
from `Entity` on the main thread, so workers read contiguous arrays instead of
scattering through the entity heap — and the counted loops are auto-vectorized
by the JVM (SuperWord) without explicit SIMD code.

## What it costs (the reason to keep it optional)

- A mixin registers/unregisters every `Entity` and syncs fields each tick.
- A javaagent (`VectorialAgent`) rewrites `Entity` getters to read the SoA
  arrays first, so the data stays fresh; the agent must be attached and the
  module bundled in the loader jar.
- SoA duplicates entity state in memory (65 doubles per slot).

Deleting this module means the explosion capture must go back to
`level.getEntitiesOfClass` on the main thread (no worker-side SoA scan), and
the NeoForge loader already does exactly that — it does not bundle vectorial
and routes entity damage back to vanilla. The Fabric aggregate keeps it
because the benchmark (125-TNT chamber) shows the SoA capture is worth the
bytecode-injection complexity.

## Boundary

- SoA fill (mixin + `GeneratedSync.syncAll`) works whether or not the agent
  injected; getter rewriting is a pure optimization whose fallback is the
  original field read.
- Loaders gate on `SimdBatchOps.VECTORIAL_AVAILABLE` (class presence) — never
  on agent injection success.

## License

MIT
