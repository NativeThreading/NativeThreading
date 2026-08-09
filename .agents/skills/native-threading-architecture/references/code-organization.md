# NativeThreading 代码组织规范

> 引入新功能时按此结构落地。与 `docs/architecture-discipline.md`(行为纪律)配套:
> 行为纪律管"**不能做什么**"(mixin 只做注入、缓存必须 static、RNG 不放两遍……),
> 本文档管"**应该长什么样**"(文件怎么拆、模块怎么分、线程安全层怎么写)。
> 两份一起构成"新功能落地规范"。Agent 侧版本:`.agents/skills/native-threading-architecture/`。

## 1. 总原则:按线程域与状态所有权组织

一个文件/一个模块的第一问题不是"它属于哪个 MC 功能",而是:

1. **它在哪个线程跑?**(主线程捕获 / worker 纯算 / 主线程应用)
2. **它拥有什么状态?**(无状态纯函数 / 静态复用缓存 / 一次性输入快照)

按这两个维度组织,而不是按"类名好听"组织。这来自 ServerExplosionMixin 的拆分实践:
同一个爆炸逻辑被拆成 Context(输入快照)/ BlockStage(worker 计算 + 静态缓存)/
EntityStage(主线程捕获与应用)/ ChunkGridCache(缓存持有者),每个文件一个清晰的
线程域与状态角色,可独立单测、可独立 review、边界可见。

## 2. 文件拆分

### 2.1 文件角色清单

项目内已确立的角色,新功能只允许用这些角色建文件:

| 角色 | 后缀/形态 | 线程域 | 状态 | 示例 |
|---|---|---|---|---|
| 注入点 | `*Mixin` | 主线程(被注入处) | 只留 `@Shadow` + 接线 | `ServerExplosionMixin`(130 行薄壳) |
| 输入快照 | `*Context`(record) | 跨阶段传递 | 不可变 | `ExplosionContext` |
| 管线段 | `*Stage` | 主线程或 worker,单一 | 静态缓存(若有)+ 计算 | `ExplosionBlockStage` / `ExplosionEntityStage` |
| 缓存持有者 | `*Cache` | 主线程写,worker 只读 | 静态复用 | `ExplosionChunkGridCache` |
| 纯函数 | `*Helper` | 任意 | 无 | `ExplosionHelper` / `ExplosionFlatViewBuilder` |
| 配置 | `*Config` | 主线程 | 配置项 | `ExplosionParallelConfig` |
| mixin 访问器 | `*Accessor` / `*Invoker` | — | 无 | `ServerLevelEntityAccessor` |

### 2.2 拆分规则

- **F1 — 一个文件 = 一个线程域 + 一个职责。** 若一段代码"在另一个线程跑"或
  "属于另一阶段",就拆出去。不允许一个文件混"主线程捕获"与"worker 计算"。
- **F2 — 状态所有权单一。** 拥有可变静态状态的文件(缓存持有者)不得兼做纯计算;
  无状态文件不得私藏可变状态。缓存与使用它的计算可以同文件(worker 要访问
  `WORKER_POS`/`FLAT_BLOCKS_CACHE`,内聚比分开好)——但要能说清"谁是写者"。
- **F3 — Context 是唯一跨线程域传递的输入形态。** 两个 Stage 之间、注入点与
  Stage 之间,一律通过 record 传递,不用隐式共享字段(隐式共享 = 时序隐含约定)。
- **F4 — mixin 遵循 M1/M2**(注入点 ≤250 行,逻辑在实现类)。

### 2.3 大小上限

- mixin 类:**250 行硬限**(构建期校验,超限 build 失败)
- 普通类(Stage/Helper/Cache):**300 行建议上限**;超限先检查是否混了多个
  线程域或职责,而不是"再塞一个方法"

### 2.4 新功能文件模板

引入一个新的并行化子系统时,默认建这组文件:

```
<feature>/common/src/main/java/<pkg>/
├── <Feature>Context.java      # 输入快照(不可变 record)
├── <Feature>CaptureStage.java # 主线程:读世界 → 产快照
├── <Feature>ComputeStage.java # worker:快照 → 纯结果(可无,若并入其他段)
├── <Feature>ApplyStage.java   # 主线程:结果 → 写世界
├── <Feature>Cache.java        # 静态复用持有者(若有)
├── <Feature>Helper.java       # 纯函数工具(若有)
└── mixin/<Feature>Mixin.java  # 注入点薄壳
```

## 3. 模块划分

### 3.1 三层结构

```
core/      执行原语层: worker/池/阶段标记/配置框架/命令框架/通用 chunk 读取
           不拥有任何特性管线(AGENTS.md)。
explosion/ workload 层: 一个特性一条完整管线(捕获→纯算→应用)。
fabric/    聚合 loader: 打包 workload 模块。
neoforge/
```

### 3.2 归属判断表

新代码落在哪一层,按这个表决策:

| 它是什么 | 放哪 |
|---|---|
| 通用并行执行原语(`ParallelWorker`/池/`SafeLevelAccess`) | core |
| 配置/命令框架、配置读写 | core |
| 通用世界读取视图(不绑定特性的 chunk 访问) | core |
| 某特性的管线逻辑(捕获/计算/应用) | 该特性的 workload 模块 |
| 某特性专属的视图/计算(flat view、曝光度) | 该特性的 workload 模块 |
| 平台入口、mixins.json、mod metadata | 模块内的 fabric/neoforge 壳 |

**边界案例——单消费者的公共设施**(如 `ChunkGrid` 目前只有 explosion 用):
可留在 core(作为执行原语的延伸),但必须满足:不依赖 workload 类型、API 是
通用的(不出现爆炸术语)。一旦 API 出现特性词汇,迁回 workload 模块。

### 3.3 依赖规则

- `workload → core` 单向;workload 之间**零依赖**;core **不反向依赖** workload。
- 平台壳只依赖自己的 common;common 不 import 平台类。
- 禁止:两个 workload 共享实现(要共享就下沉到 core 的通用原语)。

## 4. 线程安全层

这是本项目的核心差异化资产,引入新功能时**必须**按此模式设计数据交接。

### 4.1 三层线程模型

```
主线程捕获 ──(不可变快照)──> worker 纯算 ──(纯结果)──> 主线程应用
   read world               read snapshot only          write world
```

- 捕获:主线程读世界状态,提取成快照(Context/数组/record)。
- 计算:worker 只读快照,产出纯结果,不碰世界、不碰 RNG、不碰实体。
- 应用:主线程等 worker join 后,把结果写回世界。

### 4.2 跨线程数据协议

**worker 段只能收到这几类:**
- 不可变值(record / 基本类型 / 捕获时填好的数组,如 `rayPowers`)
- 只读视图(如 `WorldReadViewImpl`:持有引用但只读,无写路径)

**禁止传给 worker:**
- 活容器(`Level`/容器/区块)、实体、块实体、mod 回调
- RNG(非线程安全,且顺序敏感)
- 任何"worker 要写"的共享结构

**Context 的例外规则:** `Context` record 允许放主线程段要用的活引用
(如 `level`——capture 段要 `getEntities`/`getRandom`),但 worker 段
能碰的必须是纯值/只读视图。写 Context 时注释标明"哪段用这个字段"。

### 4.3 Context 模式

- 一次操作的**所有**输入打包成一个不可变 record。
- Context 不承载输出;输出由各 Stage 的返回值显式传递
  (如 `ExplosionBlockStage.Result(blocks, worldView)`——把隐式共享的
  世界视图变成显式返回,消除"实体段依赖射线段先跑"的隐含时序)。
- Context 改名/增删字段是纯重构,不涉及线程语义——这就是它该厚的原因。

### 4.4 静态缓存线程模型

跨爆炸/tick 的复用(已验证的模式):

```
主线程: getAndSet(null) 取走 → 填满 → worker 只读 → join → set(回存)
```

四问论证(每个 static 缓存必须能回答):
1. 谁写?——主线程
2. 谁读?——worker(只读)或主线程
3. 何时 join?——回存前 worker 已全部结束
4. 为何无竞争?——一次爆炸内串行 + worker 只读

规则:复用必须 static(C1)、绑定世界必须 level-key(C2)、复用数组每次全量重写(C3)。

### 4.5 交接与同步

- worker 阶段用 `CountDownLatch` 计数;join 是 happens-before 边,worker
  写结果(如 `BitSet`)对主线程可见。
- 结果若本 tick 要用:主线程在阶段边界**阻塞等待**(`mapEach`/`mapBatched`
  已封装)。结果不用本 tick:才允许异步(当前无此场景)。
- worker 失败:顺序降级(T3),用已捕获/已画的数据,不重跑 vanilla。

### 4.6 安全层边界(不是什么)

`SafeLevelAccess` 只是**阶段标记**(深度计数器),不是同步原语、不是访问许可。
真正的安全来自 4.1 的三层模型本身:worker 根本没拿到活引用,谈不上"访问"。
不要试图给 SafeLevelAccess 加"校验能力"——那既做不到也违背设计。

## 5. 新功能落地检查清单

引入新功能(新的并行化子系统)时逐条过:

- [ ] 按 2.4 模板建文件(Context/Stage/Cache/Helper/Mixin)
- [ ] 每个文件回答:哪个线程域?拥有什么状态?(F1/F2)
- [ ] 跨线程传递只走 Context/只读视图,无隐式共享字段(F3)
- [ ] mixin ≤250 行,只做注入点(M1/M2)
- [ ] 模块归属过了 3.2 判断表,依赖方向单向(3.3)
- [ ] worker 只收不可变值/只读视图,无活容器/实体/RNG/回调(4.2)
- [ ] 每个 static 缓存过四问(4.4),绑定世界的带 level-key
- [ ] RNG 与 vanilla 逐次一致,不放两遍(R1/R2)
- [ ] 数值路径有 differential 测试(V2)
- [ ] `./gradlew validateMixinDiscipline` 通过
