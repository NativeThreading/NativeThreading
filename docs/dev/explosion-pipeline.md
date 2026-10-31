# 爆炸并行管线

> 代码在 `explosion/common/src/main/java/com/github/uright008/ep/`。
> 行为规则见 [`architecture-discipline.md`](architecture-discipline.md),文件角色与模块归属见
> [`code-organization.md`](code-organization.md)。本文描述当前实现;旧方案(已被替代)只存在于本地归档。

## 1. 入口

`ServerExplosionMixin` 是唯一注入点(mixin 只接线,逻辑全在 `ep.*`):

| 注入点 | 作用 |
|---|---|
| `calculateExplodedPositions` HEAD(cancellable) | 方块段:画 RNG → 建 flat view → worker 追踪射线 |
| `hurtEntities` HEAD(cancellable) | 实体段:捕获 → worker 计算 → 主线程应用 |

- `ExplosionContext`(record)在主线程把 mixin 的 shadow 字段打包成一次爆炸的不可变输入;
  BlockStage / EntityStage 都是它的纯函数。
- `ExplosionParallelEligibility` 分三档:Tier A(`ExplosionDamageCalculator`)、
  Tier B(`EntityBasedExplosionDamageCalculator`)允许并行;Tier C(mod 自定义计算器)与
  配置关闭时直接走原版。
- 方块段在触碰世界**之前**调用 `ExplosionVisibilitySnapshot.begin(...)`,记录当前尚未加载的
  区块;捕获阶段据此跳过这些区块里的实体,与原版"看不到自己这一发才加载的区块"一致。

## 2. 三段式

### 2.1 捕获(主线程)

- `ExplosionChunkGridCache` 按 flat view 真正读取的 section 矩形提供 `ChunkGrid`,static 复用,
  缓存 key 同时包含 `ServerLevel` 身份与矩形;矩形不同则重建。
- 实体捕获在主线程完成:一次空间查询遍历,直接产出快照数组(`SnapshotBatch` + `Entity[]`
  refs,均为 static 复用),顺序与原版 `hurtEntities` 相同;`ignoreExplosion` / spectator /
  距离过滤都在这一遍里按原版语义完成。
- 需要逐实体上下文的方块(scaffolding、powder snow)会让 flat view 标记
  `hasEntityContextBlocks`,这些实体在捕获时改用原版逐实体曝光计算。

### 2.2 计算(worker,纯值)

- **方块段** `ExplosionBlockStage.compute`:先在主线程按原版顺序从 `level.random` 预生成全部
  1352 条射线的功率(复用 `RAY_POWERS_CACHE`),再在 worker 上追踪射线写 dense BitSet;
  worker 失败就用同一份已画数据在主线程串行重追踪——不重跑 vanilla,不重画 RNG。最后把
  BitSet 折叠成 `List<BlockPos>`,并把 `WorldReadViewImpl`(只读方块/碰撞快照)显式返回给实体段。
- **实体段** `ExplosionEntityStage.apply`:worker 上算伤害、击退与曝光度
  (`ExplosionEntityDamageComputer` 用原始数组快照;`ExplosionExposureComputer` 对 flat view
  做与原版 `getSeenPercent` 相同的采样)。worker 不碰 RNG、不碰活对象。
- 可选宽相索引 `AabbIndex`(默认关):per-section SoA AABB 索引替代逐实体
  `getBoundingBox()` 扫描,顺序约束由 `EntitySectionIndexMixin` / `EntityBoundingBoxIndexMixin` /
  `ServerLevelIndexMixin` 维护;校验模式 `-Dnt.aabbIndex.verify=true` 逐实体对照 vanilla 查询。

### 2.3 应用(主线程)

- `ExplosionEntityApplication` 把 worker 结果写回实体:伤害、击退、`hitPlayers`。
  所有世界/实体修改都只发生在这一层。
- 实体段 worker 失败时用捕获好的快照在主线程串行计算,不重跑 vanilla;只有**捕获阶段**失败
  (没有可用快照)才返回 false,由 mixin 放行原版 `hurtEntities`。捕获/计算不消费 RNG,
  因此这条降级不影响随机序列。
- 短路:当 `interactsWithBlocks=false` 且 `fire=false` 时,方块列表没有消费者。此时方块段只把
  世界 RNG 推进到"原版画完 1352 次 `nextFloat()`"的状态,返回空列表,实体段照常走原版
  `hurtEntities`(其逐实体曝光是原版语义)。

## 3. 不变量

- **RNG**:唯一消费方是主线程;1352 次 `nextFloat()` 与原版同序;worker 零 RNG 访问。
- **顺序**:实体捕获顺序 = 原版 `hurtEntities` 顺序,应用顺序与之相同。
- **降级**(T3):已画/已捕获的数据必须复用;禁止让 vanilla 重跑已消费 RNG 或已做一半的工作。
- **世界读取**:worker 只能拿到 `WorldReadView`(自带快照),拿不到 `Level` / `ChunkAccess` /
  实体 / 块实体 / mod 回调。

## 4. 配置与命令

`config/nt.json` → `explosion`:

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 并行爆炸总开关 |
| `aabbIndex` | false | 实体宽相 AABB 索引(实验,默认关) |

```
/parallel explosion                当前状态
/parallel explosion <true|false>   开关
/parallel explosion reload         从 config/nt.json 重载
/parallel explosion stats          ChunkGrid 缓存命中率与两段的路径/task 计数
```

## 5. 测试

- `:explosion:test`:`ExplosionRayFlatDifferentialTest`(射线/曝光的逐位差分)、
  `ExplosionRayBoundsTest`、`ExplosionFlatViewHelperTest`、
  `ExplosionEntityDamageComputationTest`、`ExplosionEntityApplicationTest`、
  `ExplosionKnockbackTest`、`ExplosionParallelEligibilityTest`、`WorldReadViewTest`、
  `ExplosionCapturedBlockGetterTest`、`ExplosionBlockStageEntityCallbackTest`。
- `:fabric:runGameTest`:端到端服务端 gametest。
- `-Dnt.aabbIndex.verify=true`:索引开启时逐实体对照 vanilla 空间查询(等价门)。

## 6. 相关文档

- 硬规则与提交前清单:[`architecture-discipline.md`](architecture-discipline.md)
- 文件角色、模块归属、线程安全层:[`code-organization.md`](code-organization.md)
- 测量工具与协议:[`../../benchmarks/README.md`](../../benchmarks/README.md)
- 历史方案与教训:本地归档 `docs/archive/`(不进仓库)
