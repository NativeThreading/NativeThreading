# Leaves 平台适配

> 面向改代码的人。装机、产物、构建命令见根 [`README.md`](../../README.md)；
> 这里记录 Leaves 外壳的运行时约束、命令桥、验证方法和已知差异。

Leaves 是通过 [`leavesclip`](https://github.com/LeavesMC/Leaves) 构建的 Paper fork：
服务端本身就是 leavesclip 启动器，自带 Mixin 服务（`MixinServiceKnot`），可以让
插件以**原生 mixin** 的方式改写服务端类 —— 不需要第三方 mod loader。`leaves/`
模块就是利用这一点把同一套 `core` + `explosion` 代码跑在 Leaves 上。

模块划分沿用仓库约定：`leaves/` 是**聚合壳**（插件元数据、leaves 专用 mixin 配置、
插件 main、构建/打包），各模块的 Leaves 平台壳放在 `<module>/leaves/`（与
`core/fabric`、`core/neoforge` 对齐）。core 平台壳 `core/leaves/` 持有 `/nt` 的
Bukkit 命令桥和 core 生命周期；`leaves/` 的插件 main 只负责组合模块与生命周期。

## 1. 形态与打包布局

`leaves/` 是 loader 壳，和 `fabric` / `neoforge` 平级：

- 编译与 mod 版**完全相同**的 `core/common` + `explosion/common` 源码、模块平台壳
  `core/leaves/src/main/java`，以及 `leaves/src/main/java` 里的插件 main；
- 产物不是 mod 而是 Leaves 插件：`build/libs/native-threading-leaves-<version>.jar`；
- jar 内布局：

```
leaves-plugin.json                          # 声明 features.required=["mixin"] + mixin.package-name
native-threading-core.mixins.json           # ServerChunkCacheMixin（target PREINIT / JAVA_13）
native-threading-explosion.mixins.json      # ServerExplosionMixin（target PREINIT / JAVA_13）
com/github/uright008/leaves/...             # 聚合插件 main（组合模块 + 生命周期），不进 mixin 运行域
com/github/uright008/nt/...                 # core + explosion 实现类、mixin 类，以及 core 的 Leaves 平台壳
                                            #   （NativeThreadingCoreLeaves / NtBukkitCommand）
```

启动时 leavesclip 把 `mixin.package-name`（`com.github.uright008.nt`）整棵子树抽到
`plugins/.mixins/native-threading.mixins.jar`，交给 `MixinURLClassLoader` 在服务端
classloader 里加载；插件壳由 Paper 插件 classloader 加载，parent-first 委托到同一份
类，所以配置、静态状态、命令注册在插件侧和 mixin 侧是同一份。

## 2. 构建

```bash
./gradlew :leaves:jar        # → build/libs/native-threading-leaves-<version>.jar
```

- Loom 在 `:leaves` 里只用于提供 **Mojang-mapped 26.3** 的 Minecraft 类；`remapJar`
  被显式禁用。Paper 系运行时就是 Mojang 名，remap 到 intermediary 反而会全炸。
- `minecraft_version` 与 mod 版共用（26.3）；插件元数据里的 `api-version` 用
  `leaves_api_version`（当前 `26.2`，对应目前唯一可用的 leavesclip fork）。插件入口
  只碰稳定 Bukkit API，mixin 侧类在 26.2 运行时可用。
- 编译期依赖全部 `compileOnly`（`paper-api`、`sponge-mixin`、`slf4j`、
  `org.jetbrains:annotations`），运行时由服务端提供，禁止打进 jar。

## 3. 运行时约束（都是实测踩过的）

1. **mixin 配置必须 `"target": "@env(PREINIT)"`。** leavesclip 启动器一直停在
   PREINIT 阶段；缺这行时配置会被选中、类也会准备，但 mixin **永远不应用**，
   而且日志里没有错误 —— 最隐蔽的坑。
2. **`"compatibilityLevel": "JAVA_13"`。** leavesclip 3.0.10 内置 Mixin 0.8.7，
   其 max effective compatibility 是 `JAVA_13`；写 `JAVA_21`/`JAVA_25` 会在启动时报
   "requested compatibility level could not be set"。类文件本身可以用
   `--release 25` 编译（V69，ASM 9.5 接受）。
3. **不需要 refmap。** 运行时是 Mojang-mapped/unobfuscated，类里直接引用
   `net.minecraft.world.level.ServerExplosion` 等名字。
4. **Paper 的 `BlockGetter` 比 vanilla 映射多两个抽象方法**
   （`getBlockStateIfLoaded` / `getFluidIfLoaded`）。`ExplosionCapturedBlockGetter`
   是唯一实现者，`explosion/common` 里现在显式声明了这两个方法（不加 `@Override`，
   vanilla 映射下也能编译），否则 Paper 系运行时可能 `AbstractMethodError`。
5. **启动参数必须加 `-Dleavesclip.enable.mixin=true`。** `ServerFeatureManager`
   只有在该属性为 true 时才暴露 `mixin` feature；插件的 `leaves-plugin.json` 声明
   `required=["mixin"]`，leavesclip 会跳过插件并打印
   `The plugin native-threading (...) requires some unavailable feature(s) [mixin].
   The plugin has been skipped.` —— 不会静默“加载但没 mixin”。
6. **换插件 jar 后删掉 `plugins/.mixins/`**，避免 leavesclip 复用旧抽取结果。
7. **Paper 系默认 `spigot.yml` 的 `world-settings.default.max-tnt-per-tick: 100`**
   会在实体 tick 层提前 return，把 TNT 链式反应截断。爆炸性能/一致性测量必须把它
   pin 成 `0`（或 >= 场景 TNT 数），否则测到的是节流而不是爆炸负载。

## 4. 命令桥

Paper 系不把 Brigadier dispatcher 暴露给插件，所以 `/nt` 在 Leaves 上走 Bukkit
命令表：

- `NtSubCommand` 的 `executePaper(Consumer<String>, String[])` /
  `tabCompletePaper(String[])` 是平台无关扩展点；
- core 的 Leaves 平台壳（`core/leaves/` 的 `NativeThreadingCoreLeaves`）在
  `onEnable` 里初始化 core，并通过 Bukkit `CommandMap` 注册 `/nt`
  （别名 `/native-threading`），由 `NtBukkitCommand` 把参数转发给对应 subcommand；
  聚合插件的 `NativeThreadingLeavesPlugin` 只调用模块壳，不自己注册命令；
- 权限对齐游戏内命令的 gamemaster 级别：控制台、OP、或
  `native-threading.admin`；
- 输出是 legacy `§` 颜色码字符串，Paper 的 `sendMessage(String)` 会转成 Component；
- Fabric/NeoForge 侧仍走 Brigadier（`NtCommandRegistration`），行为不变。

新增 subcommand 时两条路都要实现：`build()` 用于 Brigadier 平台，
`executePaper()` 用于 Leaves（否则 `/nt <name>` 只回落到 `getStatusLine()`）。

## 5. 验证方式

冒烟（Leaves 26.2 fork + leavesclip，插件放 `plugins/`，启动加
`-Dleavesclip.enable.mixin=true`）应看到：

```
[Mixin] SpongePowered MIXIN Subsystem Version=0.8.7 ... Service=Leavesclip Env=SERVER
[native-threading] Loading server plugin native-threading v<version>
[native-threading-core] NativeThreadingCore initializing — thread pool and world management ready.
[native-threading-config:explosion] Explosion pipeline: ON
[native-threading] NativeThreading enabled — leavesclip native mixin runtime active
[native-threading-core] Pool 'Explosion' ready - FORK_JOIN xN
```

`/nt`、`/nt explosion`、`/nt core`、`/nt explosion stats` 都应输出内容；
`plugins/.mixins/native-threading.mixins.jar` 应存在；日志无
`NoSuchMethodError` / `AbstractMethodError`。

性能验证用与本仓库 `benchmarks/` 相同的 125-TNT chamber 场景（固定 warmup +
同样窗口 + 同 commit 对照，B1），但 Paper 系没有 observer mod，所以用 datapack
tick counter + server 自带的 spark `tps` / tick durations 取代：

- `max-tnt-per-tick: 0`（见 §3.7），void superflat 世界、`forceload`、
  `function tnt_chamber:build`，40 s 预热后取 120 s 窗口；
- `spark tps` 在窗口首尾各取一次，TPS = Δticks/Δwall，MSPT ≈ 1000/TPS。

实测（Linux i9-12900HX，24 线程，Zulu JDK 25，`-Xmx4G`，Leaves 26.2 fork）：

| 配置 | 轮次 | ticks / 120 s | TPS | MSPT ≈ | tick p50/p95 | primed TNT |
|---|---|---|---|---|---|---|
| Leaves 26.2 基线（无插件） | 1 | 712 | 5.93 | 168.5 ms | 169.9/229.7 ms | ~2.4k 稳定 |
| + native-threading 插件 | 2 | 1737 / 1767 | 14.47 / 14.72 | 69.1 / 67.9 ms | 63.8/79.4、65.2/86.2 ms | ~2.4k 稳定 |

约 **2.4x TPS、单 tick 时间降到 ~41%**；同一场景早前一轮（等价插件代码）另测
6.82/6.48 TPS 对 15.24/15.81 TPS，同样 ~2.3-2.4x，所以差距不是加载器带来的。基线单轮
5.93 与早前 6.82/6.48 的差异来自机器/世界状态（同会话内对照仍然成立）。TNT 稳态
数量与基线一致（行为侧无可见偏差）。注意 Paper 系 `optimize-explosions` 默认 false，
不需要额外 pin。

本地构建 Leaves 服务端（AsagiriBeta/Leaves fork）：

```bash
git clone https://github.com/AsagiriBeta/Leaves.git     # master = 26.2
cd Leaves && ./gradlew applyAllPatches && ./gradlew createLeavesclipJar
# 产物 leaves-server/build/libs/leaves-leavesclip-26.2-R0.1-SNAPSHOT.jar
```

（上游 LeavesMC 目前只有 1.21.x 分支；26.3 fork 出现后应把 `leaves_api_version`
提上去并重跑上面的验证。）

## 6. 已知限制

- 仅专用服务端；没有单机 integrated server 场景（Leaves 是服务端发行版）。
- `config/nt.json` 在服务端工作目录下（与 mod 版一致），不是
  `plugins/native-threading/`；`/reload` 时 onDisable/onEnable 会重新初始化。
- Leaves 插件不参与 Modrinth 自动发布（没有 leavesclip loader 分类，避免被误装到
  Paper/Fabric）；CI 仍会上传该产物。
- 26.2 vs 26.3：jar 用 26.3 映射编译、在 26.2 fork 上运行；目前没有发现 26.2→26.3
  需要兼容修改的地方。若未来 26.3 Leaves 出现，应直接用 26.3 重测。
- `validateMixinDiscipline` 会额外校验 Leaves 打包约束（`leaves-plugin.json` 的
  required feature、`mixin.package-name` 覆盖范围、main class 不在被抽取的包内、
  每个 mixin 配置的 `target == @env(PREINIT)`）。
