# NativeThreading

Multi-module Fabric mod for Minecraft 26.2, parallelizing selected server workloads via mixins.

## Modules

| Module | What it parallelizes |
|--------|---------------------|
| **explosion** | TNT/creepers — ray tracing + entity damage |
| **vectorial** | Structure-of-Arrays entity data backing the explosion entity queries |

All share `core/` (thread pool, safe world access, deferred writes, config).

## Installation

Drop `native-threading-1.0.0.jar` into your `mods/` folder. Requires Fabric Loader ≥ 0.19.3.

Configure modules in `config/nt.json`.

## Performance

**场景**:125 个 TNT 方块置于 5×5×5 黑曜石密闭外壳中(防止爆炸击穿),命令方块点燃触发链式反应,Spark 每轮采样 25s。机器:i9-12900HX 24 核,系统重启后冷态测量。每组合 3 轮取均值。

对照组构成(所有组合均加载 Fabric API 等基础模组,`base` 组即包含 [Lithium](https://modrinth.com/mod/lithium)):

| 组合 | 已加载模组(除基础模组外) | MSPT (mean) |
|------|--------------------------|-------------|
| base(原版+锂) | — | 198.0 ms |
| + NEP | [NotEnoughPalette](https://github.com/NativeThreading/NotEnoughPalette) | 190.0 ms |
| + NT | NativeThreading | 47.2 ms |
| + NEP + NT | NotEnoughPalette + NativeThreading | **45.7 ms** |

> 纯 vanilla(不含 Lithium)未在本环境单独测量——`base` 组合已包含 Lithium,上表用它代表"原版+锂"基线。769 ms 是早期无黑曜石外壳、爆炸击穿导致实体数量失控时的旧数据,不适用于当前受控场景。

**RNG 差异(不可规避的架构取舍)**:原版 `ServerExplosion` 的射线功率带 `level.random.nextFloat()` 扰动(0.7~1.3 倍半径)。NativeThreading 的并行射线路径改用每线程独立的 `ThreadLocalRandom`——统计分布完全一致(`0.7 + u * 0.6`),但随机序列来自不同的实例。

- **影响**:爆炸的具体形态不再由原版世界的随机数序列决定。同一个存档、同一位置、同样数量的 TNT 重复引爆,结果序列与原版(以及开启 NT 前后)不可复现;依赖随机确定性的工具——世界回放、自动化测试、存档重放——会观察到与预期不符的偏差。
- **为什么不可避免**:`level.random` 是全局共享的可变状态,本身非线程安全。并行射线(1352 条)需要在 worker 线程上各自取随机数;若对 `level.random` 加锁串行化,每一 tick 的射线阶段都会在锁上排队,并行化的收益会被锁竞争完全抵消。因此 worker 必须使用线程本地随机源——这是并行架构的固有代价,不是实现疏漏。
- **它本来就是随机的**:原版爆炸每次结果也都不相同——`level.random` 每次调用都推进状态,爆炸形态本就由随机数决定。这里的"差异"不是"确定性 vs 随机",而是"同一个均匀分布的两个随机序列来源";对单次爆炸而言两者都是均匀随机,统计行为一致,不构成对随机性的破坏。

**Vectorial 取舍**:爆炸的实体捕获走 `vectorial` 的 SoA 实体数据(标量循环,由 JVM SuperWord 自动向量化)。该模块通过 mixin+javaagent 把实体字段复制进连续数组,换取捕获阶段更紧凑的内存布局;代价是 Entity getter 被 agent 改写、加载器必须捆绑 vectorial 并附加 agent。Fabric 聚合加载器启用它;NeoForge 聚合加载器**不**捆绑 vectorial,实体伤害回退原版路径。详见 [vectorial/README](vectorial/README.md)。

## Build

```bash
./gradlew build -x test
```

Output: `build/libs/native-threading-*.jar`

## License

MIT — see [LICENSE](LICENSE).
