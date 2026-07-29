# NativeThreading 审计计划

## 概述

本审计计划针对 NativeThreading Minecraft 模组进行全面评估，该模组通过 Mixin 技术并行化服务器工作负载。审计将结合 `~/fabric-server` 测试环境进行实际验证。

## 审计目标

1. **线程安全性**：验证并行化代码的线程安全性和同步机制
2. **功能正确性**：确保并行化逻辑与原版行为一致
3. **性能验证**：验证性能声明并识别优化机会
4. **代码质量**：评估代码结构、可维护性和测试覆盖
5. **兼容性**：评估与其他模组和 Minecraft 版本的兼容性
6. **安全性**：识别潜在的安全风险和漏洞

## 审计范围

### 模块范围
- **core**：线程池、安全访问、配置、命令
- **explosion**：TNT/苦力怕爆炸并行化
- **hopper**：漏斗传输两阶段并行化
- **redstone**：红石线传播和中继器批处理
- **vectorial**：SoA 存储和 SIMD 操作（可选）

### 技术范围
- 线程模型和同步机制
- Mixin 注入点和兼容性
- 内存管理和垃圾回收影响
- 配置管理和热重载
- 测试覆盖率和质量

## 审计方法

### 阶段 1：静态分析（第 1 周）
1. **代码审查**：人工审查关键组件
2. **静态分析工具**：使用 SpotBugs、Error Prone 等
3. **依赖分析**：检查依赖树和版本冲突
4. **配置验证**：验证配置文件架构和默认值

### 阶段 2：动态分析（第 2 周）
1. **单元测试运行**：执行现有测试套件（`./gradlew :core:test ...`）
2. **基准测试**：使用 `quick-test.py` 在 fabric-server 中运行性能基准
3. **压力测试**：高负载下的并发测试（多 TNT、多漏斗、复杂红石）
4. **性能分析**：使用 Spark MCP + analyze-spark.py 进行深度分析

### 阶段 3：安全审计（第 3 周）
1. **线程安全审计**：竞态条件、死锁、数据竞争
2. **内存安全审计**：内存泄漏、缓冲区溢出
3. **权限审计**：命令权限、配置访问控制
4. **输入验证**：配置参数、外部输入

### 阶段 4：报告和建议（第 4 周）
1. **问题分类**：按严重性和优先级分类
2. **修复建议**：提供具体修复方案
3. **最佳实践**：总结改进建议
4. **文档更新**：更新架构文档和风险登记册

## 详细审计清单

### 1. 线程安全审计

#### 1.1 SafeLevelAccess（核心）
- [ ] ThreadLocal 异常安全性检查
- [ ] 重入深度限制验证
- [ ] 线程池回收时的清理机制
- [ ] 与 vanilla 代码的交互点

#### 1.2 ConcurrentWriteQueue（关键）
- [ ] Phase.accepting 可见性（应为 volatile）
- [ ] drain() 和 publish() 的竞态条件
- [ ] ThreadLocal 清理时机
- [ ] 写入丢失场景测试

#### 1.3 ParallelWorker（关键）
- [ ] results 列表的线程安全性
- [ ] 超时后的工作线程行为
- [ ] 异常传播机制
- [ ] 工作分区边界情况

#### 1.4 ParallelThreadPool（重要）
- [ ] 静态池存储的生命周期管理
- [ ] recreateAll() 的竞态条件
- [ ] 虚拟线程兼容性
- [ ] 线程泄漏检测

### 2. Mixin 安全审计

#### 2.1 ServerExplosionMixin（高风险）
- [ ] @Shadow 字段验证
- [ ] HEAD 注入点的安全性
- [ ] 取消机制的正确性
- [ ] 与其他爆炸模组的兼容性

#### 2.2 LevelMixin（漏斗，高风险）
- [ ] tickBlockEntities 替换的完整性
- [ ] 列表修改时的迭代器安全性
- [ ] 非漏斗实体的 tick 顺序
- [ ] pendingBlockEntityTickers 合并时机

#### 2.3 BlockGetterMixin（中等风险）
- [ ] @Redirect 目标的存在性
- [ ] require = 0 的静默失败风险
- [ ] ThreadLocal 池的内存影响
- [ ] LongOpenHashSet 重用的正确性

### 3. 性能审计

#### 3.1 并行化开销
- [ ] 线程池创建时间
- [ ] 任务提交开销（CountDownLatch）
- [ ] 同步成本（ConcurrentWriteQueue drain）
- [ ] 每次爆炸/漏斗 tick 的内存分配

#### 3.2 内存影响
- [ ] VisibilityCollisionSnapshot 的大数组分配
- [ ] BlockEntityPool ThreadLocal 的累积
- [ ] cachedFirstBlockDistances 的存储
- [ ] 长时间运行服务器的内存泄漏

#### 3.3 可扩展性
- [ ] 单线程 vs 多线程扩展性
- [ ] 不同工作负载下的性能特征
- [ ] CPU 利用率分布
- [ ] GC 压力评估

### 4. 测试覆盖审计

#### 4.1 现有测试评估（21 个测试文件）
- **core**：SafeLevelAccessTest, ParallelWorkerSchedulerRegressionTest, SpinBlockingQueueTest
- **explosion**：ExplosionParallelEligibilityTest, ExplosionHelperRayTest, ExplosionEntityDamageComputationTest
- **hopper**：HopperTransferPlanTest, HopperPlanningSnapshotTest, HopperCaptureContainmentTest
- **redstone**：RedstoneWireHelperExecutionTest, RedstoneWireHelperBehaviorTest, DiodeTickBatcherTest
- **vectorial**：SoAStoreTest

#### 4.2 测试覆盖缺口
- **并发测试**：无并发压力测试
- **集成测试**：无真实服务器集成测试
- **竞态条件测试**：无数据竞争检测
- **性能回归测试**：无基准测试套件

### 5. 配置和部署审计

#### 5.1 配置管理
- [ ] JSON 配置架构验证
- [ ] 默认值合理性
- [ ] 热重载安全性
- [ ] 文件锁定机制

#### 5.2 部署配置
- [ ] Fabric/NeoForge 兼容性
- [ ] 依赖版本管理
- [ ] JVM 参数要求
- [ ] 模组加载顺序

## 测试环境

### fabric-server 配置
- **服务器**：Minecraft 26.2, Fabric Loader 0.19.3, Java 26
- **世界**：自定义平坦虚空世界，81 个强制加载区块
- **基准测试世界**：125 个 TNT + 羊，在黑曜石壳内
- **已安装模组**：Fabric API, Carpet, Spark, Lithium, NotEnoughPalette
- **性能分析**：Spark RCON 远程控制

### 测试工具

#### 基准测试工具
- **quick-test.py**：基准测试编排脚本（构建、部署、启动服务器、收集 Spark 配置文件）
- **bench-results/**：85 个现有基准测试结果（2026-07-19 到 2026-07-24）

#### 性能分析工具（两层互补）
1. **Spark MCP 工具**（实时分析）：
   - `spark-profiler-mcp_load_profile`：加载 .sparkprofile 文件
   - `spark-profiler-mcp_get_summary`：获取性能摘要（TPS、MSPT、热方法）
   - `spark-profiler-mcp_get_call_tree`：查看调用树（识别瓶颈路径）
   - `spark-profiler-mcp_get_top_self_time`：查看最耗时方法
   - `spark-profiler-mcp_get_sources_breakdown`：按模组来源分析耗时
   - `spark-profiler-mcp_diagnose`：自动诊断（阈值检查、问题识别）
   - `spark-profiler-mcp_get_health`：健康检查（TPS/MSPT 时间序列）
   - `spark-profiler-mcp_get_system_stats`：系统统计（CPU、内存、GC）

2. **analyze-spark.py**（本地详细分析）：
   - 解析 .sparkprofile 二进制文件
   - 输出每线程节点计数（近似 CPU 时间比例）
   - 识别 lock/wait 竞争节点
   - 识别 JVM/native 开销
   - 列出 Top 30 方法

#### 互补使用策略
- **Spark MCP**：快速概览、自动诊断、按模组分析、调用树深度探索
- **analyze-spark.py**：详细线程分析、锁竞争识别、自定义格式输出
- **两者结合**：先用 MCP 获取摘要和诊断，再用 analyze-spark.py 深入特定线程

### 测试命令

#### 1. 单元测试（代码正确性）
```bash
# 运行所有模块单元测试
./gradlew :core:test :explosion:test :hopper:test :redstone:test :vectorial:test

# 运行特定模块测试
./gradlew :core:test
./gradlew :explosion:test
```

#### 2. 基准测试（性能验证）
```bash
cd ~/fabric-server

# 完整基准测试矩阵（base, nep, native, nep+native）
python quick-test.py

# 仅 NativeThreading，3 次重复
python quick-test.py --combo native --repeat 3

# 快速烟雾测试（缩短预热和分析时间）
python quick-test.py --combo native --warmup 10 --duration 10

# 跳过构建（仅测试配置更改）
python quick-test.py --skip-build --combo native

# 对比测试：基线 vs NativeThreading
python quick-test.py --combo base --repeat 3
python quick-test.py --combo native --repeat 3
```

#### 3. 性能分析（Spark MCP + analyze-spark.py）
```bash
# 使用 Spark MCP 工具（在 OpenCode 中）
# 1. 加载配置文件
spark-profiler-mcp_load_profile(source="bench-results/<run-dir>/profile-*.sparkprofile")

# 2. 获取摘要
spark-profiler-mcp_get_summary(profileId="<id>")

# 3. 自动诊断
spark-profiler-mcp_diagnose(profileId="<id>")

# 4. 按模组分析
spark-profiler-mcp_get_sources_breakdown(profileId="<id>")

# 5. 查看调用树
spark-profiler-mcp_get_call_tree(profileId="<id>", rootPath=["runServer", "tickChildren"])

# 使用 analyze-spark.py（本地详细分析）
python analyze-spark.py bench-results/<run-dir>/profile-*.sparkprofile
```

#### 4. 隔离测试（配置排除）
```json
// 测试仅爆炸并行化
{
  "hopper-parallelization": { "enabled": false },
  "light": { "enabled": false },
  "pathfinding": { "enabled": false }
}

// 测试 SIMD 开/关
{
  "parallel-core": { "simdEnabled": true/false }
}

// 测试不同池大小
{
  "parallel-core": { "poolParallelism": 4/8/16/24 }
}
```

## 时间表

### 第 1 周：静态分析和代码审查
- **日 1-2**：代码结构审查和依赖分析
- **日 3-4**：线程安全静态分析
- **日 5**：配置验证和文档审查

### 第 2 周：动态分析和测试
- **日 1-2**：单元测试执行和覆盖率分析（`./gradlew test`）
- **日 3-4**：基准测试和性能分析（`quick-test.py` + Spark MCP）
- **日 5**：压力测试和稳定性验证

### 第 3 周：安全审计和深入分析
- **日 1-2**：线程安全审计（竞态条件、死锁）
- **日 3-4**：内存安全和权限审计
- **日 5**：兼容性测试

### 第 4 周：报告和建议
- **日 1-2**：问题分类和严重性评估
- **日 3-4**：修复建议和最佳实践
- **日 5**：最终报告和文档更新

## 资源需求

### 人员
- **审计负责人**：1 人，负责整体协调和质量保证
- **线程安全专家**：1 人，专注于并发和同步
- **Minecraft 模组专家**：1 人，专注于 Mixin 和兼容性
- **性能工程师**：1 人，专注于基准测试和优化

### 工具
- **静态分析**：SpotBugs, Error Prone, IntelliJ IDEA
- **基准测试**：quick-test.py（编排脚本）
- **性能分析**：Spark MCP 工具（实时诊断）+ analyze-spark.py（详细分析）
- **测试框架**：JUnit 5, Minecraft GameTest
- **版本控制**：Git, GitHub

### 环境
- **开发环境**：Java 26, Gradle, Fabric Loom
- **测试环境**：fabric-server 实例
- **生产环境**：真实 Minecraft 服务器（可选）

## 风险矩阵

| 风险 | 严重性 | 可能性 | 缓解措施 |
|------|--------|--------|----------|
| ConcurrentWriteQueue 竞态条件 | 关键 | 中 | 添加 volatile，测试并发 |
| ParallelWorker 结果损坏 | 关键 | 低 | 使用线程安全集合 |
| Mixin 在 MC 更新时失效 | 高 | 高 | 版本锁定，测试套件 |
| 快照对象内存压力 | 中 | 中 | 池分配，大小限制 |
| 线程池泄漏 | 中 | 低 | 生命周期管理 |
| 性能回归 | 中 | 中 | 基准测试 CI |

## 交付物

### 1. 审计报告
- **执行摘要**：关键发现和建议
- **详细发现**：按类别组织的所有问题
- **风险评估**：风险矩阵和优先级
- **修复建议**：具体修复方案和代码示例

### 2. 测试报告
- **单元测试结果**：覆盖率分析和缺口
- **集成测试结果**：功能正确性验证
- **性能测试结果**：基准测试数据和趋势
- **压力测试结果**：并发和稳定性验证

### 3. 改进计划
- **短期修复**（1-2 周）：关键安全问题
- **中期改进**（1-2 月）：测试覆盖和文档
- **长期优化**（3-6 月）：架构改进和性能优化

### 4. 文档更新
- **架构文档**：线程模型和同步策略
- **风险登记册**：已知风险和缓解措施
- **测试指南**：测试方法和最佳实践
- **部署指南**：配置和兼容性要求

## 成功标准

### 安全性
- [ ] 无数据竞争或死锁
- [ ] 所有世界修改在主线程执行
- [ ] 异常处理不破坏状态
- [ ] 内存使用稳定

### 功能性
- [ ] 并行化逻辑与原版行为一致
- [ ] 所有测试通过
- [ ] 配置热重载正常工作
- [ ] 命令权限正确

### 性能
- [ ] 小工作负载开销 < 10%
- [ ] 大工作负载扩展性良好
- [ ] 内存使用合理
- [ ] GC 压力可控

### 质量
- [ ] 测试覆盖率 > 80%
- [ ] 代码符合项目规范
- [ ] 文档完整且准确
- [ ] 无已知安全漏洞

## 后续步骤

### 立即行动（今天）
1. **运行单元测试**：`./gradlew :core:test :explosion:test :hopper:test :redstone:test :vectorial:test`
2. **检查现有基准测试结果**：查看 `bench-results/` 目录中的 85 次运行
3. **加载最新 Spark 配置文件**：使用 Spark MCP 工具分析最新基准测试

### 第 1 周：静态分析和代码审查
1. **代码结构审查**：使用 CodeGraph 探索项目架构
2. **线程安全静态分析**：审查 `ConcurrentWriteQueue.java`、`ParallelWorker.java`
3. **配置验证**：检查 `config/nt.json` 默认值和架构

### 第 2 周：动态分析和测试
1. **基准测试**：使用 `quick-test.py` 运行性能基准
2. **性能分析**：使用 Spark MCP + analyze-spark.py 进行深度分析
3. **压力测试**：测试高负载场景（多 TNT、多漏斗、复杂红石）

### 第 3 周：安全审计和深入分析
1. **线程安全审计**：识别竞态条件、死锁、数据竞争
2. **内存安全审计**：检查内存泄漏、缓冲区溢出
3. **兼容性测试**：测试与其他模组的兼容性

### 第 4 周：报告和建议
1. **问题分类**：按严重性和优先级分类
2. **修复建议**：提供具体修复方案
3. **最终报告**：编写审计报告和改进建议

## 附录

### A. 关键文件列表
- `core/common/src/main/java/com/github/uright008/pc/SafeLevelAccess.java`
- `core/common/src/main/java/com/github/uright008/pc/ParallelWorker.java`
- `core/common/src/main/java/com/github/uright008/pc/ConcurrentWriteQueue.java`
- `explosion/common/src/main/java/com/github/uright008/ep/mixin/ServerExplosionMixin.java`
- `hopper/common/src/main/java/com/github/uright008/hp/HopperParallelHelper.java`
- `redstone/common/src/main/java/com/github/uright008/rp/RedstoneWireHelper.java`

### B. 测试命令参考

#### 单元测试（代码正确性）
```bash
# 运行所有模块单元测试
./gradlew :core:test :explosion:test :hopper:test :redstone:test :vectorial:test

# 运行特定模块测试
./gradlew :core:test
./gradlew :explosion:test
./gradlew :hopper:test
./gradlew :redstone:test
./gradlew :vectorial:test
```

#### 基准测试（性能验证）
```bash
cd ~/fabric-server

# 完整基准测试矩阵（base, nep, native, nep+native）
python quick-test.py

# 仅 NativeThreading，3 次重复
python quick-test.py --combo native --repeat 3

# 快速烟雾测试（缩短预热和分析时间）
python quick-test.py --combo native --warmup 10 --duration 10

# 跳过构建（仅测试配置更改）
python quick-test.py --skip-build --combo native

# 对比测试：基线 vs NativeThreading
python quick-test.py --combo base --repeat 3
python quick-test.py --combo native --repeat 3
```

#### 性能分析（Spark MCP + analyze-spark.py）
```bash
# === Spark MCP 工具（在 OpenCode 中使用）===

# 1. 加载配置文件
spark-profiler-mcp_load_profile(source="bench-results/<run-dir>/profile-*.sparkprofile")

# 2. 获取性能摘要（TPS、MSPT、热方法）
spark-profiler-mcp_get_summary(profileId="<id>")

# 3. 自动诊断（阈值检查、问题识别）
spark-profiler-mcp_diagnose(profileId="<id>")

# 4. 按模组来源分析耗时
spark-profiler-mcp_get_sources_breakdown(profileId="<id>")

# 5. 查看调用树（识别瓶颈路径）
spark-profiler-mcp_get_call_tree(profileId="<id>", rootPath=["runServer", "tickChildren"])

# 6. 查看最耗时方法
spark-profiler-mcp_get_top_self_time(profileId="<id>")

# 7. 健康检查（TPS/MSPT 时间序列）
spark-profiler-mcp_get_health(profileId="<id>")

# 8. 系统统计（CPU、内存、GC）
spark-profiler-mcp_get_system_stats(profileId="<id>")

# === analyze-spark.py（本地详细分析）===

# 分析单个配置文件
python analyze-spark.py bench-results/<run-dir>/profile-*.sparkprofile

# 批量分析所有 NativeThreading 运行
for dir in bench-results/*-native-*; do
  echo "=== $dir ==="
  python analyze-spark.py "$dir"/profile-*.sparkprofile
done
```

#### 隔离测试（配置排除）
```bash
# 编辑 config/nt.json 禁用特定子系统
# 测试仅爆炸并行化
{
  "hopper-parallelization": { "enabled": false },
  "light": { "enabled": false },
  "pathfinding": { "enabled": false }
}

# 测试 SIMD 开/关
{
  "parallel-core": { "simdEnabled": true/false }
}

# 测试不同池大小
{
  "parallel-core": { "poolParallelism": 4/8/16/24 }
}

# 重新运行基准测试
python quick-test.py --skip-build --combo native --repeat 3
```

### C. 配置示例
```json
{
  "parallel-core": {
    "poolParallelism": 16,
    "simdEnabled": true
  },
  "explosion-parallel": {
    "enabled": true,
    "samplingFactor": 2.0,
    "preciseRays": true
  },
  "hopper-parallel": {
    "enabled": true
  },
  "redstone-parallel": {
    "enabled": false
  }
}
```

---

**审计计划版本**：1.0  
**创建日期**：2026 年 7 月 28 日  
**负责人**：Sisyphus  
**审核状态**：待审核