# local.48 · 网络样板图标与供应器来源映射

日期：2026-10-26。建立在未提交的 local.47 工作区改动之上，未覆盖该批改动。

## 已确认原因

原 RouteView.Choice 仅传 id/inputs/outputs/alternatives，没有供应器来源。RouteScreen 对原生编码样板无法提供 JEI recipeId；RouteJeiRecipes.treeMachines 仅由实际加载过的 JEI 条目填充，因此原生样板会落到问号。该 JEI 表不是 AE 供应器来源表，不能靠强塞物品名来修正。

原供应器抽屉枚举与 AE 原生终端同样使用精确运行时类枚举；本轮没有证据证明原生供应器列表为空的唯一原因就是该枚举。此次将抽屉与新增来源映射统一为 RouteProviders，直接读取活动节点的实际 PatternContainer owner，避免分裂的发现逻辑。隐藏、离线、不同网络、零容量设备仍会被排除，不能为凑数量绕过过滤。

## 修复路径

1. RouteProviders：活动节点 → 实际 PatternContainer，按引用去重并按终端顺序排序，供应器抽屉/上传/来源索引共同使用。
2. RoutePatternSources：在服务端读取真实编码样板槽，decodePattern 后按完整 RouteIdentity 匹配当前网络配方 ID；返回名称、终端分组图标、维度坐标（标准 LogicHost）、实际槽号。请求内建立索引，不持久保存世界/机器引用。每样板最多 8 条来源，单个不支持或损坏样板不终止其他槽扫描。
3. RouteView.Choice 新增 sources，只保留展示元数据；旧构造器和旧 JSON 的 sources 为空列表。来源字段不参与规划证明或物品转移授权。
4. RouteNetwork 将来源纳入原 170000 字符预算，超出时保留原配方、舍弃该配方新增来源并标记截断，不挤爆 200000 字符响应上限。
5. RoutePatternPresentation 让已选中节点按精确 choice.id 读取迟到的查询元数据；相同输出但不同样板 ID 不串来源。
6. RouteNativePatternIcon 独立于 JEI 的机器缓存绘图。真实终端分组图标可能代表供应器邻接机器，但仅以 AE 提供的分组为依据；不宣称是推测出的执行设备。无映射则显示编码样板图标并明确说明来源未知。
7. 配方检查器增加来源名称/槽号，悬停列出来源坐标与槽号；native 图标点击查询当前显示的图标物品。JEI 草稿继续走原机器图标逻辑。

## 验证

- 全量 test 与 routeFunctionalTest 各 442 项，0 失败；后者使用 -Xverify:all，同一套测试不重复计数。
- 新增 8 项来源呈现/旧消息兼容/活动供应器枚举测试。
- 专用服务器 verifyCanvasApply 增加真实来源断言：当前网络的循环样板各有供应器、非空图标和坐标。
- 12 个专用服务器场景全部通过，汇总 `ALL-SCENARIOS-PASS`；真实网格记录 `native pattern sources: providers=1 patterns=3`，每张被测原生样板都有非空图标、坐标和实际槽号。
- `git diff --check` 通过；`test routeFunctionalTest build -x productionBenchmark` 为 BUILD SUCCESSFUL。
- 游戏内图标绘制、悬停和真实玩家网络供应器表尚未手动验收，不将单测或服务器回归冒充客户端验收。

日志：build/local48-build.log、build/local48-server-suite.log、build/local48-selftest-<task>.log。

## 使用与边界

构建产物位于 build/libs/aecraftroute-1.13.19-local.48-dev_neoforge_1.21.1.jar，尚未部署到正式实例，未提交或推送。若用于多人服务器，两端均需更新才能取得新来源数据；旧服务端回包只会显示样板占位图标。来源是查询快照，移动/移除供应器后应重新打开画布刷新。

自定义合成服务若没有可见 PatternContainer 槽位，或其运行时包装身份不能与库存解码身份相等，仍保持“来源未知”的样板图标，不伪造映射。普通 AE 供应器的真实映射由隔离专用服务器断言验证。

## 产物 SHA256

```text
157144b9ec31a89c128924b3e1f37adb353627b978870d511a157ae95eaca6ab  aecraftroute-1.13.19-local.48-dev_neoforge_1.21.1-sources.jar
89cb12ecfa4827580ad4f16a7f13accbc92702fcf5ab49f9f1d3f649cd6e8f61  aecraftroute-1.13.19-local.48-dev_neoforge_1.21.1.jar
```
