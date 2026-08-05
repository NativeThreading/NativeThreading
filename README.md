# NativeThreading

Multi-module Fabric mod for Minecraft 26.2, parallelizing 4 subsystems via mixins.

## Modules

| Module | What it parallelizes |
|--------|---------------------|
| **explosion** | TNT/creepers — ray tracing + entity damage |
| **hopper** | Two-phase hopper transfers (plan in parallel, execute sequentially) |
| **redstone** | Wire power propagation + diode ticks |
| **vectorial** | Structure-of-Arrays entity data + SIMD batch operations |

All share `core/` (thread pool, safe world access, deferred writes, config).

## Installation

Drop `native-threading-1.0.0.jar` into your `mods/` folder. Requires Fabric Loader ≥ 0.19.3.

Configure modules in `config/nt.json`.

## Performance

Combined with [NotEnoughPalette](https://github.com/NativeThreading/NotEnoughPalette) and Lithium, 125 TNT explosion on a 24-core i9-12900HX:

| Metric | Vanilla | NEP + Lithium + NT |
|--------|---------|---------------------|
| TPS | 13.4 | **18.3** |
| MSPT (mean) | 769 ms | **55 ms** |

## Build

```bash
./gradlew build -x test
```

Output: `build/libs/native-threading-*.jar`

## License

MIT — see [LICENSE](LICENSE).
