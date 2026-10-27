# local.47 · 画布 P0 实施与验证

## 范围

在 local.46 基线上做客户端呈现与交互修补，不替换布局算法、不改变持久化/编码/规划证明协议、不自动部署。

- P0-1：RouteAnchor 记录悬停→选中→根材料的 path/key 与世界坐标中心，重建及 applyOffsets 后反补 pan；同路径物品替换或节点删除不补偿；显式适应/整理优先。
- P0-2：默认滚轮鼠标锚缩放、Shift 滚轮水平平移、Alt 滚轮数量、Alt+Shift 单个数量；水平滚轮平移。COMMON 配置 scrollEditsQuantity=true 保留旧滚轮数量/Ctrl 缩放，包括空白区纵向滚动。
- P0-3：自动/库存/适应工具条与节点菜单接线。适应保留手动位置，整理清位置。折叠只修改 RouteBranchEdits 的 collapsed，不清 branches、drafts、quantities；恢复原隐藏过滤，可撤销。
- P0-4 部分完成：数量角标与边框区分网络计划可行、未证明、本地编辑未证明；起手资本一次解码成 Map<AEKey,Long>，不按中文名查找。仅库存规则优先于旧画布配方预览，但保留本地分支数据，恢复自动后可继续编辑。

## 数据语义修正与暂缓项

RoutePlanView.startup 来自 CertifiedCraftingPlan.usedItems，是计划起手资本，不是实时库存。循环增殖路线可用 8 个起手资本完成 1000 个目标；将 demand-startup 当缺口会误报 992 缺料。LIMIT/UNSUPPORTED/ERROR 也不能推导缺料。

因此此次没有实现“缺料橙 / 库存备齐绿”分类。绿色表示服务端网络计划可行，灰色表示未证明，琥珀表示本地编辑尚未证明；tooltip 明确其区别。本地分支或数量覆盖存在时，全树采用保守的本地未证明外观。要实现逐节点库存缺口，需要先设计库存快照及同一 AEKey 跨分支分配语义，不能依赖 startup。

自动/库存是服务端策略操作，折叠是本地可撤销视图操作，两者不混入同一撤销栈。原始本地配方保留，不以策略切换为由悄悄删除。

## 自动验证

- `test`：434 项，0 failures / 0 errors / 0 skipped（原 416 项，新增 18 项）。
- `routeFunctionalTest`：同一套 434 项，在 `-Xverify:all` 下通过；不是额外 434 个不同测试。
- `build -x productionBenchmark`：BUILD SUCCESSFUL；未运行性能基准。
- 专用服务器 12 场景：growth、advanced、overestimate、advanced-overestimate、cancel、reload、partial、paths、stock-race、advanced-stock-race、restart1、restart2 全部 PASS，汇总 ALL-SCENARIOS-PASS。
- 测试只使用 run-selftest，旧测试世界已移动保留；没有修改真实游戏存档。
- `git diff --check`：通过。
- 最终成品：build/libs/aecraftroute-1.13.19-local.47-dev_neoforge_1.21.1.jar（未部署、未提交/推送）。
- 游戏内客户端验收未执行，不将构建或服务器回归当作 UI 验收。

SHA256：

```text
695741ef7db92c86d8c24e3df53cb8ef87caeb974b5fcea3faba4b5bc5f41f8c  aecraftroute-1.13.19-local.47-dev_neoforge_1.21.1-sources.jar
8b282cbba9645a220209fe1aaf82ab006a947a367d49b384b13ec50fcd4471ba  aecraftroute-1.13.19-local.47-dev_neoforge_1.21.1.jar
```

日志（工程根目录下）：
- build/p0-canvas-build.log
- build/p0-canvas-server-suite.log
- build/p0-canvas-selftest-<task>.log

使用 PowerShell 7 / Java 21 / 本机 Gradle 8.8，离线构建。Gradle 在受限沙盒中无法加载 native-platform.dll，测试在获批的沙盒外执行。

## 游戏内手工验收清单（尚未执行）

- [ ] GUI 320×200、640×360、854×480：7/8 个工具条按钮不重叠，tooltip 与点击目标一致。
- [ ] 手动平移后展开深层分支、换配方、改数量、撤销：悬停或选中材料不跳出视口。
- [ ] 锚节点被删或同路径换物品：pan 不突然复位；Home 和适应仍能主动重定位。
- [ ] 默认：节点/空白处滚轮缩放，鼠标下世界点稳定；Shift 在节点上也平移；Alt 数量、Alt+Shift 单个。
- [ ] legacy=true：节点滚轮改数量、Shift 单个、Ctrl 缩放、空白滚轮纵移；右侧配方/搜索/节点菜单滚轮不被画布截走。
- [ ] 折叠→展开→撤销：配方、数量、局部隐藏过滤和手动位置不丢；折叠标记长度不同。
- [ ] 自动与库存服务端确认回包正确；库存不再自动选配方，提示缺料即不可合成；失败不显示成功提示。
- [ ] 拖动节点后点适应：保留偏移；点整理：清除偏移；不改变配方。
- [ ] 同名不同 NBT/不同 AEKey 的材料起手资本互不串值。
- [ ] 循环增殖路线不把大量最终需求与少量起手资本之差画成缺料；LIMIT/UNSUPPORTED/ERROR 不显示缺料。
- [ ] 本地未应用配方/数量编辑不显示为服务端已证明；绿色含义可由 tooltip 读懂。

## 后续

先完成真实客户端验收，再考虑发布或 P1。库存缺口/备齐视觉需要独立的数据契约，不宜沿用研究报告原公式。P1 导航、宽度、缺口浮层、本地视图过滤尚未实施。
