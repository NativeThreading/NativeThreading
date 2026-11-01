# NativeThreading

<img src="fabric/src/main/resources/assets/native-threading/icon.png" width="96" height="96" alt="NativeThreading icon" align="right">

*Thread selected heavy server workloads off the main thread in Minecraft.*

NativeThreading is a Minecraft 26.3 mod that moves selected vanilla server
workloads off the main thread through mixins, while keeping
vanilla-observable results identical. It currently offloads explosion
computation — TNT chain reactions and other large blasts — to worker threads.

Releases ship a Fabric/NeoForge mod jar and, separately, a Leaves plugin jar.
The mod jar runs inside a dedicated server or a client's integrated
(single-player) server; there is no client-side code or protocol change. The
Leaves plugin uses Leaves' leavesclip native mixin loader and is
dedicated-server only.

## Features

- **Explosion pipeline** — ray tracing and entity damage/exposure are computed on
  worker threads; chunk reads and world/entity writes stay on the main thread.
- **Vanilla-identical results** — the per-ray power draws come from
  `level.random` in vanilla order, so the destroyed block set and blast shape
  match vanilla for the same world and seed.
- **One switch per module** — `/nt explosion` or `config/nt.json`; no
  per-optimization knobs.
- **Dedicated or integrated servers** — the same jar works in a dedicated
  server and in a client's single-player world; no client code, no protocol
  changes.

## Installation

For Fabric and NeoForge, drop `native-threading-<version>.jar` into `mods/`:

| Loader | Requirement |
|---|---|
| Fabric | Fabric Loader ≥ 0.19.5 + Fabric API |
| NeoForge | NeoForge 26.3 (tested with 26.3.0.41-beta) |

For Leaves, drop `native-threading-leaves-<version>.jar` into `plugins/` and
start the server with `-Dleavesclip.enable.mixin=true`. This needs a
leavesclip-enabled Leaves build (`leaves_api_version`, currently 26.2); plain
Paper servers and non-leavesclip Leaves builds do not load mixin plugins. The
plugin compiles the same `core`/`explosion` sources and injects the same two
mixins through leavesclip's native mixin loader; constraints and the
verification procedure live in
[`docs/dev/leaves-platform.md`](docs/dev/leaves-platform.md).

The same jar can also be installed on a Fabric or NeoForge client to run in
the integrated server of a single-player world. World state captured by the
pipeline (chunk-grid cache, last blast's entity references) is dropped when the
server stops, so leaving and re-entering a world does not retain the old one.

All modules are enabled by default and configured in `config/nt.json`:

```json
{
  "explosion": {
    "enabled": true
  }
}
```

Commands (OP level 2; in single-player, cheats must be enabled — without them
the config file still works):

```
/nt explosion                     status
/nt explosion <true|false>        toggle threaded explosions
/nt explosion reload              reload config/nt.json
/nt explosion stats               counters, phase timings, ChunkGrid cache
/nt explosion stats reset         reset stats counters
```

The same `/nt` tree works on Leaves through the Bukkit command map (console,
ops, or the `native-threading.admin` permission node).

`/nt explosion stats` reports per-stage blasts/victims, exposure-path routing
(live vs flat), dedup/grouping hits, clear-region fast-path hits, average
capture/worker/apply timings, a block-capture phase breakdown (RNG / flat fill /
shape boxes / tier capture) and the ChunkGrid cache miss rate; `stats reset`
zeroes the counters without touching the cached grid.

## Performance

125 TNT blocks inside a 5×5×5 obsidian shell, ignited by a command block. Spark
sampled 25 s windows, 3 runs per configuration, averaged. Machine: i9-12900HX
(24 cores), measured cold after reboot. Every configuration includes Fabric API
and Lithium; `base` is vanilla + Lithium. These numbers are dedicated-server
MSPT; a single-player client shares cores with the render thread, so its
frame-time effect has not been measured and is not claimed here.

| Configuration | MSPT (mean) |
|---|---|
| base (vanilla + [Lithium](https://modrinth.com/mod/lithium)) | 198.0 ms |
| + [NotEnoughPalette](https://github.com/NativeThreading/NotEnoughPalette) | 190.0 ms |
| + NativeThreading | 47.2 ms |
| + NotEnoughPalette + NativeThreading | **45.7 ms** |

On Leaves 26.2 (leavesclip native mixins, no Fabric API/Lithium) the same 125-TNT
chamber with `max-tnt-per-tick: 0` measured ~5.9 TPS / 169 ms MSPT with vanilla
explosion handling and ~14.5 TPS / 69 ms with the plugin (tick p50 170 ms → 64 ms,
same session, fixed warmup); an earlier build measured 6.5-6.8 → ~15.5 TPS. The
Paper-family default TNT cap of 100 masks most of the chain reaction, so pin it
to 0 (or ≥ the scene's TNT count) for parity or benchmarks.

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
./gradlew :leaves:jar           # Leaves plugin only (no Loom remap)
./gradlew :fabric:runGameTest   # full-server gametests (explicit; boots a server)
```

Output: `build/libs/native-threading-<version>.jar` (Fabric/NeoForge aggregate)
and `build/libs/native-threading-leaves-<version>.jar` (Leaves plugin). Requires
JDK 25 (the benchmark kits also use a Java 26 runtime).

## Development

Architecture rules, code organization, pipeline design and the pre-submit
self-check live in [`docs/dev/README.md`](docs/dev/README.md). Measurement kits
live in [`benchmarks/README.md`](benchmarks/README.md).

## License

MIT — see [LICENSE](LICENSE).
