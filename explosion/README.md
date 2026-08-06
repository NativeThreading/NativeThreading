# Explosion Parallelization

[![Release](https://img.shields.io/github/v/release/uright008/explosion)](https://github.com/uright008/explosion/releases)
[![Pre-release](https://github.com/uright008/explosion/actions/workflows/pre-release.yml/badge.svg)](https://github.com/uright008/explosion/actions/workflows/pre-release.yml)

将 Minecraft 爆炸计算从单线程改为多线程并行,大幅提升有大量爆炸(TNT 链式反应、凋灵轰炸等)时的服务端 TPS。

**纯服务端模组**,客户端无需安装。

## 性能

测试方法:命令方块持续 fill 一个 5 边长的 TNT 正方体,点燃其中一个,spark 记录一分钟。

| 配置 | spark 报告 | MSPT 95%ile |
|------|-----------|-------------|
| 原版 | [link](https://spark.lucko.me/4678Vn3HEY) | ~277        |
| 并行 | [link](https://spark.lucko.me/TN72XtEWPH) | ~74.5         |
| 锂 | [link](https://spark.lucko.me/PdEOdDtmz7) | ~130        |
| 并行+锂 | [link](https://spark.lucko.me/r2exD1Slbc) | ~37.8       |

建议同时使用 [Lithium](https://modrinth.com/mod/lithium) ——爆炸并行优化爆炸计算,锂优化实体移动时碰撞计算,互补。

## 指令

所有指令需要 OP 权限(Level 2 / Gamemaster)。

```
/parallel explosion                查看当前状态
/parallel explosion <true|false>   开关并行爆炸(默认开)
/parallel explosion reload         从 config/nt.json 重载配置
```

爆炸行为与原版一致:1352 条射线、采样 2.0(vanilla 精度)、精确浮点射线追踪、基于 flat-view 的实体可见度计算。模组只并行化计算,不改变爆炸结果。

## 配置文件

`config/nt.json` 的 `explosion` 节:

```json
{
  "enabled": true
}
```

| 字段 | 默认 | 说明 |
|------|------|------|
| `enabled` | `true` | 并行爆炸总开关 |

## 技术架构

```
explode() 主线程
├── Phase 1: 并行射线追踪 (ForkJoinPool)
│   ├── 1352 条射线 × ~30 步 = ~40K block 查询
│   ├── Worker 线程直写 dense boolean grid(无锁、无 merge 开销)
│   ├── 主线程按 vanilla 顺序预生成射线功率(level.random,序列与原版一致)
│   └── ChunkSafeAccessor 绕过 ServerChunkCache 主线程分发(避免死锁)
│
├── Phase 2: 并行实体伤害 (ForkJoinPool)
│   ├── 主线程 level.getEntities(AABB) 空间查询 + 快照捕获
│   ├── Worker 线程计算方向/距离/抗性
│   ├── getSeenPercent: 并行安全版 flat-DDA(与原版 f=2.0 采样一致)
│   └── 主线程 join → 应用伤害/击退
│
└── Phase 3: 主线程
    ├── 方块交互(掉落物)
    └── 火焰生成
```

## 构建

```bash
./gradlew build
```

产物位于 `build/libs/explosion-{version}.jar`。

需求:JDK 25, Minecraft 26.2 (1.21.5), Fabric Loader >= 0.19.2。
