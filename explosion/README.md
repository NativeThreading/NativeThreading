# Explosion Parallelization

[![Release](https://img.shields.io/github/v/release/uright008/explosion)](https://github.com/uright008/explosion/releases)
[![Pre-release](https://github.com/uright008/explosion/actions/workflows/pre-release.yml/badge.svg)](https://github.com/uright008/explosion/actions/workflows/pre-release.yml)

将 Minecraft 爆炸计算从单线程改为多线程并行，大幅提升有大量爆炸（TNT 链式反应、凋灵轰炸等）时的服务端 TPS。

**纯服务端模组**，客户端无需安装。

## 性能

测试方法：命令方块持续 fill 一个 5 边长的 TNT 正方体，点燃其中一个，spark 记录一分钟。

| 配置 | spark 报告 | MSPT 95%ile |
|------|-----------|-------------|
| 原版 | [link](https://spark.lucko.me/4678Vn3HEY) | ~277        |
| 并行 | [link](https://spark.lucko.me/TN72XtEWPH) | ~74.5         |
| 锂 | [link](https://spark.lucko.me/PdEOdDtmz7) | ~130        |
| 并行+锂 | [link](https://spark.lucko.me/r2exD1Slbc) | ~37.8       |

建议同时使用 [Lithium](https://modrinth.com/mod/lithium) ——爆炸并行优化爆炸计算，锂优化实体移动时碰撞计算，互补。

## 指令

所有指令需要 OP 权限（Level 2 / Gamemaster）。

```
/explosionparallel                          查看当前状态
/explosionparallel true|false              开关并行爆炸（默认开）
/explosionparallel sampling                查看采样质量
/explosionparallel sampling accurate       原版精度 ~45 采样/实体（默认）
/explosionparallel sampling fast           快速模式 ~24 采样/实体
/explosionparallel sampling aggressive     激进模式 ~12 采样/实体
/explosionparallel raylookup               查看射线查表状态
/explosionparallel raylookup true|false    开关射线查表加速（默认关）
/explosionparallel adaptiveRays <0..16>    自适应射线网格（默认 0=关闭）
/explosionparallel preciseRays             查看精确射线模式状态
/explosionparallel preciseRays true|false  开关精确射线追踪（默认开）
```

## 配置文件

`config/nt.json` 的 `explosion` 节，重启后自动保留设置。

```json
{
  "enabled": true,
  "samplingFactor": 2.0,
  "rayLookup": false,
  "adaptiveRays": 0,
  "preciseRays": true
}
```

| 字段 | 默认 | 说明 |
|------|------|------|
| `enabled` | `true` | 并行爆炸总开关 |
| `samplingFactor` | `2.0` | 实体可见性采样精度系数。`2.0`=accurate, `1.0`=fast, `0.5`=aggressive |
| `rayLookup` | `false` | 射线查表加速 |
| `adaptiveRays` | `0` | 自适应射线网格。8=fast 12=balanced 0=off |
| `preciseRays` | `true` | 使用与原版一致的浮点累加射线追踪。关闭后使用更快的整数 delta 路径，与原版行为有微小偏差 |

## 破坏原版行为的选项

**默认配置（`enabled=true, sampling=accurate, raylookup=false, adaptiveRays=0, preciseRays=true`）完全保持原版行为。** 以下选项会改变行为：

### sampling ≠ accurate

原版对每个受爆炸影响的实体做 ~45 次射线采样（`getSeenPercent`）计算可见比例。降低采样数直接减少计算量，但也降低精度。

| 模式 | 采样/实体 | 精度损失 | 影响 |
|------|----------|---------|------|
| `accurate`（默认） | ~45 | 0% | 与原版完全一致 |
| `fast` | ~24 | ~2% | 极端角度下击退力偏差 0.1-0.3 ❤，绝大多数场景不可察觉 |
| `aggressive` | ~12 | ~5% | 远距离部分遮挡时有概率偏差 0.5 ❤ |

**本质**：将 `getSeenPercent` 的步长公式 `1/(size×f+1)` 中的 `f` 从 `2.0` 降低。降低 `f` 意味着实体表面采样点更稀疏，极少数恰好被方块边缘遮挡的采样点可能被跳过或新增。

### raylookup = true

用爆炸射线追踪（1352 条）预计算的方向→距离表替代 `getSeenPercent` 的逐 cell DDA 遍历。

**原理**：爆炸阶段已经追踪了 1352 条从中心向外的射线，记录了每个方向第一个非空气方块的距离。实体可见性检查不再做 DDA 细胞遍历，而是直接查找对应方向爆炸射线的命中距离进行比对。

**偏差来源**：1352 条射线覆盖球面，平均角分辨率约 5.5°。采样方向落在两条射线之间时，取最近射线——可能偏差 0.5-1 格。

| 场景 | 偏差 |
|------|------|
| 开阔地 / 完全遮挡 | 0%（零偏差） |
| 距离 < 7m 部分遮挡 | < 5% |
| 距离 > 10m 复杂栅栏/缝隙 | 可达 10-15% |

**建议**：需要极致性能时配合 `sampling=fast` 使用。

### adaptiveRays > 0

降低爆炸射线网格分辨率，减少总射线数。爆炸形状精度下降但性能提升。`adaptiveRays=8` 时射线数从 1352 降至 ~296。

## 技术架构

```
explode() 主线程
├── Phase 1: 并行射线追踪 (ThreadPoolExecutor)
│   ├── 1352 条射线 × ~30 步 = ~40K block 查询
│   ├── Worker 线程直写 dense boolean grid（无锁、无 merge 开销）
│   ├── preciseRays=true: 从爆炸中心浮点累加射线步进，与原版完全一致
│   ├── preciseRays=false: 方向网格预缓存 delta，整数加法替代浮点累加
│   └── ChunkSafeAccessor 绕过 ServerChunkCache 主线程分发（避免死锁）
│
├── Phase 2: 并行实体伤害 (ThreadPoolExecutor, 可选 raylookup 加速)
│   ├── Worker 线程计算方向/距离/抗性
│   ├── getSeenPercent: 并行安全版 DDA（ChunkSafeAccessor，与原版 Fluid.NONE 行为一致）
│   └── 主线程 join → 应用伤害/击退
│
├── Phase 3: 主线程
│   ├── 方块交互（掉落物）
│   └── 火焰生成
```

## 构建

```bash
./gradlew build
```

产物位于 `build/libs/explosion-{version}.jar`。

需求：JDK 25, Minecraft 26.1.2 (1.21.5), Fabric Loader >= 0.19.2。
