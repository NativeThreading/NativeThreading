# Minecraft 26.3 移植记录

> 状态:已完成。`main` 已升级到 26.3,`./gradlew build` 全绿。
> 升级前的可用 26.2 状态保存在 `26.2` 分支(commit `911b0bc`)。
>
> 本文记录"目标版本、改了哪些文件、原版 API 审计证据、踩到的唯一工具链坑、验证结果、未覆盖项"。
> 这是移植记录,不是长期规范;架构规则仍以 `docs/architecture-discipline.md` 为准。

## 1. 目标版本

Minecraft 26.3 是正式版(发布于 2026-09-15,`javaVersion.majorVersion = 25`),因此 Java 版本要求不变。

| 项 | 26.2(升级前) | 26.3(升级后) | 依据 |
|---|---|---|---|
| Minecraft | 26.2 | **26.3** | 官方 version manifest(`latest.release = 26.3`) |
| Java | 25 | 25(不变) | 26.3 version manifest `javaVersion` |
| Fabric Loader | 0.19.3 | **0.19.5** | `fabric-example-mod@26.3`;Architectury 26.3 最低 0.19.5 |
| Fabric API | 0.152.1+26.2 | **0.161.0+26.3** | `fabric-example-mod@26.3`;Maven 上 26.3 最新 |
| Fabric Loom | 1.16-SNAPSHOT | **1.18-SNAPSHOT** | `fabric-example-mod@26.3`(构建日志实际解析为 Loom 1.18.2) |
| Gradle wrapper | 9.4.1 | **9.7.1** | Loom 1.18 要求 `org.gradle.plugin.api-version ≥ 9.7.0`;官方模板为 9.7.1 |
| NeoForge | 26.2.0.1-beta | **26.3.0.41-beta** | NeoForge Maven 上 26.3 最新 |
| ModDevGradle | 2.0.141 | **2.0.148** | 见 §4:2.0.141 附带的 jst 2.0.6 无法处理 26.3 的 access transformer |

外部资料:
- NeoForge 官方迁移 primer(26.2 → 26.3):<https://docs.neoforged.net/primer/docs/26.3/>
- Architectury 移植页(给出 26.3 最低依赖版本):<https://docs.architectury.dev/api/porting/port-26.2-26.3/>
- Fabric 官方模板 `FabricMC/fabric-example-mod` 的 `26.3` 分支(给出 loader/loom/API 组合)

## 2. 改动清单(零 Java 源码改动)

升级只需要动版本与元数据,**没有任何 `.java` 文件被修改** —— 见 §3 的 API 审计。

| 文件 | 改动 |
|---|---|
| `gradle.properties` | MC/loader/Loom/Fabric API/NeoForge 五个版本号 |
| `gradle/wrapper/gradle-wrapper.properties` | `distributionUrl` → `gradle-9.7.1-bin.zip`(wrapper jar 与 9.4.1 字节相同,无需替换) |
| `core/gradle.properties`、`explosion/gradle.properties` | 同上五个版本号(模块可独立构建,这些是活配置) |
| `fabric/src/main/resources/fabric.mod.json` | `minecraft: ~26.3`、`fabricloader: >=0.19.5` |
| `core/fabric/src/main/resources/fabric.mod.json` | 同上 |
| `explosion/fabric/src/main/resources/fabric.mod.json` | 同上 |
| `neoforge/build.gradle.kts`、`core/neoforge/build.gradle.kts`、`explosion/neoforge/build.gradle.kts` | ModDevGradle `2.0.141` → `2.0.148` |
| `.github/workflows/build.yml` | `game-versions: 26.3` |
| `core/.github/workflows/build-and-publish.yml`、`explosion/.github/workflows/release.yml` | `game-versions: "26.3"` |
| `README.md`、`AGENTS.md`、`explosion/README.md` | 版本与 loader 下限文案 |

未改:mixin `compatibilityLevel` 保持 `JAVA_25`(26.3 仍要求 Java 25);`options.release` 保持 Fabric 25 / NeoForge 26。

## 3. 原版 API 审计(26.2 → 26.3)

方法:用官方 26.3 server jar(从 piston-data 下载,sha1 `33680f5f…`)与本地 26.2 `minecraft-merged-deobf-26.2.jar` 做 `javap -p` 逐类比对。**结论:本 mod 用到的每个成员在 26.3 都存在且签名不变,因此无需改源码。**

| 目标 | 结论 |
|---|---|
| `net.minecraft.world.level.ServerExplosion` | `javap -p` 输出与 26.2 **完全一致**(字段 `level/center/radius/source/damageSource/damageCalculator/hitPlayers/fire`,方法 `calculateExplodedPositions()`/`hurtEntities()`/`interactsWithBlocks()`) |
| `ServerChunkCache.getVisibleChunkIfPresent(long)` | 存在,签名不变(`@Shadow` 目标安全) |
| `GenerationChunkHolder.getChunkIfPresentUnchecked(ChunkStatus)` | 存在(`ChunkHolder` 继承;`ServerChunkCacheMixin` 调用点安全) |
| `BlockState` 的 `isAir/getCollisionShape/getFluidState/getBlock/isRandomlyTicking` | 声明在父类 `BlockBehaviour$BlockStateBase`,26.3 未变 |
| `VoxelShape` 的 `isEmpty/toAabbs/bounds/move`,`Shapes.block()` | 未变 |
| `Mth.floor/ceil/frac/lerp` | 未变 |
| `Entity/LivingEntity/Player/Projectile` 的 `hurt/push/getBoundingBox/getEyeHeight/ignoreExplosion/onExplosionHit/setOwner/…` | 未变 |
| `ExplosionDamageCalculator.shouldBlockExplode/getBlockExplosionResistance` | 未变 |
| `EntityBasedExplosionDamageCalculator`、`ExplosionDamageCalculator` | 未变(并行分层 `Tier.A/B/C` 判定仍然成立) |
| `EntityTypes.ITEM/ZOMBIE`、`EntityTypeTags.REDIRECTABLE_PROJECTILE`、`Attributes.EXPLOSION_KNOCKBACK_RESISTANCE`、`Blocks.*` | 未变 |
| `Level.setBlockAndUpdate` / `setBlock(pos,state,int)` | 26.3 移到 `LevelWriter` 的 default 方法(源码兼容;本 mod 并未调用) |

NeoForge primer 中 26.2 → 26.3 的大改动集中在客户端(LWJGL 从 GLFW 换 SDL、Blaze3d 拆出 Renderpearl、`outputColorTextureOverride` 移除)与 GameTest/结构管理器改名,**均不在本 mod 的服务端热路径上**。

## 4. 唯一的工具链坑:NeoForge `createMinecraftArtifacts` 编译失败

### 现象

ModDevGradle 2.0.141 + NeoForge 26.3.0.41-beta 下,`:neoforge:createMinecraftArtifacts` 在 recompile 阶段失败:

```
ERROR Line: 44, contents() in <anonymous net.minecraft.core.HolderSet$1> cannot override
contents() in net.minecraft.core.HolderSet.Named; attempting to assign weaker access privileges; was public
```

### 根因

NeoForge 26.3 的 `META-INF/accesstransformer.cfg` 同时放宽了两个目标:

```
public net.minecraft.core.HolderSet$Named contents()Ljava/util/List;
public net.minecraft.core.HolderSet$1     contents()Ljava/util/List;
```

ModDevGradle 2.0.141 使用 `jst-cli-bundle:2.0.6` 做源码级 AT 变换:它把 `HolderSet$Named.contents()` 改成了 `public`,但**没有**把同一文件里 `emptyNamed()` 中的匿名类(`HolderSet$1`)的 `contents()` 一起改掉,于是匿名子类以 `protected` 覆写 `public` → javac 报错。该文件本身是 NeoForge 修补后的原版源码,不是本 mod 的代码。

### 修复

把 ModDevGradle 升到 **2.0.148**(附带 `jst-cli-bundle:2.0.11`)。修复后 NeoForm 的 `transformSources` 产物中匿名类的 `contents()` 已是 `public`,recompile 通过。已核对变换产物:`HolderSet.java:44` 由 `protected` 变为 `public`。

> 教训:MC 版本升级时,NeoForge 侧的 ModDevGradle 版本和 `neoforge_version` 一样是"升级目标",不能只升后者。

## 5. 验证证据

在 `main`(26.3)上执行:

| 检查 | 结果 |
|---|---|
| `./gradlew build` | **BUILD SUCCESSFUL**(33 tasks;含 `validateMixinDiscipline`) |
| `:core:test` / `:explosion:test` | 通过(JUnit) |
| `:fabric:runGameTest` | **5 个 GameTest 全部通过**("All 5 required tests passed");日志中 `Explosion entity paths: active=…, workerBatches=…, fallbacks=0` —— 并行实体路径在 26.3 上真实生效且无降级 |
| `:neoforge:createMinecraftArtifacts` + `:neoforge:build` | 通过,产出 `native-threading-neoforge-1.0.0.jar` |
| `:fabric:build` | 通过,产出 `build/libs/native-threading-911b0bc2.jar`(`fabric.mod.json` 中 `minecraft: ~26.3`) |
| `validateMixinDiscipline` | 通过(M2/M4:严格 JSON、类存在、≤250 行) |

产物内混入配置已确认:`parallel-core.mixins.json`、`explosion.mixins.json` 均打进 jar;NeoForge jar 的 `neoforge.mods.toml` 正常。

## 6. 未覆盖 / 后续

1. **benchmark 套件仍固定在 26.2**(`benchmarks/tnt`、`benchmarks/pathfinding` 及其 `gradle.properties`)。它们是测量工具,记录的结果只在被测量的 commit 上有效(B1:改基准 = 重测),因此本次不动它们的历史 provenance。要在 26.3 上出数,需要把 harness 升级到 26.3 并**重跑**基准,不能复用 26.2 的 MSPT 数字。
2. **没有在 26.3 上跑独立服务端实测**(只有 GameTest + 编译)。后续应部署到 `~/fabric-server` 并用 125 TNT 场景复核 MSPT 与 RNG 逐位一致性。
3. `docs/architecture-discipline.md` D3 中"是否仍适用于 MC 26.2"一句是历史评审注记,指向的 mixin 候选当前已不在配置里,本次未改。
4. `.idea/modules/{hopper,redstone,vectorial}` 等 IDE 元数据仍写着 `26.2`,对应模块已删除;属编辑器残留,未动。
