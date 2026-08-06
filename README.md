# NativeThreading

Multi-module Fabric mod for Minecraft 26.2, parallelizing selected server workloads via mixins.

## Modules

| Module | What it parallelizes |
|--------|---------------------|
| **explosion** | TNT/creepers — ray tracing + entity damage |

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

**RNG(与原版序列逐位一致)**:原版 `ServerExplosion` 在计算射线时,按 xx→yy→zz 顺序遍历 16³ 网格边界,每条射线在主线程取一次 `level.random.nextFloat()`(功率扰动 `0.7 + u * 0.6`)。NativeThreading 的并行路径在主线程以**完全相同的顺序**预生成全部 1352 个射线功率(同样调 `level.random.nextFloat()`),worker 只消费预先算好的数组、全程不接触任何 RNG——因此**同一存档、同一位置、同样数量的 TNT 引爆,随机序列与原版逐位一致**,爆炸形态按原版随机种子可复现。

> 早期实现曾用 `ThreadLocalRandom`,导致序列不可复现(且当时误以为 worker 需要各自取随机数);后来发现 1352 次随机全部在主线程串行生成、worker 零 RNG 访问,遂改回 `level.random`。`core` 曾有一个 `LevelRandomMixin`,把每个 `Level` 的 `random` 替换为 `ThreadSafeRandomSource`(为已删除的并行实体 tick 准备的线程安全包装),这会让序列经一次 `nextLong()` 派生、不再是纯原版逐位——该 mixin 已随并行实体 tick 一并移除,`level.random` 保持原版实例。原版爆炸本身也是随机的(每次结果都不同),但 NT 与原版共享同一随机序列。

**Vectorial(已移除)**:早期爆炸实体捕获走 SoA 实体数据以换取紧凑内存布局,但实测表明:2MB+ 的 SoA 字段数组只为捕获路径约 10 个字段服务、其余 55 个字段无消费方,且每 tick 全量同步开销与 vanilla 空间查询(`level.getEntities`)相当。捕获已改回主线程空间查询构建 snapshot,worker 阶段不变,SoA 模块(含 mixin+javaagent)随之移除。

## Build

```bash
./gradlew build -x test
```

Output: `build/libs/native-threading-*.jar`

## License

MIT — see [LICENSE](LICENSE).
