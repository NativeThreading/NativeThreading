# 爆炸并行管线

> 代码在 `explosion/common/src/main/java/com/github/uright008/nt/explosion/`。
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
- `ExplosionEligibility` 分三档:Tier A(`ExplosionDamageCalculator`)、
  Tier B(`EntityBasedExplosionDamageCalculator`)允许并行;Tier C(mod 自定义计算器)与
  配置关闭时直接走原版。
- 方块段在触碰世界**之前**调用 `ExplosionVisibilitySnapshot.begin(...)`,记录当前尚未加载的
  区块;捕获阶段据此跳过这些区块里的实体,与原版"看不到自己这一发才加载的区块"一致。

## 2. 三段式

### 2.1 捕获(主线程)

- `ExplosionChunkGridCache` 按 flat view 真正读取的 section 矩形提供 `ChunkGrid`,static 复用,
  缓存 key 同时包含 `ServerLevel` 身份与矩形;矩形不同则重建。命中还校验 grid 捕获的每个
  `LevelChunk` 仍是 level 当前对象(C5):卸载/重载会替换对象,失效则重建,避免命中旧对象
  读到卸载前的方块状态。
- flat view 的碰撞几何在主线程用**活世界 + 真实格点坐标**解析:
  `state.getCollisionShape(level, pos)`(2-arg,等价空 `CollisionContext`)。只有
  `!block.hasDynamicShape()` 的状态其形状是 BlockState 的纯函数(原版烘焙 cache),
  按 BlockState 身份做 per-state 盒缓存;`dynamicShape` 状态(moving piston、shulker box、
  bamboo、pointed dripstone、sulfur spike 等)的形状依赖块实体/坐标,逐格解析且**永不进缓存**。
  worker 只接收 `double[][]` 盒表,不接收 `Level` / `BlockState`。传 null level/pos 是历史事故根因:
  方块实现直接解引用(moving piston → `level.getBlockEntity`)。
- 实体捕获在主线程完成:一次空间查询遍历,直接产出快照数组(`SnapshotBatch` + `Entity[]`
  refs,均为 static 复用),顺序与原版 `hurtEntities` 相同;`ignoreExplosion` / spectator /
  距离过滤都在这一遍里按原版语义完成。
- flat view 的碰撞形状用空 `CollisionContext` 解析;26.3 里只有 scaffolding、powder snow
  与 liquid 三类方块会按查询实体改变碰撞形状。填充 flat view 时内联分类成
  `EntityContextFlags(stateDependent, hasLiquid, hasPowderSnow)`(O(1) 交给实体段,
  不再逐发爆炸扫描整个 view):
  - `stateDependent`(scaffolding / modded LiquidBlock 子类):整发爆炸的所有实体都走原版
    逐实体曝光(主线程真实实体上下文)。scaffolding 的 flat-view 形状是 `SHAPE_STABLE`
    (空上下文的 `isAbove(..., true)` 返回默认 `true`),而真实实体多数得到空形状,
    两者对几乎所有实体都不一致,所以无法逐实体放行;modded liquid 子类形状覆盖未知,
    同样保守处理。
  - `hasLiquid`(vanilla LiquidBlock):只有 `LivingEntity` 走原版逐实体曝光;非 LivingEntity
    (TNT/物品/箭/矿车)的液体碰撞形状恒为空,与 flat view 的空上下文形状逐位一致,继续走 worker
    的 flat-view 曝光。Nether 熔岩场景因此不再把整发爆炸的实体曝光拖回主线程。
  - `hasPowderSnow`(PowderSnowBlock):细雪的 flat-view 形状恒为空(空上下文的
    `isAbove(..., false)` 返回默认 `false`);活实体上下文只有在①`fallDistance > 2.5`
    ②实体是 `FallingBlockEntity` ③实体能站上细雪(`POWDER_SNOW_WALKABLE_MOBS` tag
    或脚穿皮革靴)时才非空。实体段用 `powderSnowMayChangeShape(entity)` 逐实体判定:
    三条都不满足时活形状恒等于 flat view 的空形状,flat 曝光逐位一致,照常下放 worker;
    满足者(下落中的实体、落沙/落方块、穿皮革靴的活体)才走主线程 live 曝光。
    含细雪的雪原爆炸因此不再整发退化为串行。
  - plain liquid 的识别是 `block.getClass() == LiquidBlock.class`(26.3 的水/岩浆都是该类实例);
    modded 子类保守归入 `stateDependent`。
- **非生存实体的曝光去重(P2)**: 对非 `LivingEntity`、非落方块、非实验性矿车、
  `fallDistance <= 2.5` 且不满足 `canEntityWalkOnPowderSnow` 的实体,活实体上下文在
  26.3 只会通过三类方块影响形状——液体(要求 LivingEntity)、细雪(要求上述条件)、
  scaffolding(只读实体脚部 Y 与 `isDescending`);这三者对它们都只依赖
  `(AABB, feetY, isDescending)`。因此 `getSeenPercent` 对相同 key 的实体逐位相同,
  每发爆炸按 key 复用一次 live 采样(上限 8 组,见 `EXPOSURE_DEDUP_CAPACITY`)。
  TNT 单点堆积/物品堆这类大量同位置受害者的场景,曝光调用从 O(n)/发降到 O(1)/发。
  同时 `PrimedTnt#hurtServer` 是 `final` 且恒返回 false(无副作用),
  在 worker 路径允许的 Tier A/B 伤害计算器实体钩子继承自默认纯实现的前提下,
  纯 TNT 受害者的整段伤害计算/调用被跳过(仅保留可观察的击退);
  物品/落方块/矿车等有真实伤害副作用的非生存实体不适用。
- **分组捕获(P2-C)**: 精确 `PrimedTnt` 类、伤害已省略、live 路径且 exposure 已采样的实体
  若捕获值 `(feetX, feetY, feetZ, eyeY, exposure)` 完全相同,则共享一个计算单元
  (一个 snapshot / 一个 worker 任务 / 一个 result);组表上限 16,超出即回落为独立单元。
  捕获仍按 vanilla `getEntities` 顺序把**每个受害者**记进 refs 与 `unitOfVictim`,
  所以实体枚举顺序和可见性不变,只是计算去重。分组等价性依赖:精确类(无 onExplosionHit
  覆写)、伤害省略(§2.1 上一条)、live 预置曝光、以及 5 个捕获值全等 ⇒
  `computeEntityDamage` 输出逐位相同。子类/物品/落方块等全部仍走逐实体单元。

### 2.2 计算(worker,纯值)

- **方块段** `ExplosionBlockStage.compute`:先在主线程按原版顺序从 `level.random` 预生成全部
  1352 条射线的功率(复用 `RAY_POWERS_CACHE`),再在 worker 上追踪射线写 dense BitSet;
  worker 的 DDA 只读捕获好的盒表(视图外格子按 AIR,与 flat view 越界语义一致),不调用任何
  方块/level API;worker 失败就用同一份已画数据在主线程串行重追踪——不重跑 vanilla,不重画 RNG。
  最后把 BitSet 折叠成 `List<BlockPos>`,并把 `WorldReadViewImpl`(只读方块/碰撞快照)显式返回给实体段。
- **实体段** `ExplosionEntityStage.apply`:worker 上算伤害、击退与曝光度
  (`ExplosionEntityDamageComputer` 用原始数组快照;`ExplosionExposureComputer` 对 flat view
  做与原版 `getSeenPercent` 相同的采样)。worker 不碰 RNG、不碰活对象。
- **空域曝光快速判定(P2)**: 采样前先看"实体 AABB + 空上下文采样偏移 + 爆心"的保守包围盒,
  若其中 flat 盒表全空,则 27 条采样必然全部 miss,直接返回 `1.0F`(原版 hits/count 恒等于
  1.0F,逐位一致);否则回退逐射线采样。包围盒访问格数上限 128 格,超过(大实体贴近爆炸盒
  边缘)直接走精确射线,避免快速判定本身比 27 条射线更慢。worker 侧常数优化,不改变结果。
- 可选宽相索引 `AabbIndex`(默认关):per-section SoA AABB 索引替代逐实体
  `getBoundingBox()` 扫描,顺序约束由 `EntitySectionIndexMixin` / `EntityBoundingBoxIndexMixin` /
  `ServerLevelIndexMixin` 维护;校验模式 `-Dnt.aabbIndex.verify=true` 逐实体对照 vanilla 查询。

### 2.3 应用(主线程)

- `ExplosionEntityApplication` 把 worker 结果写回实体:伤害、击退、`hitPlayers`。
  所有世界/实体修改都只发生在这一层。
- 分组单元(§2.1)的 apply 只做**逐受害者的有序 push**:按捕获顺序遍历 refs、每个实体
  `push(kbX,kbY,kbZ)` 恰好一次(`push(double,double,double)` 与 vanilla
  `pushFromExplosion(Vec3)` 的 finite 检查/写入语义相同,但省掉 Vec3 分配);
  hurt/bookkeep/onExplosionHit 对精确 `PrimedTnt` 受害者都是已证明的 vanilla no-op,
  因此不再调用。push 的调用顺序和次数与 vanilla hurtEntities 完全一致;
  非组单元继续走 `ExplosionEntityApplication`(伤害/击退/记账/回调)。
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
/nt explosion                    当前状态
/nt explosion <true|false>       开关
/nt explosion reload             从 config/nt.json 重载
/nt explosion stats              运行期计数器/阶段耗时/ChunkGrid 命中率
/nt explosion stats reset        清零计数器(保留缓存的 grid)
```

`stats` 输出(每次 reset 后重新累计)：

- `blasts`: 方块段/实体段各处理多少发,两条线的串行降级次数;
- `victims`: 实体受害者总数,live / flat 两条曝光路径各多少,曝光去重命中率,分组命中率;
- `units`: 计算单元数(每发实体段的平均单元数)与方块段每次射线数;
- `exposure`: clear-region 快速判定命中次数,以及去重/分组槽位容量;
- `time`: 方块段 capture/worker、实体段 capture/worker/apply 的平均主线程耗时;
- `chunkgrid`: `ExplosionChunkGridCache` 命中/未命中率;
- `params`: 当前线程池大小、射线数、去重槽位、分组槽位、clear-region 预算。

## 5. 测试

- `:explosion:test`:`ExplosionRayFlatDifferentialTest`(射线/曝光的逐位差分)、
  `ExplosionShapeBoxesLiveTest`(全注册表默认状态 + dynamicShape 逐格解析/缓存不变量)、
  `ExplosionRayBoundsTest`、`ExplosionFlatViewHelperTest`、
  `ExplosionEntityDamageComputationTest`、`ExplosionEntityApplicationTest`、
  `ExplosionKnockbackTest`、`ExplosionEligibilityTest`、`WorldReadViewTest`、
  `ExplosionCapturedBlockGetterTest`、`ExplosionBlockStageEntityCallbackTest`、
  `ExplosionStatsTest`(计数器/耗时格式与 reset)。
- `:fabric:runGameTest`:端到端服务端 gametest;`ExplosionDynamicShapeGameTest` 把
  moving piston / shulker box / bamboo / pointed dripstone / sulfur spike 放进真实的 TNT 爆炸
  包络,并行开关两次都必须跑完(修复前该用例以 `MovingPistonBlock.getBlockEntity` NPE 失败)。
   `ChunkGridCacheReloadGameTest` 覆盖区块卸载/重载后的 ChunkGrid 缓存复用:先证明 cache hit
  能看到同 chunk 对象的活编辑(C/P 阶段),再让 rig 所在 chunk 卸载、重载并重建资源,同矩形爆炸
  必须保持 vanilla 一致性(修复前陈旧 grid 会把半径 8 爆炸炸出黑曜石破坏)。
- `-Dnt.aabbIndex.verify=true`:索引开启时逐实体对照 vanilla 空间查询(等价门)。

## 6. 相关文档

- 硬规则与提交前清单:[`architecture-discipline.md`](architecture-discipline.md)
- 文件角色、模块归属、线程安全层:[`code-organization.md`](code-organization.md)
- 测量工具与协议:[`../../benchmarks/README.md`](../../benchmarks/README.md)
- 历史方案与教训:本地归档 `docs/archive/`(不进仓库)
