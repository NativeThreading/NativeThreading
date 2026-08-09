# NativeThreading 项目结构复盘

> 对比当前结构与三个成熟优化 mod(Lithium / C2ME / FerriteCore)的参考实践,
> 找出差距与可执行的改进方向。参考仓库为 shallow clone,以下均基于源码勘察。

## 1. 当前结构盘点

```
NativeThreading/
├── core/            # 共享基础设施: 线程池/worker/配置/命令/捕获原语
│   ├── common/      # 纯逻辑 (pc.*: ParallelWorker, ChunkGrid, SafeLevelAccess, 配置)
│   │   └── mixin/   # 3 个基础设施 mixin
│   ├── fabric/      # 平台壳
│   └── neoforge/    # 平台壳
├── explosion/       # 爆炸 workload 管线
│   ├── common/      # 管线实现 (ep.*: Helper/FlatView/Config/Command)
│   │   └── mixin/   # 1 个 626 行大 mixin (ServerExplosionMixin)
│   ├── fabric/
│   └── neoforge/
├── fabric/          # 聚合 loader
└── neoforge/        # 聚合 loader
```

已经做对的:
- **common / 平台壳分离** — 与 Lithium/FerriteCore 一致,纯逻辑不依赖 loader API
- **workload 模块自持** — 爆炸逻辑不进 core(AGENTS.md 明确),符合"功能内聚"
- **测试** — differential / fuzz / 回归测试覆盖数值一致性(比多数优化 mod 重视)
- **线程边界纪律** — 捕获→worker 纯算→主线程应用,边界写入 AGENTS.md

## 2. 与参考项目的差距

### 2.1 mixin 厚度(最大差距)

| | 现状 | 参考实践 |
|---|---|---|
| ServerExplosionMixin | **626 行**,承载整条管线 | Lithium 的 mixin 只做"注入点",逻辑全在 `common/` 实现类 |
| 逻辑位置 | 一半在 mixin 类里 | FerriteCore: mixin 薄 + `impl/` 静态类 + `ducks/` 接口三件套 |

**问题**:mixin 类不可单测(需 MC 运行时)、不可复用;626 行里既有注入点又有管线逻辑,review 和测试都难。
**方向**:把管线逻辑抽到 `ep` 包(如 `ExplosionPipeline`),mixin 只留注入 + 组装。

### 2.2 mixin 粒度

| | 现状 | 参考实践 |
|---|---|---|
| 文件数 | explosion 只有 1 个 mixin 类 | Lithium 434 个,按功能域分目录;FerriteCore 一优化一包 |
| mixin 配置 | 每模块 1 个 `*.mixins.json`,全量强制 | Lithium/C2ME: 一功能一 mixin 配置 + 可开关;FerriteCore 一优化一 config plugin |

**问题**:不能按功能开/关 mixin;配置只有模块级 `enabled`。
**方向**:爆炸管线若继续扩展,拆成射线/实体/短路等子 mixin 包,各自可开关。

### 2.3 配置系统(与目标规模匹配)

| | 现状 | 参考实践 |
|---|---|---|
| 文件 | `nt.json` + 手写 ConfigStorage 三件套 | Lithium: properties + 层级包路径规则;FerriteCore: 声明式 Option 注册表;C2ME: 单 TOML + ConfigAccessor 声明即定义 |
| 开关粒度 | 模块级 on/off | 参考项目支持逐优化项开关 |

**判断**:NT 现在只暴露模块级 on/off(此前刻意砍掉的),配置**不是当前瓶颈**;若未来要逐项开关,借鉴 FerriteCore 的 Option 注册表(声明式、代码即文档)。

### 2.4 线程/worker 架构(NT 的差异化优势)

| | 参考项目 | NT |
|---|---|---|
| C2ME | 状态机固化"哪个线程能碰什么",主线程只调度 | **捕获→纯算→应用 三段边界**,AGENTS.md 明文 |
| Lithium | 无显式多线程(纯单线程优化) | worker 池 + 快照模型 |

**判断**:NT 的捕获/worker/应用三段式与 C2ME 的"主线程调度 + 异步执行"精神一致,且边界文档化程度**优于参考项目**(C2ME 无 DESIGN 文档,靠包名约定)。这块不需要照抄,保持即可。

### 2.5 平台抽象

| | 现状 | 参考实践 |
|---|---|---|
| 平台差异处理 | fabric/neoforge 壳各自声明 mixin 配置 + metadata | FerriteCore: reflection-over-interface(IPlatformHooks),Common 零 loader 依赖 |
| 当前 NT 的跨平台差异 | 小(只有 mixin 配置和 metadata) | — |

**判断**:NT 目前平台差异极小(无 access widener/transformer、无平台特定 API 依赖),reflection-over-interface 属过度设计;若将来需要 access transformer 或平台 service,再引入。

## 3. 改进优先级(按收益/成本)

| 优先级 | 动作 | 收益 | 成本 |
|---|---|---|---|
| **P1** | 抽 `ExplosionPipeline`:mixin 只留注入点,管线逻辑进 ep 包 | 可单测、可复用、review 友好 | 中(重构,需保行为) |
| P2 | 按功能拆 mixin 包 + 独立 mixin 配置(短路/射线/实体) | 逐功能可开关、回归隔离 | 中 |
| P3 | 声明式配置(Option 注册表) | 代码即文档 | 低(但当前配置已够用) |
| — | 不引入:reflection-over-interface、C2ME 式多模块分桶 | NT 规模不需要 | — |

## 4. 直接可借鉴的轻量改进(无需大改)

1. **mixin 方法命名/包内注释约定**:Lithium 用 `mixin.<包路径>` 作配置键,NT 若加配置可按同样层级
2. **ducks 接口约定**:若 mixin 需要跨注入点共享状态,用 `ferritecore$` 风格前缀接口而非重复 @Unique
3. **differential 测试已是优势**,保持;参考 Lithium 的 `testVanilla`(禁全部 mixin 跑一遍验证原版等价)模式

## 5. 结论

NT 的**模块划分、common/壳分离、线程边界、测试纪律**已对齐参考项目的主流实践;
最大差距是 **mixin 厚度**(626 行大 mixin 承载管线逻辑)——这是下一步重构的重点;
配置粒度、平台抽象在**当前规模下不是瓶颈**,不必照搬参考项目的复杂度。

(参考仓库: `/tmp/ref-mods/{lithium-fabric,C2ME-fabric,ferrite-core}`,本分析基于 shallow clone 源码勘察)
