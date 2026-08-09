---
name: native-threading-architecture
description: "Hard architecture rules for the NativeThreading Minecraft mod codebase. Use whenever working on this repo — writing or reviewing mixins, touching cache/reuse code, designing threaded work, consuming RNG, adding config, deciding what to delete, or running benchmarks. Triggers: 'mixin', 'threading boundary', 'worker', 'capture', 'static cache', 'RNG', 'fallback', 'short-circuit', 'profile', '架构纪律', '重构爆炸', '优化'. Each rule came from a real incident or a measured rejection — violating one means the change does not land."
---

# NativeThreading 架构纪律

> 硬性规则,不是建议。每条来自一次真实事故或一次实测否决。违反 = 评审不通过。
> 人类可读权威文本:`docs/architecture-discipline.md`(仓库内,与此保持同步)。
> 构建期自动校验:`./gradlew validateMixinDiscipline`(挂在 check,改 mixin 配置/类后跑)。

## 1. Mixin 纪律

**M1 — Mixin 只是注入点。** mixin 类只允许:注入注解(`@Inject`/`@Redirect`/`@Modify*`/`@Overwrite`)、`@Shadow`、`@Accessor`/`@Invoker`、承载注入所需状态的 `@Unique` 字段。管线逻辑(循环/计算/策略)必须住在实现类(`ep.*`/`pc.*`),mixin 只做接线。
*事故*:ServerExplosionMixin 膨胀到 626 行,逻辑无法单测、无法复用。

**M2 — 单个 mixin 类 ≤ 250 行**(含注释注解)。超限只能抽实现类。校验任务已对超限报 warning。

**M3 — 命名**:`*Mixin` / `*Accessor` / `*Invoker`;`@Unique` 成员带模块前缀(`explosion$`/`parallelCore$`);内部类 mixin 用 `$` 连接。

**M4 — mixin 配置是严格 JSON**(无尾逗号),`mixins` 列出的类必须真实存在。校验任务自动查。

## 2. 静态复用与缓存纪律

**C1 — 跨爆炸/跨 tick 的复用必须 `static`。** 宿主类(`ServerExplosion`/`ServerLevel`)是一次性对象(每次爆炸 new),实例字段缓存必然失效。
*事故*:capture 列表做实例字段 → 每次从 512 扩到 4096,772ms/profile。

**C2 — 绑定世界的 static 缓存,key 必须含 `ServerLevel` 身份。** 跨维度复用 = 读到另一个世界的方块。
*事故*:ChunkGrid 缓存缺 level 维度。

**C3 — 复用数组每次全量重写或显式清空;安全论证(谁写/谁读/何时 join/为何无竞争)写进注释。**

**C4 — 缓存只在主线程写,worker 只读不可变快照。** 任何让 worker 写共享结构的"优化"直接否决。

## 3. 线程边界纪律(AGENTS.md 强化版)

**T1 — 三段式固定:捕获(主)→ 纯算(worker)→ 应用(主)。** 不引入第四段。
**T2 — worker 只收不可变值。** 禁传 `Level`、容器、实体、块实体、mod 回调。
**T3 — worker 失败 = 顺序降级,禁止让 vanilla 重跑已做一半的工作。**
*事故*:fallback 重算 → RNG 前进 2724 步,后续随机消费全偏移。

## 4. RNG 纪律

**R1 — 随机消费与 vanilla 逐次一致(次数/顺序/算法)。** 不 `fork()` 除非 vanilla 也 fork(母源步进不同 = 序列不同)。
**R2 — 同一份 RNG 消费绝不放两遍。** 并行画过的随机数,降级路径必须复用。

## 5. 原版一致性纪律

**V1 — 默认与原版逐位一致。** 行为偏差必须:文档化 + 默认关闭 + 有开关。
**V2 — 数值路径必须有 differential/fuzz 测试。** 没有测试的数值优化不提交。

## 6. 配置纪律

**F1 — 只做模块级 on/off。** 一条 vanilla-exact 路径比三个开关更可维护。
**F2 — 配置读取一律走 config 类,禁止字符串直读。**

## 7. 无价值代码纪律("感觉没什么用"的规则化)

**D1 — 无消费者的数据 = 删除。** 新代码必须能说出消费者。
**D2 — 新 mixin/新类必须过两个问题:它在 profile 的哪个热路径?(拿不出 profile 依据的优化不写)它有没有测试?(没有测试的代码是负债)**
**D3 — 遗留代码:静态 grep 无消费者(排除 mixin 注册)→ 删除候选;只在测试里被引用的生产类 → 查死代码。** 当前候选:`TickBlockEntitiesNullGuardMixin`(仅 mixin 配置注册,需运行时验证)。

## 8. 基准纪律

**B1 — 轮间固定 warmup、同 commit 对照、重启后冷态定数。** 改基准脚本 = 重测,不是优化。
**B2 — 收益以 profile self-time 为准,不以分配字节为准。** 短命对象(TLAB)不拖 tick;分配大但 GC 0.1% = 不优化。
*事故*:批量 AABB 每次爆炸现场提取数据,SuperWord 理论收益被提取成本吃光。

## 9. 提交前逐条过

- [ ] 每个 mixin ≤ 250 行,只做注入,逻辑在实现类
- [ ] 所有 static 缓存:安全论证注释、跨维度 key、主线程写
- [ ] 没有"让 vanilla 重跑已消费 RNG 工作"的 fallback
- [ ] 新数值路径有 differential 测试
- [ ] 配置只多不少是模块级 on/off
- [ ] 每处新代码能说出 profile 依据 + 消费者
- [ ] benchmark 同 commit 对照,轮间固定 warmup
