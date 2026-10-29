# AELIS 方案实施状态

2026-10-29。发布目标为 1.13.19-local.66-dev；包含 SeedReachability 及低水位环回归修复。发布与安装完成情况见文末记录。

## 已接入

- ProductionPlanner 配方 inputs/outputs/deltas 改为 SparseAmounts，移除三组 recipes × keys 稠密数组；需求状态向量仍保留原表示。
- 普通前驱计算只对相关材料执行 BigInteger 运算，正向跳跃只遍历稀疏增量。
- 构造候选同时保留高水位和低水位捕获窗口，启动消耗目标库存后仍可发现增产循环；所有候选继续通过既有 certify。
- RoutePlanningService 每 200 ms 最多推送一次进度，现有菜单/请求 lease 失效后不再发送。
- RouteNetwork 复用响应信道发送独立 planningProgress envelope，不将其解释为最终计划。
- RouteScreen 保留中间更新对应的 pending 请求；校验菜单、序号、目标签名，并按代次/修订号丢弃旧进度；展示中文阶段与毫秒耗时。
- 新增 SparseAmountsTest、SparsePreimageTest、RoutePlanningProgressTest。
- SCC 受限区域候选：ProductionGraphTopology 以迭代 Kosaraju 分解目标依赖闭包；SccCandidatePlanner 在凝聚图上先汇总进入区域的全部需求，再用合成消费者一次求解，最后按供应顺序拼接程序。
- 仅接受单输出、唯一生产者区域；副产物、替代生产者、不足的启动种子和超预算均放弃候选，继续通用求解。局部计算的外部供应是假设，不构成可行性证明；合成程序必须通过全网真实库存的既有 certify。
- 局部求解关闭递归 SCC 入口，状态与时间有界；不跨 CPU 拆单、不修改执行协议。保守保种不保证材料最优，候选未命中不产生缺料结论。
- 派发事务基础已接入原生与 AdvancedAE：ProductionJob.dispatch 固定作业/游标，CertifiedDispatchScope 按作业、样板、输入数组身份共享嵌套事务，只提交一次。正常 false 不推进；未知异常使程序 invalid 并上抛，不能伪装成可退款的拒收。
- 新增可选 CraftRouteDispatch.confirmAccepted 供应器持久所有权回执。明确回执后的 RuntimeException 清理失败仍返回成功，让原生完成待回收登记和任务扣减；日志限流。零输入仍只推进一次原生任务，未开放额外批量。
- 事务协议与同步回料边界：docs/certified-dispatch-ownership.md。未提前登记 waitingFor，同步未接收的产物必须由供应器保留重试；不宣称可以修复无回执的任意供应器异常。

## 验证

- 2026-10-29：test + routeFunctionalTest + build -x productionBenchmark --offline --no-configuration-cache，BUILD SUCCESSFUL。
- 日志：D:/pcl/versions/ATM10/tools/ae2vm-build/AE-Craftroute/build/aelis-implementation-gate.log。
- git diff --check 无空白错误。
- SCC 本轮门禁：test 与 routeFunctionalTest 各 651 项，0 failures / 0 errors；build 成功。日志：D:/pcl/versions/ATM10/tools/ae2vm-build/AE-Craftroute/build/aelis-scc-gate.log。
- 新增 10 个测试：深图不递归、SCC 拓扑序及无关区域隔离、取消、共享循环需求、两个循环区域、单区域多个受需求键、不同批量组合、无种子、副产物/替代生产者退出。候选测试独立重放实际库存。
- 派发事务本轮门禁：test 与 routeFunctionalTest 各 674 项，0 failures / 0 errors；build 成功。日志：build/aelis-dispatch-gate.log。新增 23 项所有权/执行/NBT 测试；原有 ProductionExecutionTest 也改走新事务路径。
- 独立真实服务端自测：原生 PASS task=rejectonce（请求方第一次拒收最终交付，非供应器 push 拒收）、AdvancedAE PASS task=advanced。回执分别保存在 build/aelis-dispatch-native-receipt.txt 与 build/aelis-dispatch-advanced-receipt.txt；运行日志为 build/aelis-dispatch-native-runtime.log 与 build/aelis-dispatch-advanced-runtime.log。
- 未进行游戏内视觉验收、真实整包性能基准、实际服务器进程重启或完整第三方共存矩阵。接受后异常、同步回料和未知异常的新增场景为模型/单元验证，未伪称真实供应器运行期验证。

## 尚未完成（不得标记为全部方案已实现）

1. SCC 候选已完成首版；更广泛的替代生产者/多输出区域优化和真实整包性能收益尚未验证。
2. 派发所有权事务基础已接入；第三方追加批量仍被隔离。额外抽料的共享保护视图/阶段份数限制、实际第三方供应器异常及同步回料联调仍未完成，不能据此开放批量。
3. 确认期短租约预留及多玩家、网格变化测试。
4. BigInteger 端到端订单、有限执行窗口、存档/网络迁移和精确交付协议。
5. 大网冷/热缓存性能测量，完整游戏内验收。

前一轮误认的 GTNH AE2IS 分层多 CPU 实现不是本项目来源。算法分区也不等于可安全跨 CPU 拆单。

## 2026-10-29 local.66 发布与本地安装

- 版本：1.13.19-local.66-dev。源码提交 faf6f60812362f0e7943896f382e9dbef938a04f，分支 wip/planner-vm-ui-2026-10-25，标签 v1.13.19-local.66-dev，已推送。
- GitHub 预发布：https://github.com/Hsunxl/AE-Craftroute/releases/tag/v1.13.19-local.66-dev。
- 发布附件：成品 683,813 字节、源码包 338,061 字节、SHA256SUMS-local66.txt；远端资产 digest 与本地逐项一致。
- 成品 SHA-256：e5692db6e97bc17e0424b9a5ae803363d4fa0bedd5aa4bd7b1bd81ad83616252。
- local.66 发布门禁：test 与 routeFunctionalTest 各 674 项，0 failures / 0 errors；build 成功，日志 build/release-local66-gate.log。
- 已安装：D:/pcl/versions/ATM10/mods/aecraftroute-1.13.19-local.66-dev_neoforge_1.21.1.jar，校验值与发布附件一致；mods 中仅一份 aecraftroute jar。
- 旧包备份：D:/pcl/versions/ATM10/mods-backup/local66-20261029-213653/aecraftroute-1.13.19-local.65-dev_neoforge_1.21.1.jar。
- 备份 SHA-256：aa9ea41711c3012a397a0b18013578081a6d9cb77752fc66b1bfb9dbe28c296c。备份目录 installation.json 保存安装清单。
- 用户确认运行中的 Minecraft 是其他实例；没有终止任何游戏进程。本轮没有启动 ATM10 客户端或声称已完成其游戏内验收。
