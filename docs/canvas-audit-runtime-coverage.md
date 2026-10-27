# 当前无头运行覆盖与尚缺证据（2026-10-27）

本文件是当前验收导航，不覆盖 local.59 原始审计，不代表总目标完成。完整过程见 `canvas-audit-implementation-progress.md`。

## A2：先限定真实安装的包装器，不能把模组名当包装器存在的证据

扫描实际安装的全部 jar 名称候选后，再单列原审计提到的五个模组。持久证据：`build/audit-installed-scaled-pattern-inventory.json`，包含实际 jar SHA256。

| 实际 jar | 名称以 Scaled 开头且包含 Pattern 的类 |
|---|---|
| ExtendedAE-1.21-2.2.41-neoforge.jar | 无 |
| AdvancedAE-1.6.12-1.21.1.jar | 无 |
| ae2lt-2.1. 1.jar | 无 |
| [应用能源：水晶科技] ae2cs-1.21.1-1.2.1.jar | 无 |
| extendedae_plus-1.6.3.jar | ScaledMolecularAssemblerPattern、ScaledProcessingPattern、ScaledProcessingPatternAdv |

这是类名候选清单，不证明其它模组不存在任意命名的虚拟/倍率样板。其它模组的独立执行器和 requester 也不因此被接管。不能把 ScaledPatternCompat（本模组工具类）的名称匹配当成实际包装器。

三种实际 ExtendedAE Plus 包装器：plain processing 已有真实 jar 单测；molecular 已有接口契约往返单测；advanced processing 已有真实加载器、实际样板、倍率6、稀疏输入1/2槽、提取和持久化自检。

新增 `scaled-processing-execution -PextendedAePlusSelftest`：附属原生 smart doubling 实际生成 ScaledProcessingPatternAdv×1（倍率6），真实供应器向熔炉顶部投料，熔炉使用隔离数据包的一tick测试烧炼配方，输出经真实漏斗进入ME接口并自动回网，由实际link所属AdvCraftingCPU交付给未注册requester。测试代码不搬运原料/成品。实测55ticks，交付gold_block×6，初始iron_ingot9个剩3个，ME无多余gold、熔炉输入/输出与漏斗均空，无certified挂接。日志 `build/audit-selftest-scaled-processing-execution.log`。

新增 `scaled-processing-plain-execution` 使用普通AE2处理样板，同样由原生smart doubling生成确切的 ScaledProcessingPattern（非Adv），通过相同物理链交付6个并通过相同守恒检查。两种processing任务还分别在同时加载NeoECO21.2.2、ExtendedAE2.2.41、ExtendedAE Plus1.6.3、AdvancedAE1.6.12的组合下通过。

这证明了两种 processing 的该物理链，不证明所有机器、稀疏槽形态或重启均已通过。

补充发现：非Scaled命名的StrictMolecularAssemblerPattern也构成真实包装层，名称扫描不完整。实际红例证实原codec丢失该层、把inner恢复为AECraftingPattern；现显式保存严格层、原始delegate与allowScaling/multiplierLimit，经公开wrap工厂重建。真实加载器绿例还通过ProductionJob持久化/remainingTasks验证。日志 `build/audit-selftest-scaled-strict-{red,green}.log`。

新增 `scaled-molecular-execution`：按安装jar真实结构校验放置3×3×3框架/墙/内部hybrid core，由附属自动形成Super Assembler Matrix并发布Strict样板，不手工构造cluster或替换计划。原生planner实际生成 ScaledMolecularAssemblerPattern(Strict(AECraftingPattern))×1、倍率6，矩阵真实合成交付iron_block6个、消耗iron_ingot54个，ME无剩余两种材料、实际link所属AdvCraftingCPU释放、无certified接管。NeoECO/ExtendedAE/ExtendedAE Plus/AdvancedAE组合下13ticks PASS，日志 `build/audit-selftest-scaled-molecular-native.log`。目前三种已安装Scaled包装器各有一条真实物理交付链；这仍不是所有配方/结构尺寸/并发或跨进程恢复验收。

此前手工强加 molecular 倍率给普通分子装配室的探针只交付1/6且正确FAIL，已经移除强制探针代码；不可把它恢复为生产兜底。后续优先沿附属模组实际生成的 processing 包装器贯通真实加工，而非手造计划把不支持倍率的机器硬拉进来。

## 当前必跑门禁

- `test routeFunctionalTest build -x productionBenchmark`：最近两套各579 tests、0 failures/errors。
- 当前代码重新运行 `paths/growth/cancel/reload`：四个 fresh receipts 均PASS，日志与回执 `build/audit-required-*`。
- `advanced -PneoEcoSelftest`：实际NeoECO21.2.2 + AdvancedAE1.6.12组合，挂接并交付3、CPU释放。
- `foreign-advanced -PneoEcoSelftest`：实际原生CraftingPlan、真实link所属AdvCraftingCPU，交付1、CPU释放，不接管第三方规划。
- `advanced-restart1`→新服务端进程`advanced-restart2`，以及强化后的`restart1`→新进程`restart2`（均加NeoECO）：真实ME接口自行请求并自动持久化链接；两类CPU均按原始UUID定位、恢复certified program并继续，最终接口精确收3个钻石、ME网络0个、CPU释放。sidecar仅记录预期UUID作核对，不重建requester或链接。日志 `build/audit-{advanced-restart1,advanced-restart2,restart1,restart2}-machine.log`。

## 不能据已有绿灯消除的验收空白

- A1：真实ATM10大画布是否超过128分支/24000字符；边界单测不证明真实规模够用。
  已复现并修复深度24合法节点被规划器强造第25层地址而误拒绝的问题；25个真实配方节点的链在目标1/100000下均完成证明，缺末端原料仍UNREACHABLE、深度25仍INVALID。此为模型边界，不替代真实整合包画布规模采样。
  apply报文超限时现在仅省略可选名称n，保留全部k/c/p约束；128分支长名称夹具及真实Request CODEC往返验证通过。129分支或省略名称后仍超24000均明确拒绝，不截断/不放宽约束。该压缩不是实际大画布客户端验收。
- A2：三种已安装包装器已各有代表性物理交付；其它配方/稀疏槽、机器/结构尺寸与跨进程恢复覆盖不能由这些结果推定。
- A3：所有附属输入选择形态的实际提取一致性；已证部分多候选行为不等同全部形态。
- A4：已新增实际NeoECO/AdvancedAE组合运行证据；不扩张为全部ATM10附属组合通过。
- A5：真实整合包负载/并发容量；2线程+8槽的有界拒绝和恢复已经验证，但不足以证明容量够用。新增真实服务端planning-load一次提交32个请求，第33个准入LIMIT；立即取消8个后，10个成功、14个在worker queue阶段LIMIT，全部终止；随后新请求成功并经真实CPU交付1。此为真实小网络突发负载，不是全ATM10大型网络吞吐结论。
- C项：代码/无客户端测试不能代替画布实际布局、悬停、缩放等客户端验收。用户目前选择不操作客户端，继续代码和服务端工作。
