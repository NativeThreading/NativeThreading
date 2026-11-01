# 开发文档

> 面向改代码的人(和 agent)。装 mod、看效果、构建产物见根 [`README.md`](../README.md)。
> 这里只有**流程**和**自检规矩**;事故与调研教训归档在本地 `docs/archive/`(不进仓库)。

## 1. 环境

- JDK 25(Gradle toolchain;`build.gradle.kts` / 各模块 `gradle.properties` 里是活配置)。
- 基准套件另需 Java 26 runtime、Python 3.11+、`taskset`;见 [`benchmarks/README.md`](../../benchmarks/README.md)。
- 直接用仓库自带的 Gradle wrapper,不要用系统 Gradle。

## 2. 构建与测试

```bash
./gradlew build                 # 编译 + 单元测试 + validateMixinDiscipline
./gradlew :core:test
./gradlew :explosion:test
./gradlew :fabric:runGameTest   # 完整服务端 gametest,故意独立:约 50s
```

- `build` / `check` 故意**不**跑 gametest(它会起完整专用服务器);改动 mixin 接线后手动跑 `:fabric:runGameTest`。
- `validateMixinDiscipline` 校验 M2/M4:mixin 配置是严格 JSON、列出的类存在、单类 ≤ 250 行。
- 产物:`build/libs/native-threading-*.jar`;Fabric 与 NeoForge 各一个。

## 3. 基准

测量工具和协议见 [`benchmarks/README.md`](../../benchmarks/README.md)。
判定优化的规矩(B1/B2)见 [`architecture-discipline.md`](architecture-discipline.md) §8:
轮间固定 warmup、同 commit 对照、以 profile self-time 为准,不以分配字节为准。

## 4. 提交

[Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <description>

perf(explosion): improve ray tracing
fix(core): keep fallback on drawn RNG
chore(build): update Gradle wrapper
```

- Scope:`core`、`explosion`、`build`、`docs`。
- 一次提交做一件事;失败/回退的尝试单独提交并写清结论,不混进优化提交。
- 发布标签只在 `main` 上有效(`.github/workflows/build.yml` 会校验)。

## 5. 代码风格与模块规则

- Java 25,4 空格缩进。
- **不依赖 NotEnoughPalette**;两个 mod 独立测试。
- 并行派发优先用 `NtWorker.mapEach` / `mapBatched`。
- 新并行化特性 = 新 subproject,带 `build.included.gradle.kts`;mixin 注册在模块级
  `*.mixins.json`;Fabric entrypoint 注册在 `fabric/src/main/resources/fabric.mod.json`
  的 `"provides"` 里。
- 特性专属逻辑留在特性模块,不搬进 `core`(见 [`code-organization.md`](code-organization.md) §3)。

## 6. 自检(提交前逐条过)

**规则来源:** [`architecture-discipline.md`](architecture-discipline.md) §9 与
[`code-organization.md`](code-organization.md) §5 是完整清单;最小版本:

- [ ] 每个 mixin ≤ 250 行,只做注入,管线逻辑在实现类
- [ ] 所有 static 缓存:有安全论证注释、绑定世界带 level-key、只在主线程写、持有活 chunk/section 引用的校验对象身份
- [ ] 没有让 vanilla 重跑已消费 RNG / 已做一半工作的 fallback
- [ ] 新数值路径有 differential 测试;`./gradlew build` 通过
- [ ] 每处新代码能说出 profile 依据 + 消费者;没有测试的优化不提交
- [ ] 配置只做模块级 on/off;benchmark 同 commit 对照、轮间固定 warmup
- [ ] 改了 mixin 接线 → 跑过 `./gradlew :fabric:runGameTest`

## 7. 文档在哪

| 文件 | 内容 |
|---|---|
| [`architecture-discipline.md`](architecture-discipline.md) | 硬规则(M/C/T/R/V/F/D/B)与提交前清单 |
| [`code-organization.md`](code-organization.md) | 文件拆分、模块划分、线程安全层、新功能模板 |
| [`explosion-pipeline.md`](explosion-pipeline.md) | 当前爆炸管线的三段式实现 |
| [`../../AGENTS.md`](../../AGENTS.md) | agent 入口(线程边界 + 规则摘要) |
| [`../../core/AGENTS.md`](../../core/AGENTS.md)、[`../../explosion/AGENTS.md`](../../explosion/AGENTS.md) | 模块局部说明 |
| [`../../.agents/skills/native-threading-architecture/`](../../.agents/skills/native-threading-architecture/) | agent 侧规则副本(与 architecture-discipline 同步) |
| `docs/archive/`(本地,gitignored) | 教训、被证伪方向、旧设计、调研笔记 |

**对外发布只放流程与自检规矩。** 事故叙述、实测否决、旧数据和调研笔记放进
`docs/archive/`,不进仓库。
