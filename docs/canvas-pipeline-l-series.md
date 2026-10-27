# 画布管线 L1–L14 当前状态（2026-10-27）

> 本文档由当前**未提交工作树**（uncommitted working tree）逐行实读源码生成，生成日期 2026-10-27。
> 历史快照 `docs/canvas-planner-executor-audit-local59.md` 仅为历史记录：其中 L 表格的行号、D1/D4/D5/D7/D8/D21 等发现所依据的代码状态均已过时，本文档不沿用其行号。
> 现行权威文档是 `docs/canvas-audit-implementation-progress.md`（修复与证据进度）与 `docs/canvas-audit-runtime-coverage.md`（必跑门禁与验收空白）。本文只做管线导航，不替代二者。
>
> 源码基准路径：`tools/ae2vm-build/AE-Craftroute/src/main/java/com/aecraftroute/addon/`（下文行号均相对该前缀）。
> 本轮未运行 Gradle / Minecraft / 任何构建，未启动或操作客户端；每条 file:line 均来自本次实读。

## 管线总览

| 阶段 | 当前代码位置 | 侧 |
| --- | --- | --- |
| L1 画布采集 | route/client/RouteScreen.java:977-995 | **客户端（未验收）** |
| L2 请求上行 | route/client/RouteScreen.java:784-810 | **客户端（未验收）** |
| L3 动作分派 | route/RouteNetwork.java:51-173 | 服务端 |
| L4 应用校验 | route/RouteApply.java:37-60 | 服务端 |
| L5 策略构造 | route/RouteApply.java:58 | 服务端 |
| L6 规划分派 | route/RoutePlanningService.java:276-280 | 服务端 |
| L7 强制画布规划 | route/RouteCanvasPlanner.java:11-116 | 服务端 |
| L8 求解与证明 | route/ForcedRoutePlanner.java:21-94 | 服务端 |
| L9 证明结果落策略 | route/RouteNetwork.java:224-229 | 服务端 |
| L10 会话判定 | mixin/CraftingServiceMixin.java:120-180 | 服务端 |
| L11 CPU 派发 | compat/CertifiedCpuDispatcher.java:22-139 | 服务端 |
| L12 执行桥 | mixin/ProductionCpuMixin.java:36-120 | 服务端 |
| L13 交付 | mixin/ProductionCpuMixin.java:122-166 | 服务端 |
| L14 进度上行 | **不存在**（route/RouteView.java:51-52 Reply 无执行进度字段） | — |

---

## L1 画布采集（客户端）

- **当前位置**：`route/client/RouteScreen.java:977-995`（`applyCanvas`；历史快照记为 :957-980，已漂移）。
- **作用**：把画布上已展开的节点逐个收集为 `RouteApply.Node(targetKey, choiceId, name, path)`，编码为 `argument` 后交给 `send("apply", …)`（:994）。
- **当前校验/守卫**：
  - :978 `if (!canModify)` → 状态行「请从自动合成确认界面进入修改模式」并返回，非修改模式不采集；
  - :979 `target == null` → 「先设置最终产物，再整幅应用」；
  - :984 跳过 `choice == null || choice.id().startsWith("plan:") || node.material.key == null` 的节点并累计 `skipped`，不把占位节点送服务器；
  - :987 `nodes.isEmpty()` → 「画布上还没有可应用的配方；先展开材料并选择样板」；
  - :989-990 `RouteCanvasPayload.encode` 包在 try/catch `IllegalArgumentException` 内，超限错误原文显示到状态行，不发包。
- **证据指针**：progress :202-206（encode 作为 apply 生产序列化入口、分支/字符限额共享到客户端 send、"不冒充已经通过客户端按钮发送过128分支压缩报文"）；progress :61（隔离客户端 QA 只做过 apply 回包文案，未做像素验收）。
- **验收状态**：**未验收 / 需用户授权**。客户端从未被本轮或证据文档中的验收操作；coverage :57 明确代码与无头测试不能替代画布布局/悬停/缩放的客户端验收。

## L2 请求上行（客户端）

- **当前位置**：`route/client/RouteScreen.java:784-810`（`send`；历史快照记为 :769-793）。
- **作用**：校验本地状态后以 `new RouteNetwork.Request(menuId, sequence, action, key, argument)` 经 `PacketDistributor.sendToServer`（:807）上行，并用 `pending` 记录 seq 以匹配回包。
- **当前校验/守卫**：
  - :786-787 menu/containerId 不匹配 → 「终端已关闭，请重新打开路线画布」返回 0；
  - :790-795 workbench 类请求的在途去重；
  - :796-798 mutation 类请求已有在途 → 「上一项操作尚未完成」；
  - :799 `argument.length() > RouteCanvasPayload.MAX_CHARACTERS`（=24000，常量引用而非历史的字面量比较）→ 「配方数据过大，无法编码」返回 0；
  - :800 `int sequence = ++nextSequence`；:801-804 preview/apply 先清掉过期 pending 与 `latestPlanSequence`；:805 `pending.put`。
  - 同文件 `maybeRequestPreview` :955-968：preview 去重门以 reply/过期释放（注释 :959-963 记录旧"已发送即挡"会永久卡死的缺陷已改），:966 `send("preview", target, "{"amount":…}")`。
- **证据指针**：progress :203-204（限额共享到客户端 send、RouteScreen 在加入 pending/发送前处理错误）；coverage :39（服务端严格解析通过不等于客户端发送行为已验证）。
- **验收状态**：**未验收 / 需用户授权**（同 L1）。

## L3 动作分派（服务端）

- **当前位置**：`route/RouteNetwork.java:51-173`（`handle`；历史快照记为 :60-110）。Request 记录 :26-37，CODEC :32-35。
- **作用**：按 `action` 把请求分流：bypass 类（workbench/encode/takecache/upload/discardcache）在 :64-84 直接处理，preview/apply 交 `planAsync`（:87-89），mutation 类先 `cancelPlanning`（:90），其余策略开关在 switch :99-123 就地改写并回包。
- **当前校验/守卫**：
  - **参数长度上限在构造器** :28-29：`apply/encode → RouteCanvasPayload.MAX_CHARACTERS（24000）`、`workbench|takecache|upload|discardcache → 1024`、其余 → 128，否则 `IllegalArgumentException`「路线操作参数超过限制」；CODEC :33-34 `readUtf(MAX_CHARACTERS)`、action `readUtf(16)`，Response :41-42 `readUtf(200000)`；
  - :54-58 无效 menu → 「操作失败：终端会话已失效，请重新打开终端」；:60-63 无 host/grid → 「终端没有连接可用的 ME 网络」；
  - switch :99-123：:115 代理关闭时拒绝需代理的操作（「AE-Craftroute 的自动合成没有开启」）、:116-118 样板身份不匹配（「这张样板已经变了，请重新打开页面再选」）、:122 默认「未知路线操作」；
  - **D21 已修复**：:124 `if (changed) RouteSessions.put(...)`——只在策略真的变化时写会话（历史为 :107 无条件 put）；:126 变化且处于 CraftConfirmMenu 时 `setAutoStart(false)+replan()`；
  - choices 分支 :137-164：:142 上限 64、:152 序列化体 >170000 截断；:169-171 兜底「操作失败：」+ `RouteFailureText.message`。
- **证据指针**：progress :36-37（D21 条件 put、D22 clearcanvas 语义）；coverage :36（amount 上限必跑门禁）、:39（报文严格解析门禁）。

## L4 应用校验（服务端）

- **当前位置**：`route/RouteApply.java:37-60`（`prepare`；历史快照记为 :37-51）。
- **作用**：对上行画布做纯校验（javadoc :36 明示 "Validation only: no session state is changed until a certified plan commits."），产出 `Prepared(policy, problems, locked)`，不写任何会话状态。
- **当前校验/守卫**：
  - :39 `nodes.size() > RouteCanvasPayload.MAX_NODES` → 「强制画布最多 128 个分支」；
  - :45-50 解码走 `RouteCanvasPayload.decode`，失败时**保留根因** `decodeFailure = cause.getClass().getSimpleName()+": "+cause.getMessage()`——历史 :43 的空 catch（D7）已修复；
  - :52-53 material 为空或 choiceId 空白 → 问题「「label」材料或样板标识无效（根因）」并跳过该节点；
  - :54 `!RouteGraphMath.validPath(node.path())` → 「「label」缺少有效路径，请重新打开画布」：路径正则已收敛到共享 `RouteGraphMath.validPath`（`route/RouteGraphMath.java:15-20`，`MAX_PATH_DEPTH = 24`，`root(?:/[0-9]{1,3}){0,24}`），历史 D37 记录的 RouteApply 内联正则深度不一致已消除；
  - :57 `paths.isEmpty()` → 「画布没有可执行的路径分支」。
- **证据指针**：progress :196-200（D37 共享 canExtendPath/validPath、深度 24 证成 / 深度 25 typed INVALID，日志 `build/audit-canvas-depth-red.log`、`build/audit-selftest-paths-depth-fix.log`）；progress :202-206（128 分支 / 24000 字符限额与压缩，`build/audit-selftest-paths-payload.log`）；progress :258-264（decode 七类畸形消息严格解析，`build/audit-decode-<task>.log`）；coverage :39（必跑门禁含严格解析）。

## L5 策略构造（服务端）

- **当前位置**：`route/RouteApply.java:58`（历史快照记为 :50）。
- **作用**：`candidate = problems.isEmpty() ? new RoutePolicy<>(base.rules(), new RoutePolicy.Canvas<>(target, amount, paths)) : base`——校验全过才把画布挂到**既有 rules 之上**（保留逐材料规则、只换 canvas），有问题则原样返回 base，不做半应用。
- **当前校验/守卫**：候选策略仅由无问题的 `prepare` 产出；`route/RoutePolicy.java:14` `empty() { return rules.isEmpty() && canvas == null; }`——会话"是否有约束"同时看 rules 与 canvas（D1 判定语义的正确基准）。
- **证据指针**：progress :78（无头 `vanilla-canvas` 对 `root` 执行 apply 得 `locked=[Block of Iron @ root]`、`verdict=feasible` 后真实 CPU 交付，`RESULT=PASS task=vanilla-canvas`）；progress :204（限额共享到 RouteApply/RouteNetwork/客户端）。
- 注：`RouteApply.apply()`（:61-103，走 RouteIntent 合并的遗留 API）本轮 grep 全树确认**仍无生产调用方**，仅 `selftest/SelftestHarness.java:872/939/1144/1184` 调用；生产链路是 `RouteNetwork.planAsync → RouteApply.prepare`（`route/RouteNetwork.java:197-198`）。

## L6 规划分派（服务端）

- **当前位置**：`route/RoutePlanningService.java:276-280`（`planFrozen`；历史快照记为 :251-255）。
- **作用**：:278 `if (policy.canvas() != null) return RouteCanvasPlanner.plan(snapshot, policy.canvas(), key, amount, limits);` else :279 `NetworkProductionPlanner.plan(...)`——canvas 非空即走强制画布规划，否则走常规网络规划。
- **当前校验/守卫**：入口 `planAsync` :259-275 有准入背压——:266 `JOBS.size() >= 32` → LIMIT「规划队列已满；不是缺料」；`planFrozenAvoidingUnstable` :285-301 处理不稳定快照重试。
- **证据指针**：progress :208-212（`planning-load`：真实 planAsync 32 请求、第 33 个 typed LIMIT、取消前 8 → passed10 + worker-queue LIMIT14 + admission LIMIT1，排空后恢复交付铁块 1，日志 `build/audit-selftest-planning-load.*`）；progress :142-145（2 线程/8 槽满载回归，`build/audit-worker-saturation-targeted.log`）；coverage :56（A5 仍开放）。

## L7 强制画布规划（服务端）

- **当前位置**：`route/RouteCanvasPlanner.java:11-116`（`plan`；历史快照记为 :11-110，文件现为 132 行）。
- **作用**：把 canvas policy 的路径集合展开为分支需求与精确输入分配，构造 `CertifiedCraftingPlan`（proof + bytes）。
- **当前校验/守卫**：
  - :13-14 目标/数量与 canvas 不一致 → INVALID「强制画布只适用于应用时的目标与数量；请重新应用画布」；:15-16 截止期限；
  - :20-21 每样板过 `ScaledPatternCompat.validate` → UNSUPPORTED「样板包装无法安全解析：」；
  - :30 路径非法或重复 → INVALID「画布路径无效或重复」；:31-37 choiceId 与冻结网络按身份匹配失败 → INVALID「路径 X 的样板已失效或被规则禁止」；:40-41 缺 root 或目标不符 → INVALID「必须包含最终产物的 root 路径」；
  - 需求推导 :43-65：root=amount、按路径长度排序、:48 孤立路径 INVALID「孤立路径：…；请展开并应用其父配方」、:52 `runs = 1 + ((demand-1)/output)`、:57 `if (!RouteGraphMath.canExtendPath(path.path())) continue`——只跳过协议表示不了的 UI 子地址、保留物理输入（D37/A1 注释 :54-56）、:62 输入材料已变化 INVALID「路径输入材料已变化：」、:63 `Math.multiplyExact`；
  - :66-78 配方枚举（:75 `PatternVariants.enumerate`、:77 `recipes.size() > 8192` → LIMIT「强制画布替代分配超过预算」）；:79-89 分支精确分配（:86 `r.id().pattern() == pattern` 引用相等 + inputs 相等，D13 fail-closed；:87 分配建模不了 → UNSUPPORTED，注释 :83-84 明示不得静默消费模糊替代料）；
  - :99-115 bytes 计算只累计**物理**样板次数（注释 :103-104：AE2 按每次物理样板触发记 1 byte，虚拟标记/压缩块从不执行），:112 `new CertifiedCraftingPlan(..., Math.max(1, bytes), used, counts, proof.program())`，:113-114 溢出 → LIMIT「强制画布的 CPU 任务数量超过整数容量；未提交部分程序」；
  - :127-130 `check`：中断 → `CancellationException`；过期 → LIMIT「端到端规划预算已用尽；不是缺料」。
- **证据指针**：progress :196-200（深度边界修复与 depth24/25 回归）；progress :202-206（128 分支压缩与拒绝回归）；coverage :50-52（A1：真实 ATM10 画布规模未验收）。

## L8 求解与证明（服务端）

- **当前位置**：`route/ForcedRoutePlanner.java:21-94`（`solve`；历史快照记为 :21-93，文件现为 96 行）。
- **作用**：class javadoc :9-12 说明有限画布是**最小物理执行契约**（不是材料过滤器）；以虚拟 goal 标记 :41-42 + `ProductionPlanner.solve(goal, 1)` :43 借共享库存证成全部分支，再对物理程序做独立 BigInteger 重放证明。
- **当前校验/守卫**：
  - :23 `increment<=0 || branches.isEmpty()` → INVALID「画布没有强制分支或目标数量无效」；:31-32 重复路径 → INVALID「重复的画布路径：」；:36 配方不在冻结网络 → INVALID「路径样板不在冻结网络中」；
  - :44-46 求解非 FEASIBLE → 预算内未判定 LIMIT「强制画布程序在预算内未判定；不是缺料」，否则 UNREACHABLE「共享库存无法执行所有强制分支并达到目标净增量」；
  - :50-51 分支次数 < minimum → UNSUPPORTED「内部证明不一致：强制分支未完成」；:52-56 剥离虚拟标记得到物理程序块；
  - :57-83 用 `BigInteger` 独立重放物理程序（前缀汇总、不展开循环）；:85-88 任一 key 所需 > 起手库存 → UNSUPPORTED「…起手库存被重复使用」；:90-91 净增量 < 目标 → UNSUPPORTED「…目标未达到净增量」；
  - :93 `ArithmeticException` → LIMIT「强制画布数量超过整数容量；未提交部分程序」。
- **证据指针**：progress :78-79（certified 计划交真实 AE2 / AdvancedAE CPU 各交付铁块 1，`RESULT=PASS`）；coverage :35（606 项无头门禁 0 失败）、:46（重启对 interface 持有 3 钻石、ME 0、CPU 释放）。

## L9 证明结果落策略（服务端）

- **当前位置**：`route/RouteNetwork.java:224-229`（`planAsync` 的 `whenComplete` :212-233 内；历史快照记为 :209-214）。
- **作用**：**只有 `apply && view.proven()` 才提交策略**——:224 `if (apply && view.proven()) {`，:225 注释 `// Atomic: no policy writes before proof and final snapshot validation.`，:226 `RouteSessions.put(grid, player.getUUID(), prepared.policy()); effective = prepared.policy();`，:227 `confirm.setAutoStart(false); confirm.replan();`，:228 回执「已应用强制画布程序：N 个路径分支（各分支至少执行画布批次，可继续循环增产） · …」；:229 `else if (apply) message = "未应用任何路线改动 · " + message;`——未证成一律不写。
- **当前校验/守卫**：提交前还经过 :179-180 PLANNING 票据、:186-188 `RouteCanvasPayload.decode` + `RouteRequestRules.requirePlanAmount`（[1, MAX_DEMAND=1_000_000_000_000]，只拒绝不夹取）、:191 `requirePlanningEnabled`、:192-196 apply 时目标/数量须与 CraftConfirmMenu 一致、:197-199 `prepare` 问题集、:201-208 `valid()` 快照/菜单/代理/会话一致性复核；:212-233 中 :213 失效即丢弃、:219 `CancellationException` 直接返回。
- **证据指针**：progress :78（apply → verdict=feasible → 真实 CPU 交付的端到端无头证据）；progress :250-256（`requirePlanAmount` 上限与拒绝语义，`build/audit-amount-<task>.log`）；coverage :36（amount 门禁必跑）。

## L10 会话判定（服务端）

- **当前位置**：`mixin/CraftingServiceMixin.java:120-180`（`vmBeginCraftingCalculation`；历史快照记为 :127-186）。
- **作用**：按请求者会话决定走隔离的路线计算还是原生无约束合成；`submitJob` 包装 :69-117 里 :76-82 让 `CertifiedCraftingPlan` 短路到 `CertifiedCpuDispatcher`。
- **当前校验/守卫**：
  - :132 未注册第三方 requester → 直接返回（交原生链）；
  - **代理关闭分支 :134-140**：`if (!CraftRouteConfig.isProxyEnabled()) { if (!RouteSessions.get(grid, simRequester).empty()) cir.setReturnValue(failedFuture(new RoutePlanningException("AE-Craftroute 已关闭；请清除手动约束或重新开启，未绕过约束"))); return; }`——判定用 `RouteSessions.get(...).empty()`，**同时覆盖 rules 与 canvas**（D1 已修复；历史两处判定只看 `rules()`，canvas-only 会话会失去失败隔离）；
  - **:142-156 隔离路径**：注释 `Manual requests have an isolated failure path: NEVER fall back to unrestricted AE2.`；有会话 → `CraftRouteCrafting.calculate(...)` 走独立失败通道，错误提示 `[AE2 路线] +cause`，不回落无约束 AE2；
  - :158-179 默认无会话路径（原生）提示 `[AE2 合成] +cause`；:83-117 离线 CPU 重试循环中 :106-107 遇 MISSING_INGREDIENT/INCOMPLETE_PLAN 立即停止，不吞规划失败。
- **证据指针**：coverage :40-41（certified 模糊取料、样板保持 IPatternDetails.equals 身份、reload PASS 168 ticks）；progress :274-288（身份 fail-closed 与 `PatternIdentityTest`）、:74（growth/cancel/reload/rejectonce PASS）。

## L11 CPU 派发（服务端）

- **当前位置**：`compat/CertifiedCpuDispatcher.java:22-139`（历史快照记为 :21-92，文件现为 140 行）。
- **作用**：certified 计划的 CPU 选择与提交：先原生直提 :47-58，失败只在**可改投类别**内找替代 CPU。
- **当前校验/守卫**：
  - :25 仅当 `canReroute(errorCode)`（:37-41：CPU_OFFLINE/CPU_BUSY/CPU_TOO_SMALL/NO_CPU_FOUND/NO_SUITABLE_CPU_FOUND）才改投；:43-45 `isPlanFailure`（MISSING_INGREDIENT/INCOMPLETE_PLAN）**不改投**——D4 关联的"静默改投"已收紧为受限改投；:29-31 改投前有 warn 日志 `selected CPU cannot currently run certified program: …; trying compatible CPU`；
  - :71-72 原生集群须 `canBeAutoSelectedFor`；:75 `continue; // Never hand a certified program to Trinity/ECO/other protocols.`；
  - :89-102 `canAutoSelect` 反射查 AdvancedAE 自动选择许可，**失败即 fail-closed `return false`**（注释 :94-95：选择器缺失/失败不等于该 CPU 允许自动使用），对应历史 D4 的缺陷已消除；
  - :104-117 `advancedCluster` 沿**父类链**走到 `type.getName().equals("net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU")`（:107-108）再 `getDeclaredField("cluster")`（:111）——历史 D5 记录的"仅叶子类名精确匹配"现为层级遍历，任一步失败 → null :114-116；
  - :119-139 `submitAdvanced`：反射调用**之前**失败 → warn + `NO_SUITABLE_CPU_FOUND` 交其他 CPU（注释 :130）；`InvocationTargetException` → 直接抛出、**不重试另一 CPU**（注释 :136 "The method may already have started a job. Never retry on another CPU."）；:127 null 回值 → `IllegalStateException`。
- **证据指针**：progress :67-69（只改投兼容且可自动选择的 CPU、真实 fallback CPU_OFFLINE→CPU 交付 1、fallback-small CPU_TOO_SMALL→AdvancedAE 交付 1）；progress :75-76（反射边界定向测试，方法已调用后抛错不改投）；coverage :44-45（advanced / foreign-advanced 必跑门禁）。

## L12 执行桥（服务端）

- **当前位置**：`mixin/ProductionCpuMixin.java:36-120`（历史快照记为 :63-102）。
- **作用**：把 certified 计划挂到运行中的 CPU 并逐步驱动取料/接受/回库；类 javadoc :25 `Certified dispatch, actual-return feedback, then delivery. No ring detector.`
- **当前校验/守卫**：
  - :64-79 `trySubmitJob` RETURN：成功结果上 `if (plan instanceof CertifiedCraftingPlan certified) { aecraftroute$production = new ProductionJob(certified); DispatchTrace.certified("ae2"); } else { aecraftroute$production = null; }` + `markDirty()`，非 certified 不挂桥；
  - :81-91 `extractPatternInputs` 包装：当前步输入不可用时 warn `certified dispatch waiting: current step inputs unavailable or invalid`（限流），不跳步；
  - :93-103 `pushPattern` 包装：`if (production != null && !production.allows(pattern)) return false;`——只允许当前步声明的样板（D13 身份门），被拒 → warn `certified dispatch refused by provider {} for the current step`；
  - :105-120 `insert` 包装先 SIMULATE 再 MODULATE；注释 :118 `Delivery is deliberately deferred until tick TAIL, never re-entrant in pushPattern.`——交付不在 pushPattern 内重入；
  - :48-62 `watchStall`：30 秒停顿 warn `certified job stalled 30s: position=…`（限流）。
- **证据指针**：coverage :40（`ProductionJob.extract` 模糊取料门禁）、:43（paths/growth/cancel/reload fresh receipts `build/audit-required-*`）；progress :74-79（增长/取消/重载/拒收重试与两种 CPU 家族无头 PASS）。

## L13 交付（服务端）

- **当前位置**：`mixin/ProductionCpuMixin.java:122-166`（`tickCraftingLogic` TAIL `deliver`；历史快照记为 :130-160）。
- **作用**：程序完成时按**真实库存**把最终产物交给 requester，余量记回 CPU 输出并结束任务。
- **当前校验/守卫**：
  - :124 无效 production → `cancel()`；:126 `production==null || job==null || !production.done()` 返回；:128 任务取消/挂起/集群不活动返回；注释 :131-133 `Goal satisfaction is based on real stock after retaining startup capital`；
  - :134 `held = production.deliverableAmount(...)`；:135-139 `held <= 0` → 限流 warn `AE2 CPU delivery pending: no … in the CPU inventory yet` 等待，不视为完成；
  - **D8 已修复**：:141 `link.insert(...)`；:146 `if (accepted <= 0 && link.isStandalone())` 才在 :150 改投 ME 网络（`toNetwork = true`），注释 :143-145 说明链接型 requester 可能只是本 tick 暂不可用或拒收，**均不允许重定向到 ME 存储**；:147-148 注释玩家发起的任务没有 requester link；
  - :153-157 链接型 `accepted <= 0` → 限流 warn `AE2 CPU delivery blocked; retained … for retry` 并保留重试（不再扣减 remaining）；:160-165 `inventory.extract` 后 `remaining -= accepted`，`remaining == 0` → `finishJob(true)` + `cluster.updateOutput(null)` + 清空 production，否则 `updateOutput(new GenericStack(key, remaining))`；
  - 存档 :171-207：`writeToNBT` 遇 SAVE_FAILED_TAG → `Cannot persist certified CPU program; saved job will be canceled on reload`；`readFromNBT` :199 `validateRemainingTasks(remaining)` 失败 → :202 `cancel()`（fail-closed）。
- **证据指针**：progress :81（partial / overestimate / advanced-overestimate 交付边界 PASS）；progress :307-309（r18 横扫 foreign/fallback/partial/overestimate/stock-race/rejectonce 与七个 scaled 场景全绿，`build/audit-r18-<task>.log`）；coverage :46（重启对交付 3 钻石、ME 0、CPU 释放）。

## L14 进度上行

- **当前位置**：**不存在**。回包载体 `route/RouteView.java:51-52`：

  ```java
  public record Reply(String message, List<Choice> choices, String locked, List<String> banned,
      boolean stockOnly, int ruleCount, boolean truncated, RoutePlanView plan,
      CanvasChoices canvas, RequestTarget requestTarget)
  ```

- **作用/现状**：`RouteNetwork.reply()`（:239-252，:243-244 附 canvas、:245-249 附 requestTarget、:251 GSON 序列化）只回送规划视图与会话视图，**没有任何执行进度字段**（进度、剩余、tick 计数均无）。与历史快照结论一致：L14 仍缺失，这不是回归而是既定空白。
- **证据指针**：无对应绿灯；coverage 全文的必跑门禁 :33-46 与空白 :48-57 均未列入"进度上行"，可视为未列入验收范围的既存缺口。

---

## 仍开放的验收空白（与 canvas-audit-runtime-coverage.md 对齐）

1. **A1 真实画布规模**（coverage :50-52）：已证 depth24=25 节点在 amount 1/100000 证成、depth25 typed INVALID；>128 分支与压缩后仍 >24000 的报文被明确拒绝、仅省略可选名称 `n`；**这些都不等于真实 ATM10 客户端画布不会触发上限**。证据：progress :196-206。
2. **A2 其余尺寸与并发组合**（coverage :53）：三种已安装 Scaled 包装器各有一条真实物理交付链（r18 后七个 scaled 场景绿，progress :307-309），但其余尺寸/并发组合未覆盖。
3. **A5 整包容量**（coverage :56）：已证 2 线程/8 槽在 32 请求突发下 typed 背压、取消、终止与排空恢复（planning-load），**不代表真实整合包吞吐/容量验收**。证据：progress :142-145、:208-212。
4. **全部客户端视觉/交互验收（需用户授权）**（coverage :57、progress :139）：L1、L2 及 RouteScreen 的画布布局、悬停、缩放、按钮像素行为**从未被操作**；progress :61 的隔离客户端 QA 只验证了回包文案，progress :78 明示无头 apply→交付链"仍不冒充按钮像素级验收"。本轮同样未启动客户端，**不作任何客户端侧验证声明**。

## 附：历史快照与当前代码的行号漂移

| L | 历史 local59 行号 | 当前行号 | 主要差异 |
| --- | --- | --- | --- |
| L1 | RouteScreen.java:957-980 | :977-995 | 采集逻辑移位；空选择跳过与 encode 错误回显为现状 |
| L2 | RouteScreen.java:769-793（:782 字面量 >24000） | :784-810（:799 用 `MAX_CHARACTERS` 常量） | 限额共享到常量 |
| L3 | RouteNetwork.java:60-110（:107 无条件 put，D21） | :51-173（:124 条件 put） | **D21 已修复** |
| L4 | RouteApply.java:37-51（:43 空 catch，D7） | :37-60（:45-50 保留根因；:54 共享 validPath） | **D7 已修复；D37 正则收敛** |
| L5 | RouteApply.java:50 | :58 | 行号漂移，语义一致 |
| L6 | RoutePlanningService.java:251-255 | :276-280 | 行号漂移 |
| L7 | RouteCanvasPlanner.java:11-110 | :11-116 | 新增 canonical 校验与 bytes 溢出守卫 |
| L8 | ForcedRoutePlanner.java:21-93 | :21-94 | 行号漂移，重放证明结构不变 |
| L9 | RouteNetwork.java:209-214 | :224-229 | `view.proven()` 条件提交保持不变 |
| L10 | CraftingServiceMixin.java:127-186（:139/:147 只看 rules()，D1） | :120-180（:134-156 用 `empty()` 同看 rules+canvas） | **D1 已修复** |
| L11 | CertifiedCpuDispatcher.java:21-92（D4 静默改投、D5 叶子类名） | :22-139（:37-45 受限改投、:89-102 fail-closed、:107-108 父类链匹配） | **D4/D5 已修复** |
| L12 | ProductionCpuMixin.java:63-102 | :36-120 | 附着/取料/接受/回库分段更细 |
| L13 | ProductionCpuMixin.java:130-160（D8 0 值双义） | :122-166（:146 仅 `isStandalone()` 才改投） | **D8 已修复** |
| L14 | 不存在 | 仍不存在（RouteView.java:51-52） | 无变化 |

另：历史 §2.3 记录的 `RouteApply.apply()` 无生产调用方，在当前树中依旧成立（本轮 grep 全树仅 `selftest/SelftestHarness.java` 四处调用）。
