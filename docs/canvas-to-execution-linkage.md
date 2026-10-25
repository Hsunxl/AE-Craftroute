# 画布 → 规划 → 执行：联动架构说明

> 代码基线：分支 `wip/planner-vm-ui-2026-10-25`，HEAD `dfa2aea`（local.47/48/49 改动未提交）。
> 本文只描述已存在于源码中的链路；未实现的部分在 §7 单独标注，不与已实现内容混写。
> 全部行号对应当前工作区文件，不是任何已发布版本的字节码。

## 0 一句话结论

联动的本质不是「把图交给执行层」，而是**把画布降级成一小组可证明的硬约束**。执行层永远只认一个对象：带可执行程序的 `ICraftingPlan`。画布的价值在于把「用哪张样板、用在哪个路径位置」表达成规划器能证明、执行器能逐槽校验的约束；图形、坐标、折叠状态都不出客户端。

```text
客户端画布                服务端                        规划层                  执行层
──────────                ──────                        ──────                  ──────
applyCanvas()  ──JSON──▶  RouteApply.prepare()
  {amount, nodes:           └─▶ RoutePolicy.Canvas(paths)
   [{k,c,n,p}]}                   │
                                  ▼
                          RouteNetwork.planAsync()
                            └─▶ RoutePlanningService.planAsync()
                                  └─▶ planFrozen()
                                        └─▶ RouteCanvasPlanner.plan()
                                              └─▶ ForcedRoutePlanner.solve()
                                                    └─▶ CertifiedCraftingPlan
                                                          (证明 + 程序)
                                                          │
                          RouteSessions.put()  ◀── proven() ─┘
                                  │
                                  ▼
                          CraftingServiceMixin.submitJob
                            └─▶ CertifiedCpuDispatcher
                                  └─▶ ProductionCpuMixin.trySubmitJob
                                        └─▶ ProductionJob + ProductionCursor
                                              └─▶ extract / allows / accepted
                                                    └─▶ TAIL deliver
```

## 1 六段链路与唯一入口

| 段 | 入口 | 承重类型 | 位置 |
| --- | --- | --- | --- |
| 1 画布 → 意图 | `applyCanvas()` | JSON `{amount, nodes:[{k,c,n,p}]}` | `route/client/RouteScreen.java:904-926` |
| 2 意图 → 约束 | `RouteApply.prepare()` | `RoutePolicy.Canvas(target,amount,List<Path(path,key,choiceId)>)` | `route/RouteApply.java:37-56` |
| 3 约束 → 证明 | `RouteNetwork.planAsync()` → `RoutePlanningService.planAsync()` → `planFrozen()` | `CertifiedCraftingPlan`（既是 `ICraftingPlan` 也是程序） | `route/RouteNetwork.java:160`、`route/RoutePlanningService.java:251-253` |
| 4 证明 → 提交 | `CraftingServiceMixin` 的 `@WrapMethod submitJob` | `CertifiedCpuDispatcher.submit/selectAndSubmit` | `mixin/CraftingServiceMixin.java:70` |
| 5 提交 → 执行 | `ProductionCpuMixin.trySubmitJob RETURN` 建 `ProductionJob` | `ProductionCursor` + 精确槽位 `PatternVariant` | `mixin/ProductionCpuMixin.java:63` |
| 6 执行 → 交付 | `tickCraftingLogic TAIL` 的 `aecraftroute$deliver` | `deliverableAmount(inventory,target,remaining)` | `planner/ProductionJob.java:57` |

段与段之间的实际桥是同一个对象：

```java
// planner/CertifiedCraftingPlan.java
record CertifiedCraftingPlan(
    GenericStack finalOutput, long bytes, KeyCounter usedItems,
    Map<IPatternDetails,Long> patternTimes,
    List<ProductionPlanner.Block<PatternVariant>> program
) implements ICraftingPlan
```

`simulation()=false`、`multiplePaths()=true`、`emittedItems()/missingItems()` 返回空 `KeyCounter`。类注释是硬约束：

> Plan and proof are one object: never strip the schedule at a cache/rebind boundary.

任何缓存、重绑、序列化边界只要剥掉 `program`，计划就退化成一张料表，执行层会静默走回原生路径——这是本工程最容易被后人破坏的一条不变量。

### 1.1 段1 画布 → 意图

`RouteScreen.applyCanvas()`（`route/client/RouteScreen.java:904-926`）只做三件事：遍历 `materialNodes` 取 `node.preview.choice()`；跳过 `choice==null || id.startsWith("plan:")` 的节点并计数；把 `{amount, nodes:[{k,c,n,p}]}` 发给服务端。

**不发的东西**：数量覆盖 `quantities`、折叠状态 `branchEdits`、手动偏移 `offsets`、批数。状态栏原文就写着「本地材料数量覆盖不提交，服务端按最终目标量重算」。这是设计而非遗漏，理由见 §3.2。

### 1.2 段2 意图 → 约束

`RouteApply.prepare()`（`route/RouteApply.java:37-56`）把节点列表翻译成 `RoutePolicy.Canvas`：

- `nodes.size() > 128` → 抛「强制画布最多 128 个分支」。
- `path` 必须匹配 `root(?:/[0-9]{1,3}){0,24}`，否则 problems 加「缺少有效路径，请重新打开画布」。
- 每个节点 decode key 并确认样板仍在网络上；材料或样板标识为空 → problems。
- 全空 → 「画布没有可执行的路径分支」。
- **任一 problem 存在时返回 base（policy 不前进）**，而不是挑一个能用的先用。

`RouteApply.apply` 分两路：带 `path` 的走 `prepare` + `CraftRouteCrafting.planNow`；不带 `path` 的（旧式按材料锁定）走 `RouteIntent.merge`。

### 1.3 段3 约束 → 证明

`RouteNetwork.planAsync`（`route/RouteNetwork.java:160`）是本段唯一入口：`:162` 取请求票 `PLANNING.replace(uuid, sequence)`，`:182` 调 `RouteApply.prepare`，`:194` 调 `RoutePlanningService.planAsync`，`:209` 在 `view.proven()` 成立时才 `RouteSessions.put` 并 `confirm.setAutoStart(false); confirm.replan()`。

预算是分段给死的：`apply ? (12000, 3_000_000_000L) : (6000, 2_000_000_000L)`，即预览 2 秒、应用 3 秒。

`RoutePlanningService.planFrozen`（`route/RoutePlanningService.java:251-253`）里只有一行分叉，但它是整套画布语义的总闸：

```java
if(policy.canvas()!=null) return RouteCanvasPlanner.plan(snapshot,policy.canvas(),key,amount,limits);
return NetworkProductionPlanner.plan(...);
```

### 1.4 段4 证明 → 提交

`CraftingServiceMixin` 的 `@WrapMethod submitJob`（`mixin/CraftingServiceMixin.java:70`）检查 `plan instanceof CertifiedCraftingPlan`，是则转 `CertifiedCpuDispatcher`。选 CPU 的规则（`compat/CertifiedCpuDispatcher.java`）：

- `:24` CPU 未激活 → `CPU_OFFLINE`；`:26` 存储小于 `plan.bytes()` → `CPU_TOO_SMALL`。
- `:21 submit` 拿到的是 `CraftingCPUCluster` 就交原生；AdvancedAE 走反射取 `cluster` 字段再 `submitJob`。
- `:40 selectAndSubmit` 遍历 `grid.getCraftingService().getCpus()`，跳过 busy / 容量不足 / `canBeAutoSelectedFor` 不通过；遇到 `MISSING_INGREDIENT` 或 `INCOMPLETE_PLAN` **立即返回**，不被后续候选的成功掩盖。
- 其它扩展 CPU（Trinity/ECO/Thunderbolt）打 warn「selected CPU cannot run a certified program；redirecting to a compatible CPU」。

### 1.5 段5 提交 → 执行

`mixin/ProductionCpuMixin.java` 上挂了七处钩子，每一处都对应一个具体不变量：

| 行 | 钩子 | 作用 |
| --- | --- | --- |
| `:35` | `@WrapMethod tickCraftingLogic` | 整个同步 tick 包进 `CertifiedExecutionScope.run(this, ...)`，隔离外部批量旁路 |
| `:63` | `@Inject trySubmitJob RETURN` | 提交成功且是认证计划 → 建 `ProductionJob` + `DispatchTrace.certified("ae2")`；否则置 null 并 `cluster.markDirty()` |
| `:80` | `@WrapOperation extractPatternInputs` | 改走 `production.extract(...)`，返回 null 表示当前步输入不足 |
| `:92` | `@WrapOperation pushPattern` | `!production.allows(pattern)` 直接 `return false`，成功后 `production.accepted(pattern)` |
| `:104` | `@WrapMethod insert` | 回料塞进 job 的 `waitingFor/inventory`，**交付延后到 TAIL，不在 pushPattern 内重入** |
| `:121` | `@Inject tickCraftingLogic TAIL` | `aecraftroute$deliver`：要求 `production.done()`、link 未取消、未 suspended、`cluster.isActive()` |
| `:162/:165/:170` | `finishJob` / `writeToNBT` / `readFromNBT` | 清理与持久化（`ProductionJob.TAG` = `aecraftrouteProductionV1`），读档后 `validateRemainingTasks` |

`mixin/AdvancedProductionCpuMixin.java` 是同构实现（宿主 `advanced_ae`，`@Pseudo`）。新增宿主 CPU 时照抄这张表即可，见 §8.1。

### 1.6 段6 执行 → 交付

`planner/ProductionJob.java`：

- `:57 deliverableAmount(KeyCounter inventory, AEKey target, long remaining)`：规则是「程序未派发完 → 0；派发完 → `min(remaining, max(0, 实际目标库存 - 初始目标资本))`」。初始资本由 `requiredCapital(AEKey)` 从程序前缀推导，**估计出来的 `waitingFor` 不参与完成判定**。
- `:90 validateRemainingTasks(Map<IPatternDetails,Long> tasks)`：比对原生任务表与游标剩余，不符则判 `invalid` 并打印 "cancel and resubmit. No inventory mutation."。
- `:119 extract(...)`：逐槽 `SIMULATE` 预检 → `MODULATE` 实取；任一失败 `reinjectPatternInputs` 回滚并返回 null（`:127` 预检、`:130` 实取）。

## 2 三个承重契约

这三个契约不是实现细节，而是联动能否成立的前提。破坏任意一条，画布就会变成「看起来能选、实际被静默改写」。

### 2.1 约束不可回退

`route/RoutePolicy.java` 的 `filter()` 里：

```java
if(rule.stockOnly()) return List.of();
```

类注释写得比代码更重：**Empty results must NEVER fall back to automatic recipes.**

配套的第二半在 `mixin/CraftingServiceMixin.java:147`：只要存在手动规则，就只走 `CraftRouteCrafting.calculate`；**失败不回落原生**，只把 `[AE2 路线] <cause>` 发给玩家。`:164` 对未注册的第三方 requester 直接 return（`api/CraftRouteCraftingRegistry.isUnregisteredThirdParty`），即软兼容：别人不 opt-in，我们完全不插手。

### 2.2 游标即授权

执行层从不自己算下一步。授权链是三个断言：

1. `planner/ProductionCursor.java:44 accepted(R recipe)` 先断言 `Objects.equals(current(),recipe)`，否则抛 `Out-of-order dispatch`。
2. `pushPattern` 被包（`ProductionCpuMixin.java:92`），不在程序里的样板直接 `return false`。
3. 取料走 `ProductionJob.extract()`，按 `cursor.current().slots()` 逐槽预检，失败回滚。

`ProductionCursor` 的字段只有 `block/step/round/completed`，`remainingCounts()`（`:17`）用 `Math.multiplyExact(instruction.count(), rounds)` 并扣掉当前块已完成的步数。**位置只在 provider 真的接收了实物之后才前进**（类注释：Execution position advances ONLY after a provider accepts the real inputs.）。

### 2.3 提交前有独立复核

`RouteNetwork.planAsync` 的 `valid` 谓词（`:194` 上下文）同时检查六件事：请求票仍是当前票、菜单未换且 `stillValid(player)`、代理仍启用、`RouteSessions.get(grid,uuid).equals(base)`、玩家在线、目标节点 `isActive()` 且 `getGrid()==grid`。任一条不成立就**放弃提交且不写策略**。

这解释了为什么关画布、切屏、换目标不会留下半提交状态：提交是一次带前置条件的原子动作，不是先写后验。

## 3 画布专属：为什么必须有 path

### 3.1 按材料锁表达不了的东西

旧式约束是 `lock(key, choiceId)`——按材料锁定样板。它无法表达「同一材料在两条分支上用不同物理样板」，而现实中这恰恰常见：同一物品来自两个不同供应器的不同处理线。

所以画布走**路径契约**：`RoutePolicy.Path(String path, K key, String choiceId)`，`path` 形如 `root/0/1`，索引对应配方输入槽位分组。

### 3.2 需求不信客户端数字

`route/RouteCanvasPlanner.java:13` 先校验 `canvas.target()/amount` 与请求完全一致，否则 INVALID「强制画布只适用于应用时的目标与数量；请重新应用画布」。随后所有需求都由原生样板**数量**反推：

```java
count = 1 + ((demand - 1) / output);
```

类注释：never from client-supplied numbers。这条是 §1.1 里「不发数量覆盖」的直接理由。

另外会整体拒绝的四类输入：重复/缺失 path、根路径缺失或 key 与目标不符、孤立路径（父配方未展开）、样板不产出对应材料或输入材料已变化。都抛 INVALID 而不是猜一个。

`primaryInputs`（`:96-105`）只取 `in.getPossibleInputs()[0]`——**画布只认精确主变体**，其它变体交给 `PatternVariants.enumerate` 枚举，累计超 8192 判 LIMIT。

### 3.3 虚拟标记：让多路径共享库存可证明

多条路径共享一份物理库存、同时各自要达到最小批数——直接写约束会重复计账。`route/ForcedRoutePlanner.java` 的手法：

1. 每个分支造一个虚拟标记 `record Marker(String path)`。
2. `:30-31` 给被选中样板的输出额外挂 `marker → 1`，并把 `goals.put(marker, minimum)` 设成该分支最小批数。
3. 最后加一个 `Marker(\u0000goal)` 汇总全部 goal，于是多约束变成一个普通生产网证明。
4. `:55-88` 解出来后**剔除虚拟步只留物理步**，用 BigInteger 前缀摘要独立重放，校验 `required <= stock` 与 `delta(target) >= increment`；不通过抛 `Physical stock double spent` 或 `Target is not a net increment`。

类注释：A finite canvas is a minimum physical execution contract, not a material filter. 画布不是过滤料表，是「至少这样执行」的物理合同。

### 3.4 会话态不落盘

`route/RouteSessions.java` 用 `WeakHashMap<IGrid, Map<UUID, RoutePolicy<AEKey>>>` 存 `SESSIONS`，另有 `BASELINES`（`record Baseline(int menu, RoutePolicy policy)`）供 `restore` 使用；`restore` 在 baseline 缺失或 menu 不符时抛「原始合成树会话已失效，请重新进入」。

生命周期：`PlayerLoggedOut` 时 `remove`（`CraftRouteAddon.java:50`），`ServerStopped` 时 `clear`（`:53`）。**路线约束永不进存档**，唯一的 NBT 是样板缓存 `RouteCacheData`。这是刻意设计：约束是登录会话态，不该被自动化或另一个玩家继承。

## 4 回程：执行层现在能告诉画布什么

### 4.1 已实现两条

**约束回显**：`reply.canvas().paths()` 被存进 `appliedPathChoices`（`route/client/RouteScreen.java:784-787`）；重建图时该路径强制显成 locked 蓝边、不允许改选（`:1063-1066`）。用户看得到这条分支已经被服务端钉死。

**裁决回显**：`RoutePlanView.of(plan, amount, stackFactory)` 把 `usedItems` 变成起手资本、按输出名聚合 `patternTimes` 成步骤；`proven() = FEASIBLE.equals(verdict)` 才允许画成绿色三态（`RouteNodeVisual.of(proven, stockOnly, localEdit)`：绿=计划已证明 / 灰=未证明 / 琥珀=本地编辑未证明）。

**注意 `startup` 的语义**：它是**计划起手资本，不是实时库存快照**。循环增殖路线用 8 个起手资本就能完成 1000 个目标；若把 `demand - startup` 当缺口会误报 992 缺料。所以不能据此做「缺料橙 / 备齐绿」分类，见 §7.2。

### 4.2 缺口：执行进度无上行

`ProductionJob.position()` 与 `remainingCounts()` 目前**只被 30 秒停滞看门狗打进日志**（`mixin/ProductionCpuMixin.java:57`）。`route/` 与 `route/client/` 里没有任何 progress 通道——画布上无法显示「这批走到第几步、哪个分支已满足」。

落点应当是：`ProductionJob.remainingCounts()` → 一个按 `(grid, cpu)` 索引的只读快照 registry → `RouteView.Reply` 的增量字段。**不要在 `RouteScreen` 里反射 CPU**，那会把客户端绑死在服务端类结构上。

### 4.3 端到端实证

`selftest/SelftestHarness.java:700-745` 的 `submitPathCalculation()` 是这套链路的唯一完整回归：用 4 个 path（`root`、`root/0`、`root/0/0`、`root/1`）造两条 lapis 的不同物理样板，`RouteApply.apply` 断言 `locked().size()==4` 且 `applied.policy().canvas()!=null`，再 `RoutePlanningService.planAsync` → 真实 `grid.getCraftingService().submitJob(...)`（`:847`）→ 断言两种 lapis 样板都真的被 dispatch、`DispatchTrace.hosts().get("ae2") >= 1`。

`api/DispatchTrace` 就是为这条断言存在的：它回答的问题不是「规划成功了吗」，而是「**哪个 CPU 真的收到了程序**」。

## 5 承重类型速查

| 类型 | 关键内容 |
| --- | --- |
| `planner/CertifiedCraftingPlan` | 证明 + 程序同体；`simulation()=false`、`multiplePaths()=true`；`emitted/missingItems` 空 |
| `planner/ProductionCursor` | `done()`:13 / `current()`:14 / `remainingCounts()`:17 / `accepted()`:44 |
| `planner/ProductionJob` | `deliverableAmount()`:57 / `validateRemainingTasks()`:90 / `extract()`:119 |
| `route/RoutePolicy` | `Rule(locked,banned,stockOnly)` + `Canvas(target,amount,paths)`；`filter()` 的 stockOnly 空结果 |
| `route/RouteCanvasPlanner` | `:13` 目标/数量校验、`:77` `ForcedRoutePlanner.Proof`、`:94` 组装 `CertifiedCraftingPlan` |
| `route/ForcedRoutePlanner` | `Branch(path,recipes,minimum)` / `Proof(used,counts,program,branchCounts)` / `Marker(path)` |
| `route/RouteIntent` | `Node(key,label,choiceId,choiceExists)`；同 key 多 choiceId 报 problems 而非任选 |
| `route/RouteSessions` | `WeakHashMap<IGrid, Map<UUID, RoutePolicy>>` + `BASELINES`；内存态 |
| `api/CraftRouteCrafting` | `calculate()` 取会话策略 + `Limits(30_000, 5s)`；`calculateSync` 在服务器线程直接抛 |
| `api/DispatchTrace` | `certified(host)` / `certifiedAttaches()` / `hosts()` / `reset()` |

## 6 会被明确拒绝的边界（不是 bug）

- **apply 的目标必须与确认页一致**：菜单是 `CraftConfirmMenu` 时，`request.key()`/`amount` 必须等于 `aecraftroute$routeWhatToCraft()`/`aecraftroute$routeAmount()`，否则「画布目标或数量与当前合成确认页不同；请从对应的确认页重新打开」。
- **三类失败永不互相伪装**：`LIMIT`（预算用尽）、`UNSUPPORTED`（无法建模 / 替代分配未穷尽）、`UNREACHABLE`（已证明达不到）。特别是强制画布的替代分配 `incomplete` 时，即便求解失败也降级为 UNSUPPORTED 并附「未穷尽；没有证明缺料」，绝不谎报缺料。
- **已损坏的任务不自愈**：`validateRemainingTasks` 发现原生任务表与游标不符就判 `invalid`，打印 "cancel and resubmit. No inventory mutation."，不猜库存、不跳步。
- **缓存命中也要复核**：跨请求复用只在 `stockReplans == 0` 时使用，且 `stillExecutable` 逐个校验样板仍发布、`usedItems` 为纯叶子且库存足够；任一不满足就 forget 重算。
- **起手材料变动不是缺料**：`ProductionPlanningException.StockDeficit` 携带 `(key, needed, available)`，`RoutePlanningService` 在同一个请求内重采集 + 重算最多 2 次，**不重置原始端到端 deadline**；重算时把已确认减少的中间物屏蔽为 0 优先自上游生产，保守候选失败则回退真实快照。

## 7 待补能力（明确未实现）

### 7.1 执行进度上行

见 §4.2。缺的是：进度快照 registry、`RouteView.Reply` 增量字段、画布上的分支进度呈现（例如已满足分支打勾、当前块高亮）。不要用日志解析凑。

### 7.2 逐节点库存缺口分类

现在的三态（已证明 / 未证明 / 本地编辑未证明）是**证明状态**，不是**库存状态**。要做逐节点「缺多少」必须先定两件事：

1. 库存快照的时点与来源（`RouteSnapshot` 的 stock 与 `liveInventory()` 谁是权威）。
2. 同一个 `AEKey` 出现在多条分支时的库存归属规则（池式扣减顺序必须可复现，否则同一份库存会被两条分支各算一次）。

在定清之前，用 `startup` 推缺口一定是错的（§4.1）。

### 7.3 折叠 / 数量 / 批数的持久化

折叠 `RouteBranchEdits` 是纯呈现层（类注释：Presentation-only pruning: never edits the ingredients of an encodable recipe.），数量覆盖 `quantities` 与批数都不提交、不落盘、不跨会话。若要让「我的路线」可保存，需要新设计一个路线文档格式，**不能借用 `RouteCacheData`**（那是 18 槽样板缓存 + revision + token，语义完全不同）。

## 8 接入指引

### 8.1 新 CPU 宿主

1. 实现 `compat/CertifiedExecutionOwner`：`boolean aecraftroute$hasCertifiedProgram()` + 静态 `blocksExternalBatch(Object)`。
2. `tickCraftingLogic` 包进 `CertifiedExecutionScope.run(this, () -> original.call(...))`，并用 `@WrapMethod`（不是 `@Inject` HEAD）。
3. 照 `mixin/ProductionCpuMixin.java` 的七处钩子（§1.5 表）同构实现：`trySubmitJob RETURN`、`extractPatternInputs`、`pushPattern`、`insert`、`TAIL deliver`、`finishJob`、读写 NBT。
4. 到 `compat/CertifiedCpuDispatcher.java:21-40` 加一条 `submit` 分支，否则该 CPU 会被判不兼容并重定向到别的 CPU。
5. 补一个专用服务器场景到 `selftest/SelftestHarness.java`，断言 `DispatchTrace.hosts()` 命中新宿主名。

### 8.2 新请求方（第三方模组）

`CraftRouteCraftingRegistry.register(className)` 登记后，`CraftingServiceMixin` 才会接管它的计算请求；也可以直接调 `CraftRouteCrafting.calculate(grid, requester, what, amount, strategy)`，走同一条 `planAsync`（`Limits(30_000, 5_000_000_000L)`）。未登记的一律不插手。

### 8.3 新画布输入 / 新功能

- 纯函数与 record 放 `route/client/`，配套单测放 `src/test/java/com/aecraftroute/addon/route/client/`；`RouteScreen` 里只留 ≤20 行胶水（local.47 的 `RouteAnchor`/`RouteWheelIntent`/`RouteNodeVisual`/`RouteTreeGeometry` 就是这个模式）。
- 服务端新动作只在 `RouteNetwork.handle` 加分支；规划一律经 `planFrozen`，**绝不要自己拼 `CertifiedCraftingPlan`**（会绕过 `ForcedRoutePlanner` 的独立重放校验）。
- **不要把数量、折叠、批数塞进 apply 载荷**。§3.2 的 never from client-supplied numbers 是防线，不是可以商量的性能优化。

## 附录 行号索引

### 画布与客户端
- `route/client/RouteScreen.java:904-926` applyCanvas 发包
- `route/client/RouteScreen.java:784-787` 存 `appliedPathChoices`
- `route/client/RouteScreen.java:1063-1066` locked 蓝边回显
- `route/client/RouteNodeVisual` 三态（绿/灰/琥珀）

### 服务端约束
- `route/RouteApply.java:37-56` prepare
- `route/RouteNetwork.java:160` planAsync、`:162` ticket、`:182` prepare、`:194` planAsync 调用、`:209` proven 提交
- `route/RoutePolicy.java` filter/change
- `route/RouteIntent.java` merge
- `route/RouteSessions.java` 会话态

### 规划与证明
- `route/RouteCanvasPlanner.java:13` 目标/数量校验、`:77` Proof、`:94` 组装计划
- `route/ForcedRoutePlanner.java:30-31` Marker 挂载、`:55-88` 独立重放
- `route/RoutePlanningService.java:251-253` planFrozen 分叉
- `planner/CertifiedCraftingPlan.java`、`planner/ProductionCursor.java:13/14/17/44`、`planner/ProductionJob.java:57/90/119`

### 执行与交付
- `mixin/CraftingServiceMixin.java:70` submitJob、`:147` 手动规则不回落、`:164` 未注册直接 return
- `mixin/ProductionCpuMixin.java:35/57/63/80/92/104/121/162/165/170`
- `mixin/AdvancedProductionCpuMixin.java` 同构
- `compat/CertifiedCpuDispatcher.java:21/24/26/40`、`compat/CertifiedExecutionScope.java`、`compat/CertifiedExecutionOwner.java`

### 实证
- `api/CraftRouteCrafting.java`、`api/CraftRouteCraftingRegistry.java`、`api/DispatchTrace.java`
- `selftest/SelftestHarness.java:700-745` submitPathCalculation、`:847` 真实 submitJob