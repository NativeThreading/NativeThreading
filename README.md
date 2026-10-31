# NativeThreading

*Parallelize selected heavy server workloads in Minecraft.*

NativeThreading is a server-side Minecraft 26.3 mod that moves selected vanilla
server workloads off the main thread through mixins, while keeping
vanilla-observable results identical. It currently parallelizes explosion
computation — TNT chain reactions and other large blasts.

Releases ship a Fabric jar and a NeoForge jar. Nothing is installed on the
client.

## Features

- **Explosion pipeline** — ray tracing and entity damage/exposure are computed on
  worker threads; chunk reads and world/entity writes stay on the main thread.
- **Vanilla-identical results** — the per-ray power draws come from
  `level.random` in vanilla order, so the destroyed block set and blast shape
  match vanilla for the same world and seed.
- **One switch per module** — `/parallel explosion` or `config/nt.json`; no
  per-optimization knobs.
- **Server-side only** — no client code, no protocol changes.

## Installation

Drop the jar matching your loader into `mods/`:

| Loader | Requirement |
|---|---|
| Fabric | Fabric Loader ≥ 0.19.5 + Fabric API |
| NeoForge | NeoForge 26.3 (tested with 26.3.0.41-beta) |

All modules are enabled by default and configured in `config/nt.json`:

```json
{
  "explosion": {
    "enabled": true
  }
}
```

Commands (OP level 2):

```
/parallel explosion                 status
/parallel explosion <true|false>    toggle parallel explosions
/parallel explosion reload          reload config/nt.json
/parallel explosion stats           chunk-grid cache and phase counters
```

## Performance

125 TNT blocks inside a 5×5×5 obsidian shell, ignited by a command block. Spark
sampled 25 s windows, 3 runs per configuration, averaged. Machine: i9-12900HX
(24 cores), measured cold after reboot. Every configuration includes Fabric API
and Lithium; `base` is vanilla + Lithium.

| Configuration | MSPT (mean) |
|---|---|
| base (vanilla + [Lithium](https://modrinth.com/mod/lithium)) | 198.0 ms |
| + [NotEnoughPalette](https://github.com/NativeThreading/NotEnoughPalette) | 190.0 ms |
| + NativeThreading | 47.2 ms |
| + NotEnoughPalette + NativeThreading | **45.7 ms** |

## Behavior notes

- The RNG is consumed draw-for-draw like vanilla, from the same `level.random`
  instance; no worker thread touches it.
- The destroyed block set is identical to vanilla. Which destroyed block a drop
  or fire roll lands on can differ because the block list is assembled in a
  different order (the drop table itself is unchanged and per-block random).
- Chunk loading and entity capture match vanilla at weak-load frontiers: an
  explosion does not see entities in chunks it just loaded.

Details and rationale: [`docs/dev/explosion-pipeline.md`](docs/dev/explosion-pipeline.md).

## Build

```bash
./gradlew build                 # compile + unit tests + validateMixinDiscipline
./gradlew :fabric:runGameTest   # full-server gametests (explicit; boots a server)
```

Output: `build/libs/native-threading-*.jar`. Requires JDK 25 (the benchmark kits
also use a Java 26 runtime).

## Development

Architecture rules, code organization, pipeline design and the pre-submit
self-check live in [`docs/dev/README.md`](docs/dev/README.md). Measurement kits
live in [`benchmarks/README.md`](benchmarks/README.md).

## License

MIT — see [LICENSE](LICENSE).
