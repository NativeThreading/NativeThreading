# NativeThreading 架构纪律

> 本文件是代码库必须遵守的硬性规则,不是建议。每条规则都来自一次真实事故或一次实测否决。
> 违反 = 评审不通过。Agent 与维护者都以本文件为最高代码标准(仅次于 AGENTS.md 的线程边界)。
>
> **同步约定**:agent 侧版本在 `.agents/skills/native-threading-architecture/SKILL.md`。
> 修改纪律时两处必须同步(或改一处后同步另一处),否则 agent 与评审者会看到不一致的规则。

## 1. Mixin 纪律

**M1 — Mixin 只是注入点。**
mixin 类只允许出现:注入注解(`@Inject` / `@Redirect` / `@Modify*` / `@Overwrite`)、`@Shadow`、`@Accessor` / `@Invoker`、以及承载注入所需状态的 `@Unique` 字段。
任何管线逻辑(循环、计算、策略选择)必须住在实现类(`ep.*` / `pc.*`),mixin 只做"接线"。
*事故*:ServerExplosionMixin 膨胀到 626 行,管线逻辑无法单测、无法复用。

**M2 — 单个 mixin 类 ≤ 250 行(含注释与注解)。**
超限的唯一出路是抽实现类,不是删注释。250 行是硬上限,不是目标。

**M3 — 命名与签名约定。**
mixin 类 `*Mixin`,访问器 `*Accessor`,调用器 `*Invoker`。所有 `@Unique` 成员以模块前缀开头(`explosion$` / `parallelCore$`),避免跨 mixin 冲突。内部类 mixin 用 `$` 连接(`FooMixin$InnerMixin`)。

**M4 — mixin 配置是严格 JSON。**
`*.mixins.json` 必须通过严格解析(尾逗号即失败),且 `mixins` 数组列出的每个类必须真实存在。构建期/测试期自动校验。

## 2. 静态复用与缓存纪律

**C1 — 跨爆炸/跨 tick 的复用必须是 `static`。**
`ServerExplosion`、`ServerLevel` 等宿主是**一次性对象**(每次爆炸 new 一个)。实例字段缓存必然失效——每次都是"首次",复用退化为重建。
*事故*:capture 列表做实例字段 → 每次爆炸从 512 扩容到 4096,772ms/profile 白白流失。

**C2 — 绑定世界的 static 缓存,key 必须含 level(身份比较)。**
`ChunkAccess[]` / chunk 网格绑定具体 `ServerLevel`;跨维度复用 = 读到另一个世界的方块。
*事故*:ChunkGrid static 缓存缺 level 维度,跨维度爆炸数据错误。

**C3 — 复用数组必须每次全量重写或显式清空;安全论证写进注释。**
每个缓存的使用点必须能说清:谁写、谁读、何时 join、为何无跨爆炸竞争。说不清的不用缓存。

**C4 — 缓存只在主线程写,worker 只读不可变快照。**
任何让 worker 写共享结构的"优化"直接否决(即使能加速,也不值得时序风险)。

## 3. 线程边界纪律(AGENTS.md 的强化版)

**T1 — 三段式固定:捕获(主)→ 纯算(worker)→ 应用(主)。** 不引入第四段。
**T2 — worker 只收不可变值。** 禁传 `Level`、容器、实体、块实体、mod 回调。
**T3 — worker 失败 = 顺序降级,禁止让 vanilla 重跑已做一半的工作。**
*事故*:fallback 让 vanilla 重算 → RNG 前进 2724 步,后续所有随机消费偏移。

## 4. RNG 纪律

**R1 — 每次随机消费与 vanilla 逐次一致(次数、顺序、算法)。**
不 `fork()` 除非 vanilla 原版也 fork(母源步进不同 = 序列不同)。
**R2 — 同一份 RNG 消费绝不允许跑两遍。**
并行路径画过的随机数,降级路径必须复用,不得重画。

## 5. 原版一致性纪律

**V1 — 默认与原版逐位一致。** 任何行为偏差必须:文档化 + 默认关闭 + 有开关。
**V2 — 数值路径必须有 differential / fuzz 测试守护。**
没有测试的数值优化不允许提交(参考 `ExplosionRayFlatDifferentialTest`、`AabbBatchFilterTest` 模式)。

## 6. 配置纪律

**F1 — 只做模块级 on/off。** 不加逐优化旋钮;一条 vanilla-exact 路径比三个开关更可维护。
**F2 — 配置读取一律走 config 类,禁止字符串直读。** 改名即破坏,直读无处可查。

## 7. 无价值代码纪律("感觉没什么用"的规则化)

**D1 — 无消费者的数据 = 删除。**
先例:SoA 每实体每 tick 写 45 字段、无读者 → 删;hopper/redstone/vectorial 无负载 → 删。
新代码必须能说出消费者;说不出的不进。

**D2 — 新 mixin / 新类必须过两个问题:**
- 它在 profile 的哪个热路径上?拿不出 profile 依据的优化不写。
- 它有没有测试?没有测试的代码是负债。

**D3 — 遗留代码按 D 规则审查:**
- 静态 grep 无消费者(排除 mixin 注册)→ 删除候选
- 只在测试里被引用的生产类 → 检查是否死代码
- 当前候选:`TickBlockEntitiesNullGuardMixin`(仅在 mixin 配置注册,是否仍适用于 MC 26.2 需运行时验证;验证不了就删)

## 8. 基准纪律

**B1 — 轮间固定 warmup、同 commit 对照、重启后冷态定数。**
轮间递减 warmup 会造成"伪方差"(每轮全新 JVM)。改基准脚本 = 重测,不是优化。

**B2 — 收益以 profile self-time 为准,不以分配字节为准。**
短命对象(TLAB)不拖 tick;分配量大但 GC 0.1% = 无性能问题,不优化。
*事故*:批量 AABB 每次爆炸现场提取数据,SuperWord 理论收益被提取成本吃光。

## 9. 评审清单(提交前逐条过)

- [ ] 每个 mixin ≤ 250 行,只做注入,逻辑在实现类
- [ ] 所有 static 缓存:有安全论证注释、跨维度 key、主线程写
- [ ] 没有"让 vanilla 重跑已消费 RNG 工作"的 fallback
- [ ] 新数值路径有 differential 测试
- [ ] 配置只多不少是模块级 on/off
- [ ] 每处新代码能说出 profile 依据 + 消费者
- [ ] benchmark 同 commit 对照,轮间固定 warmup
