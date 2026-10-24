# 代码质量审查报告 - 2026-10-25

## 审查范围
VM 内核集成、路由 UI 数量覆盖、机器图标旋转、Provider 搜索、阶段归因日志

## 测试验证
✅ **641 个测试全部通过，0 失败**
- ProductionPlannerTest: 8/0 (含 bulkAmountsAreNotExpandedIntoIndividualObjects)
- ProductionPlannerUpstreamTest: 7/0
- NetDeltaOracleTest: 9/0
- NumericCraftingVMTest: 15/0 (含 2000 次随机 BigInteger 对比)
- 所有其他测试套件均通过

## 代码质量评估

### ✅ 优秀实现

**1. VM 内核 (CompiledNumericProgram, NumericProgramCompiler, NumericCraftingVM)**
- 操作码设计清晰：CHECK_PREFIX=1, LIMIT_OUTPUT=2, APPLY_DELTA=3, RECORD_PATTERN=4, HALT=5
- 稀疏指令编码，只发射非零键
- 原子执行：写操作影响私有副本，仅在 HALT 时可见
- 精确溢出保护：使用 addExact/multiplyExact
- 催化剂/种子语义正确：count=min(count, 1+(working-key-input)/-delta)
- 预算耗尽返回 null 而非"不可达"

**2. RouteGraphMath**
- 纯函数设计，无副作用
- 溢出保护：safeAdd、multiplyExact
- 常量定义清晰：MAX_DEMAND=1e12
- 输入验证完整：parseDemand 限制 15 位数字

**3. RouteProviderFilter & RouteProviderSearch**
- 元数据过滤在分页前完成，性能优化
- 防抖机制：DELAY_NANOS=250ms
- 边界检查：MAX_QUERY_LENGTH=128
- 归一化处理：大小写不敏感、trim

**4. RoutePlanningService 阶段归因**
- 完整的阶段追踪：CAPTURE → WORKER_QUEUE → WORKER_COMPUTE → VALIDATION → COMPLETE
- 详细的性能指标：captureActiveMs、captureWallMs、workerQueueMs、workerComputeMs、validationMs
- 慢请求诊断阈值：SLOW_REQUEST_NANOS=500ms
- 错误阶段标记：errorPhase

**5. RouteScreen 数量覆盖**
- 本地覆盖：quantities map 从不发送到服务器
- 完整撤销支持：EditState 记录包含 quantities
- 根节点特殊处理：depth==0 修改 targetAmount
- 循环引用保护：cycle 节点禁止覆盖

**6. 机器图标旋转 (RouteJeiRecipes)**
- 旋转周期：MACHINE_ROTATE_TICKS=60 (约 3 秒)
- 悬停暂停：hover 时保持当前机器
- 工具提示完整：显示候选设备编号、旋转说明

### ⚠️ 需要改进

**1. README 文档缺失**
- README.md 未提及 VM 内核集成
- README_en.md 同样缺失
- 建议添加以下章节：
  * VM 内核架构说明
  * 候选路径与 certify 机制
  * 性能优化说明（probe fast path）

**2. 生产规划器复杂度**
- ProductionPlanner.probe() 方法较长（约 130 行）
- 建议提取辅助方法：compileNumericProgram、buildCandidateWord

**3. RouteScreen 代码组织**
- RouteScreen.java 超过 400 行
- 数量编辑、节点菜单、拖拽逻辑混合
- 建议拆分为：RouteScreenQuantityEditor、RouteScreenNodeMenu

**4. 测试覆盖**
- ✅ NumericCraftingVMTest 15 个测试非常全面
- ✅ 包含随机化对比测试（2000 次 BigInteger 验证）
- ⚠️ UI 交互未有自动化测试（数量覆盖、机器图标悬停）

## 集成验证

### ✅ VM 内核作为候选路径
- ProductionPlanner.probe() 在反向搜索前尝试构造性前向批处理提示
- 编译一次 NumericProgramCompiler，在相同预算内
- 每个候选批次调用 NumericCraftingVM.execute()
- TIMED_OUT → 返回；EXECUTED → 提交库存
- **强制 certify()**：certified.status==FEASIBLE && !expired() 才采用
- 仅作为候选提示，不影响最终可行性证明

### ✅ Limits 未放宽
- RoutePlanningService 使用 Limits(30_000, 5_000_000_000L)
- 默认搜索 3 秒
- probe 预算与搜索预算共享同一 deadline

### ✅ 旧不变量保持
- 不进行逐路径 DAG 重新展开
- emittedItems 排除制造中间体
- deficit-aware applyBundleDirect

## 未完成事项

1. **游戏内验证**（需要用户同意，绝不触碰活动存档）
   - 数量覆盖：中间点击编辑器、滚轮 ±1 批次、Shift ±1 单位
   - 机器图标悬停：工具提示显示

2. **存档复制**（用于实验）
   - 源：D:\pcl\versions\ATM10\saves
   - 目标位置待定

3. **README 更新**
   - 添加 VM 内核章节
   - 补充架构图

4. **Provider 抽屉和画布 UI 收尾**
   - 代码已完成，需游戏内验证

## 架构建议

### 优先级 P0（当前）
- README 更新以反映 VM 内核
- 游戏内验证数量覆盖和机器图标

### 优先级 P1（下个迭代）
- RouteScreen 拆分以降低复杂度
- ProductionPlanner.probe() 提取辅助方法
- UI 自动化测试（可选）

### 优先级 P2（未来）
- NetDeltaOracle 集成到搜索排序（如果性能分析支持）
- 更多随机化测试用例

## 结论

**代码质量：优秀** ✅

VM 内核集成设计清晰，性能优化到位，测试覆盖全面。所有 641 个测试通过，无回归。候选路径机制正确实现，certify 强制执行确保正确性。UI 功能完整，本地覆盖机制符合预期。

主要改进空间在文档和代码组织，但不影响功能正确性。

**推荐：可以合并到主分支** ✅
