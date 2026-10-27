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

- `test routeFunctionalTest build -x productionBenchmark`：最近两套各606 tests、0 failures/errors（D15 元数据校验、恢复路径加固、画布请求数量上限、画布报文严格解析、certified 模糊取料与样板身份回归锁新增单测后由579升至606）。
- 画布请求数量上限（2026-10-27）：服务端原只拒绝 `amount<=0` 就采信客户端的 `long`，改过的客户端可送超大需求，把失败推进规划器深层的 `Math.*Exact`，只留下通用“规划失败”。现由 `RouteRequestRules.requirePlanAmount` 统一校验 `[1, RouteGraphMath.MAX_DEMAND=1_000_000_000_000]`，超限直接返回可读拒绝而非静默夹取。真实服务端 `vanilla-canvas`、`vanilla-canvas-advanced`、`paths` 回归 PASS。
- D15 反馈元数据：真实隔离服务端 `legacy-feedback[-advanced]-marker-corrupt`（标记写成字符串）、`...-heldtype-corrupt`（held 写成字符串）、`...-corrupt`（缺 held）、`...-oldkeys`（改写成未重命名的 `ae2vm*` 键）均 PASS：非法元数据一律取消任务且只回真实 held=2；旧键存档仍能恢复并完成交付。合法 `...-reload`、`...-seed-rejectonce`、`legacy-feedback`、`legacy-feedback-control`、`foreign-advanced` 同步回归 PASS。此项不覆盖其它模组自有反馈元数据。
- D15 恢复路径健壮性（2026-10-27）：存档 job 的样板运行计数若为负、求和溢出或超过 `MAX_TASK_RUNS=1_000_000_000_000`，在 `ledger.restore` 之前即被 `requireSaneRuns` 拒绝并取消，避免 `potentialInput` 饱和成 `Long.MAX_VALUE` 导致 `offerRelease` 永久 `Release(0,0)`、CPU 持信不完工。释放路径改用 `pendingInputOrUnknown`（不可解析槽=继续持有而非取消），非延迟 insert 预判对 null 槽容忍。job 标记要求 TAG_COMPOUND，且原生丢弃 job 时记录掉信日志。上列 16 个真实服务端 legacy-feedback 变体在新逻辑下全部 PASS。
- 画布报文严格解析（2026-10-27）：`RouteCanvasPayload.decode` 取代 `planAsync` 里的裸 Gson 读取，畸形 `k/c/n/p`、非对象分支项、非数字 `amount` 一律给出专属消息而不是泛化「操作失败」；缺失字段仍交给 `RouteApply.prepare` 拒绝，不放宽任何约束。`vanilla-canvas`/`vanilla-canvas-advanced`/`paths` 真实服务端回归 PASS。
- certified 模糊取料（2026-10-27）：`ProductionJob.extract` 现按原生 `getValidItemTemplates` 语义先取精确 key、再遍历 `findFuzzyTemplates` 并只接受 `input.isValid` 的候选，槽位不足时整体回滚按等料处理。此前只认精确 key，会让可替换/模糊族样板在等价库存上永久停摆。`growth`/`paths`/`vanilla-canvas` 真实服务端回归 PASS。
- certified 任务身份（2026-10-27）：`validateRemainingTasks` 与 `allows` 保持样板自带的 `equals` 语义，不放宽成「类名+可见输入输出相同」的结构比较——容器返还、替换规则与供应器行为不在可见字段里。第三方样板若未实现 `equals`，重载时会 fail-closed 取消（库存不动、材料回 ME、重新提交即可），这是接受的边界，不作为缺陷修。不一致日志现带 `certified=N actual=M` 与最多 4 条 want/have 类名计数。门禁 `test routeFunctionalTest build -x productionBenchmark` PASS，真实服务端 `reload` RESULT=PASS ticks=168。
- 增长环启发式可诊断（2026-10-27）：`TwoNodeGrowthPlanner` 在「歧义双环」与「计数溢出」两种放弃建模的情形下打 debug 级 `[AE-Craftroute] Feedback ring not modelled: …`，判定与重排行为不变；新增 `ambiguousRingsAreDeclinedInsteadOfGuessed` 锁住「不猜顺序」。门禁两组各 606 tests，真实服务端 `growth`/`legacy-feedback`/`advanced` PASS。
- 当前代码重新运行 `paths/growth/cancel/reload`：四个 fresh receipts 均PASS，日志与回执 `build/audit-required-*`。
- `advanced -PneoEcoSelftest`：实际NeoECO21.2.2 + AdvancedAE1.6.12组合，挂接并交付3、CPU释放。
- `foreign-advanced -PneoEcoSelftest`：实际原生CraftingPlan、真实link所属AdvCraftingCPU，交付1、CPU释放，不接管第三方规划。
- `advanced-restart1`→新服务端进程`advanced-restart2`，以及强化后的`restart1`→新进程`restart2`（均加NeoECO）：真实ME接口自行请求并自动持久化链接；两类CPU均按原始UUID定位、恢复certified program并继续，最终接口精确收3个钻石、ME网络0个、CPU释放。sidecar仅记录预期UUID作核对，不重建requester或链接。日志 `build/audit-{advanced-restart1,advanced-restart2,restart1,restart2}-machine.log`。

## 不能据已有绿灯消除的验收空白

- A1：真实ATM10大画布是否超过128分支/24000字符；边界单测不证明真实规模够用。
  已复现并修复深度24合法节点被规划器强造第25层地址而误拒绝的问题；25个真实配方节点的链在目标1/100000下均完成证明，缺末端原料仍UNREACHABLE、深度25仍INVALID。此为模型边界，不替代真实整合包画布规模采样。
  apply报文超限时现在仅省略可选名称n，保留全部k/c/p约束；128分支长名称夹具及真实Request CODEC往返验证通过。129分支或省略名称后仍超24000均明确拒绝，不截断/不放宽约束。该压缩不是实际大画布客户端验收。
  静态规模采样（2026-10-27，只读扫描 mods 557/557 jar + kubejs + 存档 datapack，85,703 个配方、两轮交叉核对）：250 个终局候选中 62 个依赖闭包 >128（最大 1,525），最宽配方 BFS 在深度 8–12 跨过 128 节点（均 < MAX_PATH_DEPTH=24）；按 encode 真实形状模拟 128 节点前缀为 17,135–21,562 字符，全部 ≤24,000 ⇒ 实测中分支上限先于字符上限成为约束。这是静态证据，仍不等于运行期真实画布分支数观测；建议的下一批 canvas-scale 真实服务端 128 节点自测 + 129 拒绝负例见 progress 文档。
- A2：三种已安装包装器已各有代表性物理交付；其它配方/稀疏槽、机器/结构尺寸与跨进程恢复覆盖不能由这些结果推定。
- A3：所有附属输入选择形态的实际提取一致性；已证部分多候选行为不等同全部形态。
- A4：已新增实际NeoECO/AdvancedAE组合运行证据；不扩张为全部ATM10附属组合通过。
- A5：真实整合包负载/并发容量；2线程+8槽的有界拒绝和恢复已经验证，但不足以证明容量够用。新增真实服务端planning-load一次提交32个请求，第33个准入LIMIT；立即取消8个后，10个成功、14个在worker queue阶段LIMIT，全部终止；随后新请求成功并经真实CPU交付1。此为真实小网络突发负载，不是全ATM10大型网络吞吐结论。
- C项：代码/无客户端测试不能代替画布实际布局、悬停、缩放等客户端验收。用户目前选择不操作客户端，继续代码和服务端工作。
