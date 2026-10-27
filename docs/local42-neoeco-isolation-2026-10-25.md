# local.42：NeoECO 外部 CPU 批量旁路隔离

## 复现证据

- 玩家在 local.41 上复现第一次订单停滞，目标为 1000 个数据粉。停滞诊断显示 CPU 在线、未暂停、未取消，认证游标持续停在 `block=0, step=0, completed=0`。
- 只读解析该次保存的 CPU NBT：CPU 库存已有 1312 个沙子；`waitingFor` 为空；原生任务表中的 EMC 转化任务已移除，但认证程序仍从 EMC 生成沙子开始，未记录任何执行进展。
- NeoECO 21.2.2 的 `ECOExternalCpuFastPath.execute` 在 CPU `executeCrafting` 的 HEAD 注入中执行批量任务。它直接提取输入、向供应器提交批次并减少原生任务次数，不经过 AE-Craftroute 位于正常 `executeCrafting` 内的逐次提取/接收钩子。
- 因此沙子并非未回 CPU；是旁路完成了沙子任务，认证游标却没同步。取消后重下单时，已有沙子可直接作为起手库存，从而避开这个触发条件。

## 最小修复

新增软依赖接口 `CertifiedExecutionOwner`，由原生和 AdvancedAE 的生产执行桥实现，状态来自当前真实 `ProductionJob`，不是 CPU 类名或整个模组的开关。

新增可选 `@Pseudo` Mixin，在 NeoECO 外部 CPU 批量执行入口检查此标记。认证程序存在时返回 0，表示快路径未接管；NeoECO 调用方继续正常 CPU 派发。非认证订单完全放行。没有增加材料、补做交付、重写任务数量或猜测恢复游标。

NeoECO 未安装时保持可选；工程的专用测试开关 `-PneoEcoSelftest` 仅为独立测试实例加载现有 NeoECO/LDLib2，不把它们嵌入成品。

## 验证

- 全量单元回归与 `-Xverify:all` 功能回归各 368 项，失败 0、错误 0。
- 新增测试覆盖认证持有者拦截、普通订单/非持有者放行、原生与量子 CPU 状态标记和已安装 NeoECO 的方法 ABI。
- 加载实际 NeoECO 21.2.2、LDLib2、Thunderbolt 与 AdvancedAE 的两个独立服务器场景均通过：`growth`（18:13:34）、`advanced`（18:14:08），各交付 3 个目标。
- 两场景分别记录了实际原生/量子 CPU 的 `external batch bypass blocked before task access`，证明可选 Mixin 已应用且在读取/修改认证任务前退出。日志为 `logs/local42-neoeco-selftest-growth.log`、`logs/local42-neoeco-selftest-advanced.log`。
- 18:15:25 未加载 NeoECO 的独立 `growth` 场景同样通过，交付 3 个目标，验证软依赖目标缺失时不会阻断启动或执行。
- 测试工具在实际 Mixin 转换后的 NeoECO 类上调用入口，传入真实持有认证程序的 CPU 与任何方法访问都会失败的测试 job，要求在访问原生任务前返回 0，然后让真实合成继续到交付。该工具不进入成品 jar。

## 升级边界

已受影响的旧卡单，其原生任务表和认证游标不一致，不能安全猜测已执行数量。升级后应取消该订单并重新提交；本修复保证新订单不再由这条旁路脱离认证协议。真实完整 ATM10 的游戏内复测仍与独立服务器验证区分。
