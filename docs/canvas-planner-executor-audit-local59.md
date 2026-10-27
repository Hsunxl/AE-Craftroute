# 画布 ↔ 规划 ↔ 执行：三层接通可行性审计（local.59 基线）

> 本文是**只读审计**的产物。本轮没有修改模组任何源码。
> 审计对象：`tools/ae2vm-build/AE-Craftroute`（NeoForge 1.21.1 / ATM10 附加模组 AE-Craftroute）

---

## 0. 阅读须知：基线与时效性（先读这一节）

### 0.1 基线快照

| 项 | 值 |
|---|---|
| HEAD commit | `1c7ee64` `fix: stabilize route canvas previews and provider mappings`（2026-10-26 02:33:53 +0800） |
| 分支 | `wip/planner-vm-ui-2026-10-25` |
| `gradle.properties:14` | `mod_version=1.13.19-local.59-dev` |
| 依赖 | minecraft 1.21.1 / neoforge 21.1.259 / ae2 19.2.18 |
| src 下 `.java` 数量 | 212 |
| 全树指纹（`Get-FileHash` 汇总） | `62415FF169CD38831C4759427E378A7222FB5A96AD664AB73E9C3A87FE08A007` |
| 行号锚定用的纯净快照 | `docs/teamflow-run-notes/head-1c7ee64/`（`git show HEAD:<path>` 抽出，扁平命名如 `route__RouteScreen.java`） |

**本文所有行号都以 `1c7ee64` 的 `git archive` / `git show` 内容为准**，不是工作区文件。原因见 0.2。

### 0.2 并发改写实证（为什么不能直接引用「当前文件的行号」）

审计期间同目录源码树**正在被另一个团队持续改写**，实测证据：

- 02:27:56 与 02:28:19 两次全树指纹快照（211 → 212 个 `.java`，逐文件 SHA256），比对出 **6 个文件在这 23 秒内被改写**：
  `route/client/RoutePatternPresentation.java`、`route/client/RouteRecipeHover.java`、`route/client/RouteScreen.java`、`route/RoutePatternSources.java`、`route/RouteProviders.java`、`route/RouteWorkbench.java`
- `git status` 的已跟踪修改数从 17 个（`M`）增长到 25 个，未跟踪新增 `route/RouteProviderPresentation.java`；`git diff --stat` 从 463+/148− 增长到 592+/216−。
- 随后这批改动**被并发团队提交**为 `1c7ee64`（HEAD 由 `54d2817` 前进，`git diff --stat 54d2817..HEAD` = 30 files changed, 696 insertions(+), 179 deletions(-)）。`CHANGELOG.md` 新增 local.53–local.59 七条。

**结论：本文结论对 `1c7ee64`（local.59）有效；对该提交之后的工作区/新提交不保证有效。** 引用行号前请先确认基线，否则可能指向完全不同的代码。

**时效复核（2026-10-26 03:03 复查）**：HEAD 仍为 `1c7ee64`，`src` 下最新 mtime 为 02:30（并发团队提交前留下），此后 33 分钟内未再出现新的文件改写，工作区仍只有本文 §0.3 那 6 个未提交文件（`git diff --shortstat` = 6 files changed, 244 insertions(+), 37 deletions(-)，与 02:30 时一致）。若后续 HEAD 前进，请以 `git rev-parse HEAD:src/...` 重新核对行号。

### 0.3 工作区里那 6 个未提交文件是什么

审计时刻工作区仍有 6 个文件是**本会话更早一轮流水线 dev 阶段**（非并发团队、非本次审计）留下的产物，244 insertions / 37 deletions，mtime 02:08–02:30：

`api/CraftRouteCrafting.java`、`compat/CertifiedCpuDispatcher.java`、`planner/ProductionJob.java`、`route/RouteApply.java`、`route/RoutePlanningService.java`、`route/client/RouteScreen.java`

**这 6 个文件里的「修复」不在 `1c7ee64` 里，也未经任何验证。** 因此本文按 HEAD 判缺陷（下文 D2/D4/D5/D6/D19 在 HEAD 全部成立），并在对应条目下标注「工作区有一份未验证的改法」。**不要把这些未提交改动当成已修复**。

### 0.4 与既有文档的关系

`docs/canvas-to-execution-linkage.md`（313 行）自述基线为 `dfa2aea`，比本文落后三个提交（`dfa2aea` → `54d2817` → `1c7ee64`）。它给出的是**设计意图**的六段链路表；本文给的是**代码事实**的逐段行号 + 缺陷清单。二者的增量与更正见 §5。

---

## 1. 结论摘要

### 1.1 三层到底通没通

| 层间 | 结论 | 一句话依据 |
|---|---|---|
| 画布（客户端）→ 请求上行 | **通** | `RouteScreen.applyCanvas()` 组 JSON 后 `send("apply",...)`，`RouteNetwork.handle` 把它路由进 `planAsync`，全程无空实现 |
| 请求 → 规划器 | **通** | `planAsync` 里 `RouteApply.prepare` 校验后构造 `RoutePolicy.Canvas`，`RoutePlanningService.planFrozen` 用 `policy.canvas()!=null` 优先分派到 `RouteCanvasPlanner` |
| 规划器 → 证明 | **通** | `RouteCanvasPlanner` → `ForcedRoutePlanner` → `ProductionPlanner`（含 `certify` 证明门）→ 生成 `CertifiedCraftingPlan` |
| 证明 → 提交执行 | **通但有语义缺口** | `planAsync` 只在 `apply && view.proven()` 时落策略并 `confirm.replan()`；`CraftingServiceMixin` 的会话判定却只看 `rules()`（D1） |
| 提交 → CPU 执行 | **通但有静默改道** | `CertifiedCpuDispatcher.submit` 遇到显式选中但不兼容的 CPU 会 `selectAndSubmit` 改投别的 CPU（D4/D5） |
| 执行 → 交付给请求者 | **有真实语义缺口** | `link.insert` 返回 0 被当「无 requester」，货物改投 ME 网络并扣 `remaining`（D8，javap 已证 0 有两种含义） |
| 执行进度 → 画布（上行） | **不存在** | 无任何 server → client 的执行进度包；画布只能显示规划结果，看不到「正在做第几个样板」 |

**所以：画布 ↔ 规划 接通成立（比 `canvas-to-execution-linkage.md` 撰写时更完整）；规划 ↔ 执行 不是「没接线」，而是「接好了但有三处语义事故」。**

### 1.2 三个真实语义缺口（会让玩家困惑/丢货，不是噪声）

1. **D1 — 关闭开关时的会话判定漏了 canvas。**
   `mixin/CraftingServiceMixin.java:139` 与 `:147` 都用 `!RouteSessions.get(...).rules().isEmpty()` 判断「这个会话有没有手动约束」。但 `RoutePolicy.empty()` 的正确语义在 `route/RoutePolicy.java:14` 是 `rules.isEmpty() && canvas==null`。于是 **canvas-only（rules 空、canvas 非空）会话在 `proxyEnabled=false` 时既不报「AE-Craftroute 已关闭」，也不走隔离失败路径**。
   （注意：这不是「静默回退原生规划」——rules 空时走 `:162` 分支，`planFrozen` 会先看 canvas 正常规划。缺口只在「开关关闭时的判定」和「失败隔离」上。）

2. **D4/D5 — 显式选择的 CPU 被静默改道。**
   `compat/CertifiedCpuDispatcher.java:31-37`：拿不到 `CraftingCPUCluster` 也拿不到 `advancedCluster` 时，只打一条 warn 就 `return selectAndSubmit(...)`。而 `advancedCluster` 的判定（`:74-75`）是**类名精确全等** `"net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU"` + `:77` `getDeclaredField("cluster")`（只看叶子类），任何子类/字段在父类的实现都会被判不兼容，然后被静默改道——**恰好触发了 D4 的兜底**。两处合起来 = 玩家选的 CPU 不是实际跑任务的 CPU。

3. **D8 — 交付改道：请求者永久收不到货。**
   `mixin/ProductionCpuMixin.java:138` `link.insert(key,held,MODULATE)` 返回 0 时，`:140-146` 判定为「无 requester」，改投 `grid.getStorageService().getInventory().insert(...)` 并计入已交付、扣 `remaining`。
   但 javap 实证 AE2 19.2.18 的 `appeng.crafting.CraftingLink.insert` 返回 0 有**两种**含义：① 无 requester（tie/request/requester 任一为 null，直接 `lconst_0;lreturn`）；② **requester 本 tick 拒收**（`insertCraftedItems` 返回 0）。本模组把 ② 也当成 ①，于是**请求者永远收不到、成品凭空进了 ME 网络**。

### 1.3 按危害排序的最危险五条

| 序 | 编号 | 位置 | 危害 |
|---|---|---|---|
| 1 | D9 | `mixin/ProductionCpuMixin.java:175-183` + `planner/ProductionJob.java:100,120` | 校验失败只打日志不 cancel → `allows()` 恒假 → CPU 每 tick 推 0 个样板 → **任务永久卡死、材料不归还** |
| 2 | D8 | `mixin/ProductionCpuMixin.java:140-146` | 交付改道，请求者永久收不到货（见上） |
| 3 | D1 | `mixin/CraftingServiceMixin.java:139,147` | 关开关时 canvas-only 会话失去失败隔离 |
| 4 | D2 | `planner/ProductionPlanner.java:392-398` | `offer()` 内部无 deadline/中断检查，单次调用 O(basis×keys×BigInteger) 不可打断；上限 `:387` 允许 12 万节点（`limits.states*4`） |
| 5 | D3 | `planner/CompiledProductionGraph.java:13,69` | 缓存签名只含请求内局部 key 下标、不含 key 身份 → 跨请求串用编译图 |
### 1.4 第二、三轮「全树逐行」的净增量（相对第一轮）

第一轮的结论**没有一条被推翻**；第二轮把 `src/main/java` 129 个 `.java` / 12165 行全部逐行读完（快照与范围见附录 A、附录 B），净增量如下：

| 增量 | 数量 | 位置 | 最高优先级 |
|---|---|---|---|
| 服务端新增条目 | 5 | §3.6 的 D37–D41（路径常量不一致、反射诊断静默、LogThrottle 无线程契约、Thunderbolt stub 与主类注释冲突、配置三项写法不统一） | **全部 P2** |
| 客户端新增条目 | 9 | §7.1 的 C8–C16 | **P1×5**（C8 节点配色全错 / C9 画布回包饿死 / C10 预览数量静默错值 / C11 子树拖不动 / C12 布局失败无法自愈） |
| 证伪的第三方说法 | 5 | §7.2（底部按钮 2px、顶部 scissor 裁切、`status` 可能为 null、`RouteJeiPlugin` 自报 P0、`RouteRecipeHover` 缓存键） | — |
| 降级 | 1 | `route/client/RouteJeiPlugin.java:110-113` 自报 **P0 → P3** | — |
| **第三轮**：三份子代理报告亲核后的新增 | 11 | 服务端 **D42–D46**（§8.1：编码失败丢样板 / 坏缓存槽锁死面板 / 溢出错误文案为 null / lock-ban 被单张坏样板带崩 / 槽位分页只按第一个供应器）+ 客户端 **C17–C21**（§8.1：JEI 点击整张配方解码无 try / 缓存展开盖住编码按钮 / 删节点全局屏蔽同材料分支 / EMC 索引越界 / 设备候选失败永久缓存） | **P1×1**（C17，客户端鼠标事件崩溃），其余 P2 |
| **第三轮**：降级 / 证伪 | 5 | 降级 2（`begin` 非幂等 → P3；`decodeKey` 返回 null 并入 C5）+ §8.3 证伪 3（`whenComplete` 无 try、`item()` 未判 level、`renderProviders` 的 `state==null` NPE） | — |

**最值得先修的不是 P2 那 5 条服务端项，而是 C9 与 C11**：C9（`RouteNetwork.clientHandler` 单静态字段被覆盖、`RouteScreen.onClose()` 不恢复）会让第二个画布打开后的第一个画布**彻底收不到回包**；C11（`RouteTreeGeometry.constrain` 的 `Math.max` 吃掉向上/向左位移）让「拖子树」这个核心交互**完全失效**。两条都在玩家高频路径上，且都是纯客户端、改动面小。

第三轮亲核后又补了一条同级别的：**C17**（`route/client/RouteJeiRecipes.java:490` 的 `readDraft` 没有 try，同配方任一槽的 JEI 类型没注册 AE2 转换器就把 `IllegalArgumentException` 抛进 `mouseClicked`）——这是**唯一一条会把异常送到客户端鼠标事件里**的条目，触发面取决于装了什么 JEI 插件配方，但代价是直接崩客户端，建议与 C9/C11 同批修。其余第三轮条目（D42–D46、C18–C21）都是 P2，按 §6.1 第 6 批清。

---

## 2. 三层接通的逐段链路

证据等级：【实读】= 逐文件逐行读过；【javap】= 反编译 AE2 jar 字节码确认；【推断】= 依调用点/类型推断，未运行验证。

### 2.1 链路总图（行号均为 `1c7ee64`）

```
[客户端]
 RouteScreen.applyCanvas()            :957-980   <- 遍历画布节点，组 {"k","c","n","p"} JSON
   +- send("apply", target, json)     :979 / :769-793
        +- RouteNetwork.Request(menuId, sequence, action, key, argument)
[服务端]
 RouteNetwork.handle                  :60-110
   +- 旁路动作 workbench/encode/takecache/upload        :60
   +- planAsync(...)                  :80 / :160-223
        +- RouteApply.prepare(...)    :182 -> route/RouteApply.java:37-51
        +- valid 布尔链                :186-193
        +- RoutePlanningService.planAsync(..., Limits, valid)   :194-195
             +- planFrozen(...)   route/RoutePlanningService.java:251-255
                  +- policy.canvas()!=null -> RouteCanvasPlanner.plan(...)   :253
                  +- 否则                  -> NetworkProductionPlanner.plan(...)
                       +- RouteCanvasPlanner.plan   route/RouteCanvasPlanner.java:11-110
                            +- 校验 canvas.target/amount == 请求        :13-14
                            +- 逐样板 ScaledPatternCompat.validate      :17-21
                            +- 路径去重 / 样板解析 / root 必须存在      :25-32
                            +- demands / computeCount / primaryInputs   :34-50
                            +- PatternVariants.enumerate(<=8192)        :53-65
                            +- 精确输入分配匹配（引用相等！D13）        :67-76
                            +- ForcedRoutePlanner.solve(...)            :79-83
                                 +- route/ForcedRoutePlanner.java:21-93
                                      +- 每 branch 一条网络 + Marker 伪配方  :31-42
                                      +- ProductionPlanner.solve(goal,1)     :43
                                      +- 转物理程序 + BigInteger 重放        :51-82
                                      +- new CertifiedCraftingPlan(...)      :94
[提交 / 执行]
 RouteNetwork.planAsync 回调           :197-218
   +- if(apply && view.proven())        :209
        +- RouteSessions.put(prepared)  :211
        +- confirm.setAutoStart(false); confirm.replan()   :212
 CraftingServiceMixin.beginCraftingCalculation HEAD   :127-186
   +- planFrozen -> CertifiedCraftingPlan
 CraftingServiceMixin.submitJob @WrapMethod            :70-83
   +- if(plan instanceof CertifiedCraftingPlan) -> CertifiedCpuDispatcher.submit/selectAndSubmit
        +- CPU.submitJob -> ProductionCpuMixin.trySubmitJob RETURN   :63-78
             +- new ProductionJob(certified)                        :70-75
 ProductionCpuMixin 执行期                                        :80-160
   +- extractPatternInputs 包装（计划输入序 / 熔断）              :80-90
   +- pushPattern 包装（accept 计数 / markDirty）                 :92-102
   +- 交付：link.insert -> 失败改投 ME（D8）                       :130-160
```

### 2.2 逐段证据与语义

| # | 段 | 位置 | 证据 | 语义要点 |
|---|---|---|---|---|
| L1 | 画布采集 | `route/client/RouteScreen.java:957-980` | 【实读】 | `:958` 非修改模式直接拒（「请从自动合成确认界面进入修改模式」）；`:964` 跳过 `choice==null \|\| id().startsWith("plan:") \|\| material.key==null`；`:972` 空画布拒；`:974` 数量 `max(1,targetAmount)` |
| L2 | 请求上行 | 同上 `:769-793` | 【实读】 | 三重门：菜单代际（`:769`）、参数长度 `:782` `>24000` →「配方数据过大，无法编码」、序号自增 `:783` |
| L3 | 动作分派 | `route/RouteNetwork.java:60-110` | 【实读】 | `:60` 四个旁路动作；`:88` `query/preview` 空实现；`:91` `clear` 全清；`:107` **无条件 `RouteSessions.put`（D21）** |
| L4 | 应用校验 | `route/RouteApply.java:37-51` | 【实读】 | `:39` ≤128 分支；`:43` 解码失败被空 catch 吞（D7）；`:45` 统一报「材料或样板标识无效」；`:46` 路径必须匹配 `root(?:/[0-9]{1,3}){0,24}`；`:49` 无路径拒 |
| L5 | 策略构造 | `route/RouteApply.java:50` | 【实读】 | `new RoutePolicy<>(base.rules(), new Canvas<>(target,amount,paths))` —— **保留原 rules，只换 canvas** |
| L6 | 规划分派 | `route/RoutePlanningService.java:251-255` | 【实读】 | canvas 优先于 network planner；没有「两者都跑再比较」的逻辑 |
| L7 | 强制画布规划 | `route/RouteCanvasPlanner.java:11-110` | 【实读】 | target/amount 必须等于应用时刻的请求（`:13`），否则 INVALID「请重新应用画布」 |
| L8 | 求解与证明 | `route/ForcedRoutePlanner.java:21-93` | 【实读】 | 每条画布路径变成一条「强制分支」网络，再让 `ProductionPlanner` 在共享库存下证明「所有分支都能执行且净增 = increment」；失败原因映射为 LIMIT/UNREACHABLE |
| L9 | 证明结果落策略 | `route/RouteNetwork.java:209-214` | 【实读】 | **只有 `view.proven()` 才 `put` + `replan()`**；否则 message =「未应用任何路线改动 · …」。这是「本地覆盖不污染服务端」的保证 |
| L10 | 会话判定 | `mixin/CraftingServiceMixin.java:127-186` | 【实读】 | `:138` 开关关闭分支、`:146-147` 「never fall back to unrestricted AE2」隔离分支、`:162-185` 正常路径。**两处判定都只看 `rules()`（D1）** |
| L11 | CPU 派发 | `compat/CertifiedCpuDispatcher.java:21-92` | 【实读】 | `:23-27` 原生 CPU 三判；`:29-30` 高级 CPU 反射；`:31-37` 兜底静默改道（D4）；`:74-75` 精确类名（D5） |
| L12 | 执行桥 | `mixin/ProductionCpuMixin.java:63-102` | 【实读】 | `:66` 只在 `submitJob` 成功时挂钩；`:70-75` 只接管 `CertifiedCraftingPlan`；`:94` 不在计划内就 `false` |
| L13 | 交付 | `mixin/ProductionCpuMixin.java:130-160` | 【实读】+【javap】 | 见 D8。AE2 原生 `CraftingCpuLogic.storeItems()` 是「逐 key `MEStorage.insert(MODULATE)` 后按返回值递减」，即「把剩余全塞回网络」——本模组在 `link.insert` 返回 0 时复用了同样的语义，但没区分两种 0 |
| L14 | 进度上行 | —— | 【实读】 | **不存在**。`RouteNetwork` 的回包只有 `RouteView.Reply`（choices/locked/banned/stockOnly/ruleCount/truncated/plan/canvas/requestTarget），没有执行进度字段 |

### 2.3 一个容易误判的点

`RouteApply.apply()`（`route/RouteApply.java:53-65`）在 HEAD **没有任何调用点**——`RouteNetwork` 只调 `prepare`，真正的规划在 `planAsync` 里异步做。`apply()` 是遗留 API（它内部会同步 `planNow`）。读代码时不要以为线上路径会走它。
---

## 3. 缺陷清单

严重度定义：**P0** = 玩家可见的功能错误或服务端风险；**P1** = 会丢失/改道物品、永久卡死、静默改变语义；**P2** = 健壮性、可维护性、边界。

本节 D1–D46 是**服务端 / 规划 / 执行**层的结论：D1–D36 来自第一轮（23 个关键文件精读），§3.6 的 D37–D41 是第二轮 `src/main/java` **129 个 `.java` / 12165 行全部逐行**读完补出来的，D42–D46 是第三轮对三份第三方子代理报告逐条亲核后成立的（详表在 §8.1）。客户端 / UI 层单列在 §7（C1–C21，含被否掉的误报），两者同基线、同严重度口径。

### 3.1 P0

| 编号 | 位置 | 机制 | 触发条件 |
|---|---|---|---|
| **D1** | `mixin/CraftingServiceMixin.java:139`、`:147` | 会话有无手动约束只判 `rules().isEmpty()`，漏了 `canvas`；正确语义见 `route/RoutePolicy.java:14` `empty()=rules.isEmpty() && canvas==null` | `proxyEnabled=false` 且会话是 canvas-only（rules 空、canvas 非空）：不报「已关闭」，也不走「never fall back」的隔离失败路径 |
| **D2** | `planner/ProductionPlanner.java:392-398` | `offer()` 三段循环（`:394` produced 过滤、`:395` `for(var old:basis) if(le(old.need,n.need)) return;`、`:396` 支配剔除）**全程不调用 `expired()`、不查中断**；`le()`（`:97-99`）是 O(keys) 的 `BigInteger.compareTo`。`:353`/`:363` 的检查只在 recipes 循环**之前**生效 | 大规模环状图（`basis` 大 + `keys` 多）在单次 `offer` 内超时；`:387` 允许 `basis+queue > states*4`（默认 12 万节点），无 deadline 可打断 → 规划工作线程长时间占满 |
| **D3** | `planner/CompiledProductionGraph.java:13`、`:69` | `private record Signature(int keys,List<Flow> flows)`，`Flow(Map<Integer,Long> in,out)` 里的 key 是**请求局部下标**；签名不含 key 身份。类注释自承「Recipe numbers are request-local positions and are rebound by the existing bridge.」 | 两次不同请求的配方数相同、flow 结构相同但涉及不同材料 → 命中同一个缓存图（TTL 60s、weight 上限 262144、最多 32 条），编译期 rebind 到错误的 key |

### 3.2 P1

| 编号 | 位置 | 机制 | 触发条件 |
|---|---|---|---|
| **D4** | `compat/CertifiedCpuDispatcher.java:31-37` | 非 `CraftingCPUCluster` 且 `advancedCluster(target)==null` → 打 warn「redirecting to a compatible CPU」后 `return selectAndSubmit(...)`，**静默改道** | 玩家显式选中 ECO/Trinity CPU，或选中取不到 `cluster` 字段的 `AdvCraftingCPU` 子类 |
| **D5** | `compat/CertifiedCpuDispatcher.java:74-75`、`:77` | 高级 CPU 判定用**类名精确全等** `"net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU"`；`cluster` 字段用 `getDeclaredField`（只看叶子类）。子类或字段在父类的实现一律判不兼容 | 与 D4 组合：玩家选中的高级 CPU 被判不兼容 → 静默改道到别的 CPU |
| **D6** | `compat/CertifiedCpuDispatcher.java:66-70` | `canAutoSelect` 反射失败一律 `return true` | CPU 的 `isExcluded` / `canBeAutoSelectedFor` 判定失效，被排除的 CPU 仍会被自动选中 |
| **D7** | `route/RouteApply.java:43` | `try{...decodeKey}catch(Exception ignored){}` 空 catch 吞掉原因，`:45` 统一报「「label」材料或样板标识无效」 | 任何解码异常（含真正的编码 bug）都无法区分 |
| **D8** | `mixin/ProductionCpuMixin.java:138-146` | `link.insert` 返回 0 → 判为「无 requester」→ `grid.getStorageService().getInventory().insert(...)` 改投 ME、`toNetwork=true`、计入 `accepted`、扣 `remaining`。**javap 证明 0 有两种含义**：无 requester / requester 本 tick 拒收 | 机器型 requester 当 tick 拒收（库存满/离线），或 `CraftingLink` 的 tie 尚未建立 → 请求者永久收不到货，成品进了网络库存 |
| **D9** | `mixin/ProductionCpuMixin.java:175-183` + `planner/ProductionJob.java:100`、`:120` | `readFromNBT` → `ProductionJob.load(...)` → `validateRemainingTasks(remaining)` 的返回值**只用于打日志，不 cancel**。校验失败 → `invalid=true` → `allows(p)` 恒假 → `:94` 的 `pushPattern` 包装恒 `false` | 存档恢复时 cursor 侧（`ScaledPatternCompat.decode`）与 job.tasks 侧（`PatternDetailsHelper.decodePattern`）是两条不同解码路径；任何没重写 `equals` 的第三方 `IPatternDetails` 必然判不等 → CPU 每 tick 推 0 → **任务永久卡死、材料不归还** |
| **D10** | `mixin/ProductionCpuMixin.java:165-169` | `writeToNBT` TAIL 直接调 `production.save(registries)`，**无 try/catch**；而 `:170-188` 的 `readFromNBT` 全包 try。读写保护不对称 | 样板在任务进行中被改动 → `ScaledPatternCompat.encode` 抛 `IllegalArgumentException`（`compat/ScaledPatternCompat.java:31-48`）→ 异常从 `CraftingCpuLogic.writeToNBT` 冒出，区块 `saveAdditional` 中断 |
| **D11** | `planner/ProductionJob.java:142-173`（写）vs `:193`（读） | `save` 对程序 word 长度**不设上限**；`load` 里 `if(steps.isEmpty() || steps.size()>16) throw new IllegalArgumentException("Invalid word size")` 直接拒收整单 | `ProductionPlanner.java:285-305` 的稳态聚合会把窗口内多个 block 的 word `addAll` 拼接；一旦拼出 >16 步，save 照写、重启时 load 抛错 → 返回 invalid job → 落入 D9 的永久卡死路径 |
| **D12** | `planner/DagBatchPlanner.java:53-54`、`:76-78` | `:54` `if(!root && stock[key].signum()>0){color[key]=2;return true;}` —— **有库存即视为「已解决」（假成功）**；`:77` `reserve[k]=reserve[k].max(BigInteger.valueOf(graph.seeds[r][i]))` —— 多配方贡献用 **max 合并而非求和** | dag 快路复用同一中间物给多个下游时低估需求，交给后续精确校验才发现（或误判可行） |
| **D13** | `route/RouteCanvasPlanner.java:73`、`:49` | `:73` `r.id().pattern()==pattern` 是**引用相等**；`:49` 只比 `chosenChild.key().equals(input.getKey())`，**不比 amount/choiceId** | 同一 `IPatternDetails` 的等价副本（重新解析产生的不同实例）匹配失败 → UNSUPPORTED「精确输入分配无法建模」；或输入数量变了但 key 没变时被放行，`:50` 用新 demand 算 `multiplyExact` |
| **D14** | `route/ForcedRoutePlanner.java:50`、`:85`、`:88` | 三处 `throw new IllegalStateException("Unproven branch"/"Physical stock double spent"/"Target is not a net increment")`，**不是 `ProductionPlanningException`**；`:90` 只把 `ArithmeticException` 转成 LIMIT | 触发时异常穿透 `RouteCanvasPlanner` 的 catch（只 catch `ProductionPlanningException`/`ArithmeticException`）→ 变成 `planAsync` 里的 `RoutePlanView.error("规划失败："+msg)`，丢失「这是证明内部矛盾」的语义 |
| **D15** | `mixin/GrowthCpuMixin.java:61-67` | pending 只统计 `possibleInputs[0]` | 多候选输入槽的反馈种子被低估 → 本该留作种子的物品被放行 → 净产出归零 |
| **D16** | `api/CraftRouteCrafting.java:111` | `var registries = policy.empty()?null:requester.getActionSource().player().orElseThrow().registryAccess();` —— **无消息的 `orElseThrow()`** | 自动化 requester（无 player）→ 抛裸 `NoSuchElementException`，而不是可读的规划失败 |

### 3.3 P2

| 编号 | 位置 | 机制 | 触发条件 |
|---|---|---|---|
| **D17** | `vm/NumericCraftingVM.java:59-62` | `case LIMIT_OUTPUT` 里 `count = Math.min(count,(Long.MAX_VALUE-working[key])/delta)`，**未防 `delta==0`** | 数值程序含 0 常量作增量 → `ArithmeticException` 除零。对比：`:53-57` 的 `CHECK_PREFIX` 已正确防了 `delta<0` |
| **D18** | `vm/NumericCraftingVM.java:49` | `for(int pc=program.entry(recipe); ; pc++)` 每指令都查 `expired`（**不会静默死循环**），但**无 pc 越界防护** | 程序缺少 `HALT` → `program.opcode(pc)` 抛 `ArrayIndexOutOfBoundsException` |
| **D19** | `planner/ProductionJob.java:124`、`:137` | `extract` 以 `variant.slots().size()` 循环，直接 `var input=p.getInputs()[i];`，**无长度守卫** | `slots` 比 `p.getInputs()` 长时越界（样板被改动后 slots 变化） |
| **D20** | `route/RoutePlanningService.java:291` | `stopped` 只 `cancelJobs;clearReuse;clearPlanningCaches;WORKERS.purge();`，**无 `shutdownNow()`**，且 `WORKERS` 是 `static final` 不可重建 | 单人游戏反复退出/进入世界时线程池与队列残留（daemon 线程，不阻塞关服，但状态不干净） |
| **D21** | `route/RouteNetwork.java:107` | `RouteSessions.put(grid,player.getUUID(),policy)` 在 switch **之后**无条件执行，`query/preview` 同样 put | 只读查询也会写会话（`RouteSessions.put` 对 `empty` 会 remove，所以多数情况是 no-op，但语义噪声 + 与 `begin` 语义混淆） |
| **D22** | `route/RouteNetwork.java:91` | `clear` 把 `policy` 换成空 policy，即「撤销全部路线改动」 | **没有「只撤 canvas」的入口**：应用了强制画布后想撤销只能连 rules 一起清 |
| **D23** | `compat/ScaledPatternCompat.java:34-37` | `getOperationsPerPush` 必须 `instanceof Long`，否则抛「Invalid scaled pattern multiplier」 | 第三方 scaled pattern 返回 `int`/`Number` → 整个网络规划失败 |
| **D24** | `compat/ScaledPatternCompat.java:71-84`、`:89-91` | 从存档里的类名字符串 `Class.forName` 反射实例化，只有 `getSimpleName().startsWith("Scaled") && contains("Pattern")` 的名称前缀白名单；3 参构造传 `level`（可能为 null） | 类名被替换/模组被移除后仍能执行到匹配的构造器；`level==null` 时在远端 NPE |
| **D25** | `route/RouteSnapshot.java:57-58` | `for(var input:pattern.getInputs()) for(var stack:input.getPossibleInputs())` —— 有 `if(stack==null)continue;`，但**未防 `getPossibleInputs()` 返回 null 数组**（增强 for 直接 NPE），也未防 `stack.what()==null` | 第三方样板返回 null 数组 |
| **D26** | `route/RouteCanvasPlanner.java:92` | `Math.multiplyExact(Math.multiplyExact(out.amount(),e.getValue()),out.what().getAmountPerUnit());` **结果被丢弃**，只为触发溢出检查 | 语义隐晦（易被后来者当死代码删掉）；删掉就丢溢出保护 |
| **D27** | `mixin/CraftingServiceMixin.java:96` | `plan instanceof CertifiedCraftingPlan && !compatible` 分支：`:77` 已对 Certified 计划直接 return，此处**永不成立**（死代码） | 维护陷阱：看起来「重试循环里保护了 certified 计划」，实际不执行 |
| **D28** | `planner/NetworkProductionPlanner.java:100-104` | 自己估算的 `bytes` 与 AE2 的 CPU 容量估算口径不同 | 估小 → 中间物滞留在 CPU；估大 → 够用的 CPU 被拒 |
| **D29** | `planner/ProductionJob.java:190`、`:202` | 反序列化只限 `blocks.size()>120_000` 与 `steps.size()>16`，无总 workload 上限；输出指纹用 `ListTag.equals`（顺序敏感） | 损坏/构造的 NBT 长时间阻塞主线程；输出顺序变化 → 整单判「Pattern outputs changed」作废 |
| **D30** | `route/RouteView.java:42-58` | `Reply` 是 10 参 record + 3 个便捷构造 + `withCanvas`/`withRequestTarget` 都是**全字段复制** | 以后加字段时任一构造漏传 → 静默丢字段（`requestTarget` 就是这么加进去的） |
| **D31** | `mixin/AdvancedCraftingClusterMixin.java:22-35` | 声明了 `cancellable` 却从不取消，且每次 CPU_OFFLINE 都无节流打 warn | 与 `CraftingServiceMixin` 的重试循环叠加 → 日志洪水 |
| **D32** | `compat/advancedae/AdvancedAECompat.java:52-55` | `logged=true` 写在 `isDebugLogging()` 判断之前 | 首次 debug 关闭后，兼容确认信息永远不打印 |
| **D33** | `mixin/RouteTreeWidgetMixin.java:19-20` | `draw` 每帧调用 `getMousePoint()`（返回新的 `java.awt.Point`）并对 map 查一次 | 合成树长开时持续分配 → GC 压力 |
| **D34** | `planner/ProductionJob.java:51-53` | `readyToDeliver` 自称交付闸门，**无任何调用点** | 按注释推理会得出错误的交付时机 |
| **D35** | `planner/ProductionPlanner.java:106-109` vs 调用点 | `expired()` 本身是**双条件**（中断抛 `CancellationException`；超时返回 true），但只在 `:136/:167/:325/:338/:341/:353/:363` 这些「循环外壳」被调用 | D2 的根因。注释里若写「每步都查」是错的 |
| **D36** | `route/RouteNetwork.java:128`、`:131`、`:133`；`route/RoutePlanView.java:93` | 三处裸用第三方返回值：`:128` 只判 `possible.length == 0` 而**没判 `possible == null`**（NPE 在 `.length`）；`:131` `possible[0].what()` 直接取下标且不判 `possible[0]` 为 null；`:133` `p.getOutputs().stream()` 与 `RoutePlanView.java:93` `outputs.isEmpty()` 都没判 `getOutputs()` 返回 null。对照：`route/RouteCanvasPlanner.java:99-100` 对同一接口写全了 `options==null \|\| options.length==0 \|\| options[0]==null` 并抛受控 `UNSUPPORTED` | 原生 AE2 样板恒返回非 null 数组/列表，故只在**第三方 `IPatternDetails` 实现**（ATM10 里 ExtendedAE/AdvancedAE 等）返回 null 时暴露；暴露点正是 `planAsync` 的 reply 构建，会把整次画布刷新打成异常 |

### 3.4 已核实**不成立**（避免重复上报）

这些是其他审计/PRD 提出但我逐行核对后**否掉**的，不要再当 bug 报：

| 说法 | 实际情况 |
|---|---|
| 「`expired()` 只看中断标记」 | 错。`planner/ProductionPlanner.java:106-109` 是双条件 |
| 「`route/RouteSnapshot.java:57-58` 未判空」 | 部分错。有 `if(stack==null)continue;`；真实风险只剩 `getPossibleInputs()` 返回 null 数组（见 D25） |
| 「`NumericCraftingVM` 无任何防护会死循环」 | 错。`:49-50` 每条指令都查 `expired.getAsBoolean()`；真实问题见 D17/D18 |
| 「缺少 logout/停服清理钩子，会话泄漏」 | 错。`CraftRouteAddon.java:48-53` 注册了 `PlayerLoggedOutEvent`（`RouteSessions.remove(UUID)`）与 `ServerStoppedEvent`（`RouteSessions.clear()`）；`RouteSessions.remove` 会 `RouteNetwork.cancelPlanning` |
| 「`RouteApply.apply()` 是线上路径」 | 错。HEAD 无调用点，见 2.3 |
| 「`mixin` 里用了 `@Local` 会炸」 | 错。没有使用 `@Local`/`@Share`；`@WrapOperation` 描述符与 AE2 19.2.18 逐字节吻合 |
| 「`CertifiedExecutionScope` 的 ThreadLocal 会泄漏」 | 错。finally 里设置、嵌套时恢复 previous |
| 「`ProductionPlanCache` 键漏字段导致串用」 | 错。Key 覆盖 target+amount+有序全量 flow+stock，且只缓存 FEASIBLE |
| 「`CompiledProductionGraph` 的 weight 维护有并发缺陷」 | 未证实。在 synchronized 内成对维护，淘汰循环不会在空表上 `next()`。（但 D3 的**签名语义**问题成立。） |
| 「AdvancedAE/NeoECO 签名不匹配会 NoClassDefFoundError」 | 未证实。javap 核对 `AdvCraftingCPU.cluster` 私有 final、`submitJob` 四参、`TaskProgress.value` 为 long 均一致；`@Pseudo` 保护缺失模组的情况 |

### 3.5 空指向专项扫描（null-dereference sweep，D36 的来源）

「遍历检查空指向」不能只看缺陷清单，得按模式扫全树。方法：`git grep -n --fixed-strings <模式> HEAD -- src/main/java`（直接扫提交对象而非工作区，避免读到并发团队的脏文件），逐条回读上下文判断有无守卫。7 组模式的结果如下（可复核，不是印象）：

| 扫描模式 | `git grep` 命中（`HEAD:src/main/java`） | 结论 |
|---|---|---|
| `orElseThrow(` | **5 处 / 4 文件**：`api/CraftRouteCrafting.java:111`（**无消息**）、`route/RouteCanvasPlanner.java:28`（带 `ProductionPlanningException`）、`route/RouteWorkbench.java:64`（带 `IllegalArgumentException`）、`selftest/SelftestHarness.java:477`、`:702` | 只有 `CraftRouteCrafting.java:111` 是缺陷 = **D16**（自动化 requester 无 player 时抛裸 `NoSuchElementException`）；其余 3 处都有兜底异常或属测试代码 |
| `.getFirst()` | **11 处 / 5 文件**：`planner/ProductionPlanner.java:380/381`（`:379` 已判 `word.size()==1`）、`route/RoutePatternEncoder.java:47/48`（`:47` 已判 `outputs.size()!=1`）、`route/client/RouteJeiRecipes.java:176`（`group` 为 grouping 结果，非空）/`:392`（`!emcDevices().isEmpty()`）、`route/client/RouteScreen.java:1640`（`:1639` `sources.isEmpty()` 三元）、`selftest/SelftestHarness.java:539`（`:539` 自带 `rows==null \|\| rows.isEmpty()`）/`:715`（测试） | 全部有守卫，**无缺陷** |
| `.get(0)` | **11 处 / 8 文件**：`route/client/RouteClient.java:39`（`:38` `!draft.outputs().isEmpty()`）、`route/client/RouteJeiPlugin.java:113`（整段在 `:107 try` 内）、`route/client/RouteScreen.java:349`（`RecipeDraftImport.read` 的 `:33` 保证 outputs 非空）/`:653`（`:649` `found.isEmpty()`）/`:1013`（`:1012` `!plan.steps().isEmpty()`）、`route/client/RouteWorkbenchPanel.java:164`（`:163` `!state.providers().isEmpty()`）、`route/RecipeDraftImport.java:26`（`:25` 已判 `slot.alternatives().isEmpty()`）、`route/RouteIntent.java:53`（`byKey` 只装至少一个 node 的 key）/`:68`（调用方保证非空列表）、`route/RoutePlanView.java:95`（`:94` `outputs.isEmpty()`）、`route/RouteWorkbench.java:157`（`shown.isEmpty()` 三元） | 全部有守卫，**无缺陷** |
| `getPossibleInputs()` | **15 处 / 14 文件**（含 1 处纯注释 `compiler/IFiniteUseInput.java:25`），实调用 14 处：**写全了 null / 长度 / 元素判空**的 11 处（`route/RouteCanvasPlanner.java:99`、`planner/FiniteToolTransitions.java:13`、`planner/FuzzyFamilyAllocator.java:35`、`planner/PatternVariants.java:27`、`vm/NetProduction.java:16`、`vm/TwoNodeGrowthPlanner.java:40`、`mixin/GrowthCpuMixin.java:62`、`mixin/AdvancedGrowthCpuMixin.java:66`、以及 `planner/NetworkProductionPlanner.java:52` 用 `options!=null` 前置守卫）；**自身没判 null 的 4 处**：`route/RouteNetwork.java:128`、`route/RouteSnapshot.java:57`、`planner/PatternVariants.java:106`、`planner/PatternVariant.java:25`（后两处由**上游** `PatternVariants.java:27-28` 的 `options==null` 早退保护，`PatternVariant.recipe()` 另由 `:17 slots.size()!=pattern.getInputs().length` 前置，故只在「样板实现在两次调用之间被换成返回 null 的实现」时暴露，记为上游依赖型） | D36 + D25 成立；`PatternVariants.java:106` / `PatternVariant.java:25` 记为**上游依赖型**，不单独计缺陷 |
| `getOutputs()` | **20 处 / 11 文件**，是**比 `getPossibleInputs()` 更宽的无守卫面**：只有 `vm/TwoNodeGrowthPlanner.java:34`（`p.getOutputs()==null`）与 `planner/NetworkProductionPlanner.java:59`（`out!=null && out.what()!=null` 元素判空）做了防御；`route/RouteNetwork.java:133`、`route/RoutePlanView.java:93`、`route/RouteSnapshot.java:49/60`、`route/RouteCanvasPlanner.java:41/58/92`、`planner/PatternVariant.java:19`、`planner/ProductionJob.java:135/163/201`、`route/RoutePlanningService.java:95`、`vm/NetProduction.java:14` 全部直接迭代 | 与前一项同根：原生 AE2 恒给非 null `List`。**单独成条**的只有 `RouteNetwork.java:133` 与 `RoutePlanView.java:93`（在画布 reply 的关键路径上）——已并入 **D36**；其余位于规划期，出错会被 `ProductionPlanningException`/`catch(RuntimeException)` 兜住变成「规划失败」，不静默 |
| `orElse(null)` | **6 处 / 3 文件**：`route/RoutePatternEncoder.java:27`、`route/client/RouteJeiPlugin.java:110/133`、`route/client/RouteJeiRecipes.java:151/280/296` | 都是显式可空语义 + 下一行立即判空（如 `RouteJeiRecipes.java:280` → `:281 if(typed==null)return;`），**无缺陷** |

**结论**：全树的空指向风险不是「到处都有」，而是**高度集中在两个相邻的模式上**：①对第三方接口 `IPatternDetails.IInput.getPossibleInputs()` 的返回值只做「长度检查」不做「null 检查」；②对 `IPatternDetails.getOutputs()` 干脆连长度都不查、直接 `for` 迭代（20 处里只有 2 处防御）。同一接口在 `RouteCanvasPlanner` 里写对了（抛受控 `UNSUPPORTED`），在 `RouteNetwork`/`RouteSnapshot` 里漏了。`javap appeng.api.crafting.IPatternDetails$IInput` 证实 `getPossibleInputs()` 的返回类型是 `GenericStack[]`（可变数组，接口未规定非 null），`IPatternDetails.getOutputs()` 返回 `java.util.List`（同样未规定非 null）——所以**原生 AE2 实现永远给值，但第三方样板实现可以给 null**，这正是 D36 的触发面。

### 3.6 第二轮全树逐行补出的服务端条目（D37–D41）

第一轮只精读了 planner/route/mixin/compat 的关键文件；第二轮把 `src/main/java` 下 **129 个 `.java` / 12165 行**全部逐行读完（快照见附录 A），服务端侧只多出下面 5 条，且全部是 P2。**这不是「漏了很多 bug」，而是这 5 条属于「不影响运行、影响可维护性/一致性/诊断可信度」的那一类**：

| 编号 | 优先级 | 位置（HEAD `1c7ee64`） | 机制 | 触发条件与后果 |
|---|---|---|---|---|
| **D37** | P2 | `route/RouteApply.java:46` vs `route/RouteGraphMath.java:16` | 路径正则深度上限不一致：`RouteApply` 用 `root(?:/[0-9]{1,3}){0,24}`，`RouteGraphMath.childPath` 用 `root(?:/[0-9]{1,3}){0,23}`（全树仅此一处 `{0,24}`） | 服务端接受的路径比客户端能生成的**深一层**。当前无人能构造出这一层（唯一生成点是 `childPath`），故不触发；但两层协议各写各的常量，以后任一侧放宽都会变成「客户端发不出的路径服务端照样收」。应提取成共享常量 |
| **D38** | P2 | `telemetry/CpuProbe.java:114-123`（`call`）、`:130-139`（`plain`） | 全反射实现：`subject.getClass().getMethod(name)` 只找 **public 且无参**方法，从叶子类开始；`catch(ReflectiveOperationException \| RuntimeException)` 一律返回 null / 回落 `String.valueOf` | 反射失败**静默**：诊断日志里缺字段而不报错。这是 `:16-20` 注释「guarantees it never changes behaviour」的**有意取舍**，不是 bug；记在此处是为了防止后来者把「诊断缺字段」误判成「CPU 状态异常」。`InaccessibleObjectException`（RuntimeException 子类）也被兜住 |
| **D39** | P2 | `telemetry/LogThrottle.java:9` `private final Map<String, Long> last = new HashMap<>();` | `acquire` / `acquireDelayed` / `reset` 全部**非 synchronized**，底层是普通 `HashMap` | 当前 4 个实例全是 mixin 里的 CPU BE 字段（`ProductionCpuMixin.java:39`/`:42`、`AdvancedProductionCpuMixin.java:39`/`:42`），只在 server 线程 tick 调用 → **不是并发缺陷**。但类本身没有任何线程契约声明（`@NotThreadSafe` 或「仅限单线程」的 javadoc），谁把它挪到客户端或网络线程就会静默损坏 map |
| **D40** | P2 | `compat/thunderbolt/ThunderboltCompat.java`（13 行）、`compat/thunderbolt/CraftRouteBatchCraftingPlanner.java`（13 行）、`CraftRouteAddon.java:56-60`、`:96-101` | ①两个 `compat/thunderbolt/*` 文件是**纯注释 stub**，无任何类声明；②`CraftRouteAddon.java:101` 的 `ThunderboltCompat.registerIfPresent()` 已被注释掉；③但 `CraftRouteAddon.java:56-60` 的大段注释仍在描述「通过 `CraftingPlanningEngines.register(CraftRouteBatchCraftingPlanner, 900, false)` 注册到 Thunderbolt 多引擎体系」 | 代码早已在 v1.13.16 剥离（stub 的注释自己写了原因：`NoClassDefFoundError: com/moakiee/thunderbolt/api/crafting/CraftingPlanningEngine` 会在静态初始化时崩游戏），但**主类里那段注释没跟着改**。后来者按 `CraftRouteAddon.java:56-60` 推理会得出「我们注册了第三方引擎路由」的错误结论。注意：真正还在生效的是 `compat/ThunderboltBatchCompat.java`（`:100` 调用，只注册 batch 资格规则，不接管引擎）——两者名字接近、作用完全不同 |
| **D41** | P2 | `config/CraftRouteConfig.java:52-53` vs `:30-32` | ①`scrollEditsQuantity` 的 comment 是**唯一只有英文、没有中文**的一项（`proxyEnabled`/`debugLogging` 都是中英双语 + 「Requires Restart」）；②它的 getter `:31` 用 `catch (IllegalStateException e)` 回退，而 `isProxyEnabled`/`isDebugLogging` 用 `catch (Throwable t)` | 未注册配置（COMMON spec 尚未加载）时三个 getter 的**兜底覆盖面不同**：`Throwable` 能兜 `NullPointerException`（字段为 null 时会抛，注意 `ModConfigSpec.BooleanValue` 未 build 时 `SCROLL_EDITS_QUANTITY` 为 null → `SCROLL_EDITS_QUANTITY.get()` 抛的是 NPE 而非 ISE）→ `scrollEditsQuantity()` 在这种情形下会**抛异常穿透**到 `RouteScreen.java:1353/:2109/:2115` 的渲染/滚轮路径。实际不可达（`CraftRouteAddon.java:44-45` 在构造函数里先注册 spec），但三者写法不统一本身就是隐患 |

「D41 的 NPE 不可达」有实证：`CraftRouteAddon.java:44-45` `ModList.get().getModContainerById(MOD_ID).ifPresent(container -> container.registerConfig(ModConfig.Type.COMMON, CraftRouteConfig.COMMON_SPEC))` 在 `commonSetup` 之前执行，而三个调用点全在客户端渲染/滚轮事件里。

**第三轮再补了 5 条服务端条目 D42–D46**（`route/RouteWorkbench` 的编码失败丢样板 / 坏缓存槽锁死面板、`route/RouteNetwork` 的溢出错误文案为 null 与 lock-ban 被单张坏样板带崩、`route/RouteWorkbench` 的槽位分页只按第一个供应器）。同一轮还有 5 条客户端条目 C17–C21，为免分散阅读，服务端与客户端合成一张表放在 **§8.1**，此处不重复。

---

## 4. 未验证假设（需要真机才能确认）

| 编号 | 假设 | 为什么需要真机 |
|---|---|---|
| **A1** | 画布应用的路径数 ≤128、单请求 ≤24000 字符这两个上限在真实配方（ATM10 六位数量级配方树）下够用 | 静态看不出玩家画布实际规模；超限只有 status 文本提示，无自动降级 |
| **A2** | `ScaledPatternCompat.encode` 对 ATM10 里所有 scaled pattern（ExtendedAE / AdvancedAE / ae2lt / ae2cs / extendedae_plus）都能成功 | `RouteIdentity.java:17-20` 在缺 definition 时抛的是**受控** `RoutePlanningException`（「样板没有稳定标识，暂不支持手动锁定」），不是裸 NPE；但这条路没在 ATM10 实测过 |
| **A3** | `RouteCanvasPlanner` 用 `possibleInputs[0]` 作为「主输入」（`:96-104`）在所有样板形态下与 AE2 实际推样板时的取法一致 | 若不一致，`:50` 算出的子需求会与实际执行不符；`RouteGraphMath.MAX_DEMAND=1e12` 只是上限保护 |
| **A4** | `CertifiedCpuDispatcher` 的反射路径在 AdvancedAE 1.6.12 + NeoECO 组合下真的能拿到 `cluster` 字段 | 在 jar 上核对了字段名与签名，但没在运行时验证 `getDeclaredField` 在混淆/重映射下的稳定性 |
| **A5** | 规划工作线程（2 个 daemon，队列 8，`AbortPolicy`）在单人集成包里足够 | `AbortPolicy` 在队列满时抛 `RejectedExecutionException`，其上层是否兜住要看真机多线程并发 apply 的情况 |

---

## 5. 对 `docs/canvas-to-execution-linkage.md` 的增量与更正

那篇文档基线是 `dfa2aea`，本次在 `1c7ee64` 上重核。增量：

1. **新增两环**：`RouteApply.prepare` → `RoutePolicy.Canvas` 的构造（含 128 分支 / 路径正则 / 空路径校验）与 `RouteCanvasPlanner` → `ForcedRoutePlanner` 的「强制分支网络 + 共享库存证明」方法，是原文档没有展开的。原文档把画布描述成「一小组可证明的硬约束」，但没有说明**怎么证明**——答案在 `ForcedRoutePlanner:29-46`（每分支一条网络 + `Marker("\u0000goal")` 伪配方 + `ProductionPlanner.solve(goal,1)`）。
2. **更正（会话判定）**：原文档 §2.1（`canvas-to-execution-linkage.md:133-143`）把「**只要存在手动规则**，就只走 `CraftRouteCrafting.calculate`；失败不回落原生」写成承重契约，并引 `mixin/CraftingServiceMixin.java:147` 为「配套的第二半」。这句就是 D1 的上游表述：它把会话是否介入**定义成 rules 非空**，而 `route/RoutePolicy.java:14` 的 `empty()` 实际是 `rules.isEmpty() && canvas==null`。代码里 `CraftingServiceMixin.java:139`/`:147` 两处都把「会话是否介入」写成 `rules().isEmpty()` 的取反，**canvas-only（有画布、无手动规则）会话因此被归到「不存在」**：`proxyEnabled=false` 时既不报「AE-Craftroute 已关闭」，也不走隔离失败路径，直接放行原生 AE2 递归计算、画布约束整条丢掉（`proxyEnabled=true` 时因为 `:147` 判 false 会落到 `:162` 分支，仍能经 `planFrozen` → `RouteCanvasPlanner` 正常规划，所以缺口只暴露在开关关闭与失败隔离两条路径上）。因此不是「没接线」，而是**接线完整、判定口径错**（同类问题还有 D4 静默改道、D8 交付改道）。
3. **补充**：原文档 §7.1 提到的「执行进度上行未实现」确认成立且有据：`RouteView.Reply` 的 10 个字段里没有任何执行态；`RouteNetwork` 也不接收执行进度。
4. **补充**：`RouteApply.apply()` 是遗留 API（无调用点）——原文档把它当成主路径之一。

---

## 6. 修复顺序与回归门禁

### 6.1 建议修复顺序（每批独立可回滚）

**第 1 批 —— 会话与选择语义（改动小、危害大、无需真机就能验证）**

1. **D1**：`CraftingServiceMixin.java:139/:147` 改用 `policy.empty()` 语义（或 `rules().isEmpty() && canvas()==null`），与 `RoutePolicy.java:14` 对齐。
2. **D4+D5**：`CertifiedCpuDispatcher.java:31-37` 显式不兼容时**不再改道**，返回明确失败（如 `CraftingSubmitResult.noSuitableCpu` + 玩家提示）；`:74-83` 的类名判定改为「沿父类链 + 类名前缀」，字段查找沿父类链。
3. **D7**：`RouteApply.java:43` 的空 catch 至少保留 cause（例如附 `getClass().getSimpleName()+": "+message`）。
4. **D16**：`CraftRouteCrafting.java:111` 的 `orElseThrow()` 换成带消息的、或改为可空降级（无 player 时走无 registry 的快照路径）。

**第 2 批 —— 执行正确性（必须有真机/存档往返验证）**

5. **D9 + D11**：`ProductionJob.validateRemainingTasks` 失败时**必须 cancel/清任务**；`save` 与 `load` 的 word 上限对齐（写入前校验或读时容忍）。
6. **D8**：`link.insert` 返回 0 时区分「无 requester」（`link` 的 tie/requester 为空）与「本 tick 拒收」（保留在 inventory，下 tick 重试），不要改投 ME。
7. **D10**：`writeToNBT` 的 `save` 包 try/catch，异常时记录并跳过保存（不可让区块保存被打断）。
8. **D2**：`offer()` 内加 `expired()` 短路（每 N 次迭代或每次），并给 `le()` 的支配扫描加上界。

**第 3 批 —— 缓存与快路（影响正确性但触发条件窄）**

9. **D3**：`CompiledProductionGraph.Signature` 加入 key 身份（或直接按请求隔离缓存）。
10. **D12**：`DagBatchPlanner` 的「有库存即已解决」改成「库存足够才已解决」；`reserve` 由 max 改 sum（或明确注释这是上界估计）。
11. **D13**：`RouteCanvasPlanner:73` 的引用相等改 `equals`，`:49` 补 amount 比较。

**第 4 批 —— 健壮性**

12. **D17/D18/D19/D20/D21/D22/D23/D24/D25/D26/D27/D28/D29/D30** 按 P2 逐条清。
**第 5 批 —— 客户端交互（第二轮新增，全部纯客户端、可独立验证）**

13. **C9**：`RouteClient.open(...)` 里先记住旧 `RouteNetwork.clientHandler`，`RouteScreen.onClose()` 恢复它（或改成 handler 列表按 screen 实例过滤）。这一条最该先做：改动 < 10 行，直接消除「第二个画布打开后第一个饿死」。
14. **C11**：`RouteTreeGeometry.constrain` 的 `Math.max` 只允许单向推挤；改成「先按目标坐标放置，再只对**重叠**做最小位移」，或把向上/向左的合法位移单独放行。同时把 `RouteTreeGeometryTest` 的断言从「约束满足」扩成「约束满足 **且** 合法位移被保留」。
15. **C8**：`RouteScreen.java:1427` 的第三参从 `!branches.isEmpty()` 改成「本节点是否被本地编辑过」（按 `offsets`/`branchEdits` 里该 `node.path` 是否存在判断）。
16. **C10**：`RouteClient.java:44` 的 `if (key == null) key = planned;` 不要在兜底后仍让六参 `:64` 的 `else if(key==null)` 依赖它；改成显式传「amount 是否需要从 confirm 取」的布尔，或在 `:64` 前先按 `key==null` 判过再兜。
17. **C12**：`RouteJeiRecipes.select` 的早退加 `&& error == null`，并把 `error` 在成功时清空。
18. **C13/C14/C15/C16** 按 P2 逐条清；**C14** 只需在 `RouteNativePatternIcon` 的 `:15-20` 后再加一次兜底（`var key = ...; return key != null ? key : AEItemKey.of(AEItems.PROCESSING_PATTERN.stack());`），改动 2 行。

**服务端 P2 里的 D37–D41** 不阻塞任何功能，可在最后一并清（D37 提取共享路径正则常量、D40 修主类那段过时注释）。

**第 6 批 —— 第三轮亲核新增（P2，除 C17 外都不阻塞）**

19. **C17（唯一 P1）**：`route/client/RouteJeiRecipes.java:490` 的 `RouteJeiPlugin.readDraft(...)` 包 try/catch(RuntimeException)，失败时 `error=e.getMessage()` 且不设置 `held`（保持 `held` 为 null，拖动不生效），与同文件 `currentDraft():205`/`load():301` 的写法一致。这一条要与第 5 批一起做——它是**唯一会把异常抛进客户端鼠标事件**的条目。
20. **D42**：`RouteWorkbench.storeEncoded` 把「扣空白样板」改成先 MODULATE 再写 cache、或失败时把 `encoded` 退回玩家背包/网络库存；至少文案要说清样板已丢失。
21. **D43**：`RouteWorkbench.view()` 与 `takeToBackpack()` 的缓存槽循环逐槽 try，坏槽以「无法读取」占位并允许直接丢弃（否则一个坏槽永久锁死面板）。
22. **C18**：`RouteWorkbenchPanel.top()` / `RouteLayout.cacheReservedHeight()` 在展开时要反映真实高度（让 `RouteScreen.inspectorBottom()` 拿到正确值），否则窄窗口下编码按钮被盖住且被 `workbench.click` 先消费成死键。
23. **C19**：`RouteScreen.removeBranch` 的 `:149 hideMaterial` 要么去掉（只按 path/parent 隐藏），要么在状态栏明确写「同材料的其它分支也隐藏了」并给一键恢复入口。
24. **C21**：`RouteJeiRecipes.select()` 里 `catalystCache.clear()`（或只缓存成功结果），消除「设备候选永久为空」。
25. **D44/D45/D46、C20** 按 P2 清：`:131` 包 try 给出可读文案（D44）、`:99-101` 逐张样板 try（D45）、`slotPages` 取所有 shown 供应器的最大页（D46）、`render():322` 前夹一次 index（C20）。

### 6.2 回归门禁

改动前**先记录基线数字**（本机 gradle 8.8 + JDK 21，离线缓存）：

```bash
JAVA_HOME=C:/Users/xun/.gradle/jdks/jdk21/jdk-21.0.12.1+1 \
C:/Users/xun/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle.bat \
  --offline test routeFunctionalTest build -x productionBenchmark
```

| 门禁 | 命令/方式 | 通过标准 |
|---|---|---|
| 单元测试 | `gradle test` | failures=0 errors=0（基线在 local.52 是 486 项，需在 `1c7ee64` 上重测确认） |
| 路线功能测试 | `gradle routeFunctionalTest` | 同上 |
| 端到端自检 | `runSelftestServer -PselftestTask=paths\|growth\|cancel\|reload` | 四个子任务全绿；`reload` 直接覆盖 D9/D11 的存档往返 |
| 交付链路（真机） | 画布 apply → CPU 执行 → 请求者收到货 | 覆盖 D8；建议用机器型 requester（ME 接口/样板供应器）与高级 CPU 各跑一遍 |
| 存档往返（真机） | 任务执行中停服 → 重启 → 任务继续 | 覆盖 D9/D10/D11 |
| 性能 | `productionBenchmark` | **不作门禁**（本来就 `-x`），仅记录 |

### 6.3 单文件回滚面

第 1 批每一条都能单独 revert，且不改变任何回包/协议格式（`RouteView.Reply` 字段不变），因此可以只回滚出问题的那个文件。

---

## 7. 客户端 / UI 层补充发现（同一 HEAD 基线）

> 本节补 §1.1 表格里「画布（客户端）」那一列的细部。
> **行号口径**：C1–C7 的行号按 `docs/teamflow-run-notes/head-1c7ee64/`（文件名形如 `route_client_RouteScreen.java`）记录，那是第一轮的抽取快照；**C8–C21 的行号按 `docs/teamflow-run-notes/head-sweep/src/main/java/com/aecraftroute/addon/`**（文件名是真实包路径 `route/client/RouteScreen.java`），那是第二、三轮的完整快照。两份都是同一提交 `1c7ee64`，所以同一文件的真实行号一致，只是抽取后的文件名不同。
> 该目录已补齐 client 层快照：`route_client_RouteScreen.java`（2326 行 / 122465 字节 / blob `204611fca36be8edb40a246d34bc97c687b027a1`）、`route_client_RouteJeiRecipes.java`（533 行）、`route_client_RouteRecipeHover.java`（85 行）、`route_client_RouteMachineTray.java`（60 行）、`route_client_RouteMachineGrid.java`（24 行）、`route_client_RouteWorkbenchPanel.java`（227 行）、`route_client_RouteClient.java`（133 行）、`route_client_RouteNodeMenu.java`（34 行）、`route_client_RouteDrafts.java`（32 行）、`route_client_RouteJeiPlugin.java`（150 行）。
> 抽取方式为 `git show HEAD:<path> > file`，每个文件都用 `git hash-object` 与 `git rev-parse HEAD:<path>` 比对过，10/10 完全一致（不是工作区内容）。
> **工作区的 `RouteScreen.java` 是 2373 行 / 127399 字节**（= HEAD 2326 行 + 未提交的 55+/8−，净 +47，行数完全对得上）。读本节时不要用工作区行号。
>
> **第二轮已把覆盖补全**：客户端 `route/client/` 全部 **33 个** `.java` 已逐行读完（不再是最初的 10 个）；同一轮还逐行读完了 `mixin/` 17、`compat/` 8、`api/` 3、`telemetry/` 4、`vm/` 6、`selftest/` 2、`config/`1、`compiler/`1、根 2。快照根 `docs/teamflow-run-notes/head-sweep/src/main/java/com/aecraftroute/addon/`（`git archive HEAD` 解出 129 个 `.java`，与工作区 129 一致）。7.1 的 C1–C7 来自第一轮，C8–C16 来自第二轮，C17–C21 来自第三轮（在 **§8.1**，与 D42–D46 同表）。

### 7.1 成立的缺陷

| 编号 | 优先级 | 位置（HEAD） | 事实（已逐行实读） | 后果 |
|---|---|---|---|---|
| C1 | P1 | `route_client_RouteScreen.java:2174` | `if(keyCode==GLFW.GLFW_KEY_ESCAPE) {onClose();return true;}`。同一个 `keyPressed` 里，`:2145` 只拦节点菜单、`:2147` 只拦数量编辑（条件是 `quantityPath!=null && (itemId==null \|\| !itemId.isFocused())`）、`:2170` 只拦「搜索框聚焦 + Enter」。**搜索框聚焦时 Esc 没有任何专属分支**，直落 `:2174` → `onClose()`（`:2192-2194` `setScreen(parent)`） | 在目标搜索框里按 Esc（期望收起建议/取消焦点）会**整屏关闭画布**，未提交的搜索内容与未 apply 的画布编辑一起丢失 |
| C2 | P1 | `route_client_RouteScreen.java:600-630`（`onSearchChanged` → `searchItems`），触发点 `:541` `itemId.setResponder(this::onSearchChanged)` | `searchItems` 里 `for (Item item : BuiltInRegistries.ITEM)`，每一项 `new ItemStack(item)` + `stack.getHoverName().getString()` + 对 name/id 各一次 `toLowerCase(Locale.ROOT)`；结果超过 256 才截断（`:629`）。**每输入一个字符全量扫一遍**；`setTargetFromField():648` 再扫一遍；`setResponder` 对 `setValue` 也触发 | ATM10 上万物品 → 每个字符一次 O(物品数 × 对象分配) 全表扫描，输入与建议框明显掉帧 |
| C3 | P2 | `route_client_RouteMachineTray.java:51-54` | `if(grid==null \|\| !grid.contains(mx,my))return false; int hit=grid.hit(mx,my,first);if(button==0 && hit>=0)show.accept(values.get(hit));return true;`；而 `route_client_RouteMachineGrid.java:9-13` 的 `hit` 对标题带（`my` 在 `[y,y+18)`）算出 `cy<0` 后返回 -1 | 点在托盘**标题行/空格子**上会返回 `true`（消费掉点击）却什么都不做；同一次点击不会再落到画布（既非选节点、也不开始平移） |
| C4 | P2 | `route_client_RouteScreen.java:1246-1254` `saveGeometryOffsets()` 内 `:1249` | `var parent=node.path.equals("root")?null:findByPath(node.path.substring(0,node.path.lastIndexOf('/')));`。path 不含 `/` 时 `lastIndexOf` = -1 → `substring(0,-1)` 抛 `StringIndexOutOfBoundsException`。同文件 `:144` 的正确写法是 `node.path.contains("/") ? node.path.substring(0, node.path.lastIndexOf('/')) : ""` | 结构性越界。目前 `path` 全由 `RouteGraphMath.childPath` 生成（`root`、`root/0/1`）故不触发；任何新路径来源（自定义恢复、第三方存档）都会直接抛 |
| C5 | P2 | `route_client_RouteScreen.java:1105`（`buildNode` 内），上游 `rebuildGraph():1033-1084` | `if(amounts(candidate.inputs()).equals(amounts(planned.inputs())) && ...)`。`amounts():2264-2271` 里 `stack.decode(...)` 经 `route__RouteView.java:20-26 decodeKey` 抛 `IllegalArgumentException("材料数据无法读取", e)`，`Math::addExact` 抛 `ArithmeticException`。**`matchesDraft():2254-2262` 对自己的调用有 `try/catch(Exception)`，`:1105` 这个调用点没有**；`buildNode` → `rebuildGraph` → `accept()`/`render()` 全链无 catch | 回包里任一 `Stack.key` 无法反序列化（跨版本、模组移除、流体 key）时异常穿出 `accept()`（由 `route__RouteNetwork.java:45` 的 `enqueueWork` 调用）或 `render()` |
| C6 | P2 | `route_client_RouteJeiRecipes.java:256-269`；`route_client_RouteRecipeHover.java:26-35`；调用点 `route_client_RouteScreen.java:1519`（在 `drawCanvas` 内） | `previewRecipeId` 在 `knownId==null && !choice.alternatives() && recipes.size()<=64` 时 `for(i<recipes.size()){selectIndex(i); if(matches(...))...}`，而 `selectIndex():201` 每次 `load()` 重新取 JEI 分类并重建 layout | 悬停目标**变化的那一帧**里做最多 64 次 JEI 布局构建（`RouteRecipeHover.offer` 用三个 `Objects.equals` 去重，所以不是每帧），仍会造成悬停卡顿 |
| C7 | P2 | `route__RouteGraphMath.java:65-72` + `route__RouteView.java:28-31` | `outputAmount` 里 `for (var stack : choice.outputs())` 直接迭代；`Choice` 的紧凑构造器**只归一化 `sources`**（`:29`），`inputs`/`outputs` 不判空。客户端 `RouteScreen.java:238/239/318/351/1129/1483/1635/1691` 都直接调它 | 若回包 JSON 缺 `outputs`（版本错配或第三方构造），客户端 NPE。服务端唯一构造点 `RouteNetwork.java:133` 始终给列表，所以只在跨版本场景暴露 |
| C8 | P1 | `route/client/RouteNodeVisual.java:5-11` + `route/client/RouteScreen.java:1427` | `of(boolean proven, boolean stockOnly, boolean localEdit)` 里 `if (localEdit) return new RouteNodeVisual(0xFF7A5A2E,0xFFFFFFFF,0xE05A4525,"本地编辑·未证明");` **优先于** `if (!proven)`；而唯一调用点 `RouteScreen.java:1427` 第三参传的是 `!branches.isEmpty()` —— **整个画布级别的「有没有任何分支」**，不是「这个节点被本地编辑过」 | 只要画布里存在任意一条分支（几乎总是存在），**每个节点**都被标成棕色「本地编辑·未证明」，包括已证明的节点；真正的 `!proven`（未证明）配色永远不可见。显示层错值，不影响提交 |
| C9 | P1 | `route/client/RouteClient.java:72-75` + `route/RouteNetwork.java:21` + `route/client/RouteScreen.java:2191-2194` | `RouteNetwork.clientHandler` 是**单个静态字段**，`RouteClient.open(...)` 每次开画布都无条件覆盖它，`RouteNetwork.java:45` 的 `playToClient` 只喂这一个 handler；而 `RouteScreen.onClose()` 只有 `Minecraft.getInstance().setScreen(parent);` —— **不恢复、不置空** | 先开画布 A（handler→A），再开画布 B（handler 被覆盖→B）；关掉 B 回到 A 后，A 的 handler 已被覆盖成 B 的 `WeakReference`，**A 再也收不到任何回包**：`material.loading` 永远停在 true、预览/应用/强制画布全部超时（客户端 10 秒）。本轮最容易被真人遇到的交互缺陷 |
| C10 | P1 | `route/client/RouteClient.java:33-51`（三参 open）vs `:53-77`（六参 open） | 三参重载 `:44 if (key == null) key = planned;` 先把 `key` 兜成 `CraftConfirmMenu` 的 `whatToCraft` 才调六参，六参 `:64 else if(key==null)` 因此**永不命中**。真正复算 amount 的是 `:62-63 if(output!=null){key=output.what();amount=output.amount();}`，而 `RouteTreeSnapshot.output(confirm)` 在 `confirm.getPlan()==null` 时已被 `:58-60` 拦下、计划非空但取不到 AE2CT 终产物时返回 null | 当 `output==null` 且 `key!=null` 时 `amount` 停留在 `:39` 的 `draft.outputs().get(0).amount()` 或 `0`，**不回落**到 `aecraftroute$routeAmount()`；下游 `RouteScreen.java:946` 用 `Math.max(1, targetAmount)` 发出 `amount:1` 的预览 → 权威面板按 1 算，与确认页显示的目标数量不符（静默错值，不报错） |
| C11 | P1 | `route/client/RouteTreeGeometry.java:15-34`（`:25-26`）+ `RouteScreen.java:1962`/`:2025`/`:2062` | `constrain` 的 y 约束是 `int y=previousBottom==Integer.MIN_VALUE?box.y():Math.max(box.y(),previousBottom+Y_GAP);` —— `Math.max` **只会往下/往右推**。向上拖子树时 `translate` 先减 y，`constrain` 立刻把该层 y 抬回 `previousBottom+Y_GAP`，而 `previousBottom` 是**上一层的底边**（上层没动）→ 位移被完全吃掉 | 子树的**向上**拖动（向左同理，`:25`）**完全无效**：松手位置不变，视觉上「拖不动」。向右/向下可拖（会被钳到最小间距）。`RouteScreen` 每帧都从 `dragGeometry` 快照重算，不是「逐帧回弹」而是根本不动。单测 `src/test/java/com/aecraftroute/addon/route/client/RouteTreeGeometryTest.java:28-31` 只断言约束满足、没断言合法位移被保留，测不出来 |
| C12 | P1 | `route/client/RouteJeiRecipes.java:272-290`（`select(AEKey)`）、`:301-302` | `select` 首行 `:273 if(Objects.equals(key,this.key))return;` **早退**；`:301-302` 在 `try{layout=create(...)}catch(RuntimeException e){error="此插件的原生配方布局无法创建";}` 之后仍把 `this.key` 设成新 key | JEI 重载 / 第三方插件布局失败后，玩家**重选同一材料被 `:273` 挡回**，`error` 永远保留，侧栏一直显示「此插件的原生配方布局无法创建」。唯一自救入口是 `RouteScreen.java:561-577` 的 `reloadMappings()`，玩家无从得知。应在 `error != null` 时允许重试 |
| C13 | P2 | `route/client/RouteDrafts.java:27-30`（save）+ `:12-19`（load） | `save` 用**固定临时文件名**写盘再 `ATOMIC_MOVE`，失败回落普通 `move`；无锁、无版本号。`load` 解析失败直接 `catch(RuntimeException) → IOException("草稿文件损坏，未覆盖原文件")` | 两个画布窗口（或快速连点保存）并发 `save` 时，一方 `move` 可能读到另一方写了一半的 tmp → 半截 JSON → 之后**所有**草稿都读不出来（`load` 的 catch 是整体失败，不跳过坏条目）。唯一调用点 `RouteScreen.java:2184` 在 `try/catch(Exception)` 里，只表现为 `status = e.getMessage()`，玩家只看到「草稿文件损坏」 |
| C14 | P2 | `route/client/RouteNativePatternIcon.java:15-20` + `:22`（draw）/ `:35`（show） | `for(var source:sources) if(source.icon()!=null) try {return source.icon().decode(mc.level.registryAccess());} catch(RuntimeException invalid) { }` —— **try 里直接 return**，`decode` 返回 null（而非抛异常）时**直接 return null**，绕过 `:19 return AEItemKey.of(AEItems.PROCESSING_PATTERN.stack())` 的兜底；`draw`/`show` 随后把该 null 直传 `AEKeyRendering.drawInGui` | AE2 的 `AEKey.fromTagGeneric`（`appeng/api/stacks/AEKey.java:108-118`）对无法反序列化的 tag **返回 null 且不打异常**（只 `LOG.warn`）。跨版本/模组移除后的样板图标 key 会走到这条路径 → `drawInGui` 内部 `getUnchecked(what)` 对 null 调 `what.getType()` → **渲染期 NPE**。对照 `route/client/RouteNativeMachines.java:15-18` 同类代码写了 `if(key!=null && ...)`，确属漏判 |
| C15 | P2 | `route/client/RouteClient.java:99-110`（`separateTerminalButtons`） | `ScreenEvent.Init.Post` 上遍历 `event.getScreen().children()`，命中文字含「深色/暗色/Dark Mode」的按钮就 `button.setY(button.getY() - 22)`。**无幂等去重、无标记** | 同一 Screen 每次 re-init（窗口 resize、子界面开关、切语言）都会再减 22px → 按钮逐次上漂，最终移出屏幕。仅装 `com.wcwt`（Wireless Comprehensive Work Terminal）时触发，故 P2 |
| C16 | P2 | `route/client/RouteClient.java:57-60` vs `:112-133`（key / F8）与 `RouteScreen.java:484-504`（`beginRetry`） | `:57-60`：从 `CraftConfirmMenu` 打开且 `confirm.getPlan()==null` 时**直接** `displayClientMessage("合成计划尚未计算完成，请稍候再打开画布",true); return;` —— 一次性放弃，无重试、无排队 | 玩家在确认页刚点下去就按 F8（很常见）会看到提示，得再按一次；而同文件 F8 分支与 `RouteScreen.beginRetry`（`BEGIN_RETRY_FRAMES=20`/`BEGIN_MAX_ATTEMPTS=20`）已建好「等计划算完自动续」的状态机，工具栏按钮这条路没接进去。体验/逻辑不一致，不崩溃 |

### 7.2 已核实不成立（第三方报告里被我否掉的，避免照着改坏）

| 报告的说法 | 实测（HEAD） | 判定 |
|---|---|---|
| 「底部按钮画了 18px 但只有 2px 可点，落空还会穿透成画布拖动」 | `route_client_RouteJeiRecipes.java:465-469` `button()` 画的是 `g.fill(x,y,x+w,y+18,...)`（填充 `[y,y+18)`），而调用点 `:355-356` 传的是 `y+h-20` → 屏幕上是 `[y+h-20, y+h-2)`；点击判据 `:499` `if(button!=0 \|\| mx<x \|\| mx>=x+w \|\| my<y+h-20 \|\| my>=y+h-2)return false;` 的接受区间**正好也是** `[y+h-20, y+h-2)`，同样是 18px | **误报**。绘制与命中完全吻合，不存在 2px 可点区 |
| 「画布顶部 2px 内容被 scissor 提前裁掉，导致顶部点不到」 | `route_client_RouteScreen.java:1365` `g.enableScissor(canvasLeft, canvasTop + 16, canvasRight, canvasBottom)` 的上边界（+16）比 `:1367` `g.pose().translate(canvasLeft, canvasTop + 18, 0)` 的内容起点（+18）**更靠上**，即裁剪更松，不会裁掉内容。真实问题是方向相反的**常量不一致**：命中门 `:1989`/`:2000` 用 `canvasTop + 16`、`:1269 contentY` 用 `- canvasTop - 18`、`:1974`/`:1022`/`:1267` 用 `+18` → `canvasTop+16..+18` 这 2px 带能通过命中门并映射到略负的 content y | **误报**（结论方向相反）。真实项降级为 P2 常量不一致，见 7.3 |
| 「`RouteScreen.java:924` 的 `status` 可能为 null，`startsWith` 会 NPE」 | `:381` `private String status = "读取当前网络…";`，全文件无 `status = null` 赋值；`:1726-1727` 还额外写了 `status != null &&` 与 `status == null ? "" : status` 两重防御 | **误报**。`status` 恒非 null |
| 「`RouteJeiPlugin.java:110-113` 的 else 分支没判 `currentRuntime` 为 null，是 P0 崩溃」 | 该分支只有 `RouteClient.open(currentRuntime.getRecipesGui()...)`。上游 `route/client/RouteJeiPlugin.java:42` 的 `onRuntimeUnavailable()` 会把 `showJei`/`recipeBrowser`/`currentRuntime` 一起清空，且 `:96` 有 `if(!RouteClient.available()) return false;` 前置；JEI 的布局回调在 runtime 失效窗口内不会带着非 null 的旧 runtime 回调进来。**可达性为零** | **误报（降 P3 防御性）**。真要防守，加一行判空即可，但不是 P0 |
| 「`route/client/RouteRecipeHover` 的 demand 缓存键缺字段，会串用上一个节点的数量」 | `RouteRecipeHover.fallback()` 的 EMC 分支只用 `RouteEmcRules.unit(draft)`，与 demand 无关；`renderEmc` 里的 demand 只喂侧栏 browser，而 `RouteScreen.java:1579`/`:1620` 每次渲染都重置 | **误报**。缓存键确实不含 demand，但不影响任何观测量 |

### 7.3 P2 常量不一致（不是「点不到」，只是 2px 带）

`route_client_RouteScreen.java` 里「画布内容的 y 原点」有 `+16` 和 `+18` 两种写法：

- 渲染裁剪 `:1365` = `canvasTop + 16`；内容平移 `:1367` = `canvasTop + 18`
- 坐标反算 `:1269` `contentY(mouseY) = (mouseY - canvasTop - 18) / zoom - panY`
- 命中门 `:1989`/`:2000`（`materialAt`/`recipeAt`）用 `canvasTop + 16`，高度也算 `canvasBottom - canvasTop - 16`
- 画布平移门 `:1974`、节点屏幕坐标 `:1267`、配方机标 `:1518` 用 `+18`

结果：`canvasTop+16 .. canvasTop+18` 这 2px 带能通过 `materialAt`/`recipeAt` 的命中门，算出的 content y 是负值（约 `-2/zoom`），通常会落空返回 null，所以**不是**「顶部点不到」，只是命中门与内容原点差了 2px。统一成一个常量即可。

### 7.4 关于那份第三方 UI 报告的口径

后台子代理的 UI 层报告自称 44 条（9×P1 + 35×P2），但它声明的行号基线是「`RouteScreen.java` 2373 行」。**2373 行是工作区当前状态（HEAD 2326 行 + 未提交改动），不是 HEAD**，两者差 55+/8−，行号会漂移，所以它的行号一律不可直接引用。

我按内容在上述 HEAD 快照上重新定位，只保留逐行读过源码确认成立的部分（**第二轮之后是 C1–C21**：C1–C7 来自第一轮，C8–C16 来自第二轮，C17–C21 来自第三轮亲核、详表在 §8.1），并把五条明显误判单列在 7.2（底部按钮命中区、顶部 scissor、`status` 可能为 null、`RouteJeiPlugin` 的 P0、`RouteRecipeHover` 的 demand 缓存键）。它的 P1/P2 分级也没有沿用：C3/C6 被我从 P1 降到 P2（实际触发面比报告描述小），C2 保留 P1；它自报的唯一 P0 被证伪降到 P3。

---

## 8. 第三轮：第三方子代理报告的逐条亲核（净增量）

本轮派出的只读审计子代理有 3 份报告落盘（`docs/teamflow-run-notes/sweep/`：`b-route-core.md` 223 行、`d1-client.md` 13.5KB、`c-client-core.md`）。**报告结论一律按第三方数据处理**，逐条回读 HEAD 源码亲核后的结果：**成立 10 条（新增 D42–D46、C17–C21）、降级 2 条、证伪 3 条**。本节只为留下可复核记录，结论已并入 §1.4 的净增量表。

### 8.1 亲核后成立（新增条目，全部含逐字代码）

| 编号 | 优先级 | 位置（HEAD） | 事实（已逐行实读） | 后果 |
|---|---|---|---|---|
| **D42** | P2 | `route/RouteWorkbench.java:68-81`（`storeEncoded`），调用链 `route/RoutePatternEncoder.java:58` ← `route/RouteNetwork.java:64` | `:71 var cache=cache(player); int slot=RouteCacheData.freeSlot(cache);` → `:73-74` 把 token+item 写进**本地副本** → `:76-77` SIMULATE 失败抛「网络内没有可提取的空白样板」 → `:78-79` MODULATE 失败抛「空白样板提取失败」 → `:80 commit(player,cache);` **只在不抛异常时才提交** | 客户端 `RouteScreen.java:76` 的 `armedEncoding` 在发请求前已置空（「确认编码」二段式确认已消费）；MODULATE 失败时服务端 cache 未提交 → 已编码样板**既没进缓存也没还回背包**，且错误文案只说「空白样板提取失败」，玩家无法判断样板去哪了。材料侧守恒（样板先序列化再扣空白样板），所以是**丢样板、不丢材料** |
| **D43** | P2 | `route/RouteWorkbench.java:62-67`（`stack()`）+ `:87`（`takeToBackpack`）+ `:174`（`view()`） | `ItemStack.parse(player.registryAccess(),entry.getCompound("item")).orElseThrow(()->new IllegalArgumentException("缓存样板数据无法读取，已保留原始数据"))`。`:174` 在 `view()` 的 18 槽循环里抛 → `RouteWorkbench.view(...)` 整体抛 → `RouteNetwork.java:74` 的 reply 不执行 → 落到 `:219 catch(Exception failure)` 回「操作失败：缓存样板数据无法读取，已保留原始数据」 | 一个坏槽（物品被模组移除/组件不兼容）让**整个缓存面板永久打不开**：既看不到其它 17 个好槽，也没法用「放入背包」删掉坏槽（`:87` 同一函数在同一循环里抛）。`RouteCacheData.freeSlot` 仍返回首个空槽 → 18 槽写满后彻底锁死。修法：`view()`/`takeToBackpack()` 逐槽 try，坏槽以「无法读取」占位并允许丢弃 |
| **D44** | P2 | `route/RouteNetwork.java:131`，兜底 `:152-153` | `inputs.add(RouteView.Stack.of(possible[0].what(), Math.multiplyExact(possible[0].amount(), in.getMultiplier()), player.registryAccess()));` —— **无 try**；`:152 catch (Exception e)` → `:153 new RouteView.Reply("操作失败：" + e.getMessage(), ...)` | `ArithmeticException` 的 `getMessage()` 为 `null` → 玩家看到的是**「操作失败：null」**。数量级需要 `amount*multiplier` 溢出 long（正常配方不可能），故 P2：不是数值风险，是**错误文案不可诊断** |
| **D45** | P2 | `route/RouteNetwork.java:99-101` | `if ((request.action().equals("lock") \|\| request.action().equals("ban")) && patterns.stream().noneMatch(p -> RouteIdentity.pattern(p, player.registryAccess()).equals(request.argument())))` —— `RouteIdentity.pattern` 内部 `route/RouteIdentity.java:13` 先 `ScaledPatternCompat.validate(p)`，`:17-20` 对 `leaf.getDefinition()==null` 抛 `RoutePlanningException("样板没有稳定标识，暂不支持手动锁定")` | 只要终端里**存在任意一张**没有稳定定义 / Scaled 校验不过的第三方样板，lock/ban **整体失败**（`stream().noneMatch` 短路在抛异常处），归因变成「操作失败：「样板没有稳定标识…」」，而玩家锁的其实是另一张正常样板。修法：逐张 try，无法计算标识的样板跳过而不是炸整体 |
| **D46** | P2 | `route/RouteWorkbench.java:157` | `int slotPages=shown.isEmpty()?0:RouteProviderWindow.of(shown.get(0).getTerminalPatternInventory().size(),request.columns(),request.rows(),requestedSlotPage).pages();` —— 分页数**只按当前页第一个供应器**的槽位数算 | 各供应器槽位数不同（样板供应器/扩展样板供应器）时，槽位翻页数被第一个供应器锁死：后面的供应器槽位多出被截断、或翻到空白页。`:162` 每行的 `window` 又各自独立算，所以「页数」与「页内实际内容」不一致 |
| **C17** | **P1** | `route/client/RouteJeiRecipes.java:490`（`click`），上游 `RouteScreen.java:1898`/`:1932` | `held=displayed.get();stableDraft=RouteJeiPlugin.readDraft(layout,stableDraft,clickedSlot.get());heldDraft=stableDraft;...` —— **无 try**。`readDraft`（`route/client/RouteJeiPlugin.java:121-149`）会对**整张配方所有槽位**调 `RecipeDraftImport.read(slots,decode)`（`route/RecipeDraftImport.java:27 Objects.requireNonNull(decode.apply(ingredient),...)`），而 `decode` 是 `RouteJeiConverters.from(...)`，对未注册转换器的类型直接 `throw new IllegalArgumentException("材料类型未注册 AE2 转换器："+raw.getClass().getSimpleName()+"；未省略该材料")`（`route/RouteJeiConverters.java:24`） | `:489` 的守卫只保证**被按下的那个槽**可转换；同配方里另一个槽（ExtendedAE/自定义类型/未装 ae2jeiintegration 时的特殊 ingredient）抛 IAE → 异常从 `click` 冒到 `RouteScreen.mouseClicked`（`:1898`/`:1932` 均无 try）→ **客户端鼠标事件崩溃**。对照：同文件 `currentDraft():205` 与 `load():301` 都有 try，唯独这条交互路径没有 |
| **C18** | P2 | `route/client/RouteWorkbenchPanel.java:35-44`/`:55`/`:124-126` + `route/RouteLayout.java:34` + `route/client/RouteScreen.java:87` | `layout()` 里 `:41 y=collapseCache && !cacheExpanded?bottom-20:expandedY;`，折叠按钮 `:124-126` 点下去只把 `y` 改成 `expandedY`；但 `top()` 是 `:55 return collapseCache?bottom-20:y;` —— **collapseCache 时恒返回折叠高度**；`RouteLayout.cacheReservedHeight()` 也是 `collapseCache?20:…`（构造期定死的 record 分量） | 窄窗口（`height<300` → `collapseCache=true`）里点「▾ 样板缓存」展开后：缓存格从 `expandedY` 往下画，而检查器的 `inspectorBottom()` 仍 = `workbench.top()-5` = `bottom-25` → **编码按钮（`:1581`/`:1622` 的 `inspectorBottom()-20`）被缓存格盖住**；且 `contains()` 用的是被改过的 `y`，覆盖到该区域 → `RouteScreen.java:1881` 的 `workbench.click` 先于 `:1925` 的 actionHits 返回 true → **编码按钮变成死键**。修法：让 `top()` 在展开时返回 `y`/`expandedY`，并让 `RouteLayout.cacheReservedHeight()` 由面板实际高度回灌 |
| **C19** | P2 | `route/client/RouteScreen.java:149`（`removeBranch`）+ `:1094`（`buildNode`）+ `route/RouteBranchEdits.java:31-33` | `removeBranch` 删节点时除 `hide(path)`/`hide(parent,key)`/`hideChild` 外还调 `:149 branchEdits.hideMaterial(node.material.key);` —— 这是**全局材料屏蔽**（`hiddenMaterials.add(key)`）；`buildNode` `:1094 if (depth > 0 && branchEdits.materialHidden(material.key)) return null;` | 删一个节点会连带让画布上**所有同材料的无关分支及其下级静默消失**，而 `:155` 的状态栏写的是「已移除节点及其下级；原配方完整材料不变，可撤销」。可撤销（`EditState.edits` 是 `RouteBranchEdits.snapshot()` 深拷贝含 hiddenMaterials，`:131` 已 `checkpoint()`），但玩家不知道别的分支为什么没了；`applyCanvas` 组出的路径也少这些分支。恢复入口只有 `:251 showMaterial(ingredientId)`（重选该材料的配方时） |
| **C20** | P2 | `route/client/RouteJeiRecipes.java:322-323`（`render`），配 `:32`/`:33`/`:307-314` | `:322 if(count()>0){` → `:323 String title=emcSelected()?"EMC 转换":recipes.get(index).category().getTitle().getString();`。`count()=recipes.size()+(emc.available()?1:0)`、`emcSelected()=emc.available() && index==recipes.size()`。若 EMC 从「可用」翻成「不可用」而 `index` 停在 `recipes.size()`，则 `count()` 少 1 但 `index` 不夹 → `recipes.get(index)` 越界；`tick():309-311` 的回夹条件写的是 `wasAvailable!=emc.available()`，而 `emc` 已经是 NONE 后每 20 tick 重新 query 结果恒定（`false→false`）→ 不再回夹 | **前提未证明**：`route/client/RouteEmcAccess.java:30-43` 的 `unit` 由 ProjectE 物品 EMC 值决定，运行期恒定；`select(key)` 在 `:273 if(Objects.equals(key,this.key))return;` 早退，换 key 时又会把 `index` 归 0。故正常流程下形成不了该状态，只在 ProjectE 运行期卸载/重载、或数据包改 EMC 值时暴露。**定 P2 防御性**，修法一行：`:322` 前加 `index=Math.min(index,Math.max(0,count()-1));` |
| **C21** | P2 | `route/client/RouteJeiRecipes.java:57`/`:62-68`，对照 `:274`/`:299` | `private final Map<Object,List<ITypedIngredient<?>>> catalystCache=new HashMap<>();`；`rawCatalysts` 用 `catalystCache.computeIfAbsent(type,t->{try{...}catch(RuntimeException ignored){return List.of();}})` —— **失败的空表被永久写入**；`select()`（`:274`）与 `load()`（`:299`）都不清它 | JEI 重载/注册表变化后（`RouteScreen.java:435` 只在打开屏幕时取一次 `RouteClient.recipeBrowser`，同屏内不重建），某配方类别的设备候选**在整个画布会话内永久为空**，且没有重试入口（`error` 都不会置，界面只显示「无机器」）。修法：在 `select()` 里 `catalystCache.clear()`，或缓存「成功」而不缓存「失败」 |

### 8.2 降级 / 并入既有条目

| 报告说法 | 亲核结果 |
|---|---|
| 「`begin` 非幂等，会覆盖 BASELINES，是 P1」 | **降 P3**。`route/RouteNetwork.java:89` + `route/RouteSessions.java:13-15` 确实每次 `begin` 都覆盖 `BASELINES`，但全树只有 `route/client/RouteScreen.java:471 if(!initialized)pressBegin();` 一处调用，且 `init()` 只在屏幕首次打开跑；重试路径 `retryBeginIfDue()`（`:493-504`）在 `:495 if(target!=null){beginRetry.answered();return;}` 之后才重发，此时策略必空、覆盖无害。只剩「菜单 containerId 复用 + 策略非空」的理论窗口 |
| 「`RouteView.decodeKey` 可能返回 null，是 P1」 | **并入 C5**。机制成立（AE2 `AEKey.fromTagGeneric` 的 catch 分支 `return null` 不抛），但 19 处调用点里只有 `route/client/RouteScreen.java:1105` 没有守卫，已在 §7.1 的 C5 记录；`:349` 归 C1/P3；`amounts()` 的另一个调用点 `matchesDraft():2254` 有 try |

### 8.3 证伪（第三轮新增的 3 条，避免照着改坏）

| 报告说法 | 实测（HEAD） | 判定 |
|---|---|---|
| 「`route/RouteNetwork.java:197` 的 `future.whenComplete` 没有 try/catch，异常会被吞」 | `:197 future.whenComplete((plan,error)->{` → `:199 try {` … `:217 } finally {if(PLANNING.current(...))PLANNING.forget(...);}`；外层还有 `:55 try`/`:219 catch(Exception failure)` 兜 `reply()` 内的 `key.toTagGeneric`（`:233`，AE2 用 `getOrThrow()` 会抛） | **误报**。有 try/finally |
| 「`route/client/RouteWorkbenchPanel.java:104-110 item()` 未判 `Minecraft.getInstance().level` 为 null 会 NPE」 | `item()` 只在 `render()` 里调，而 `render()` 由 `RouteScreen.render` 调；屏幕渲染时世界必已加载（`RouteClient.open` 只从容器界面进入） | **不可达**（P3 防御性） |
| 「`route/client/RouteWorkbenchPanel.java:177` 在 `state==null` 时 NPE」 | `:177` 的 `state.providerPage()` 在右侧三元里，但整个表达式以 `!current?` 开头，而 `currentFilterShown()`（`:59-61`）本身就要求 `state!=null` → `state==null` 时 `current==false`，**短路求值不会触及右侧**；`:162` 同构 | **误报**（与 §7.2 原记录一起：我先前自报的这条也一并否掉） |

> 口径说明：§7.2 的 5 条 + 本节 3 条 = **8 条已否掉的误报**。三份子代理报告共自报 P0×1、P1×9、P2×40+，亲核后**没有一条 P0 成立**，最高只有 C17 这一条 P1。

---

## 附录 A：方法

- 用 `git archive HEAD src/main/java src/test/java` 解出纯净快照。**第一轮**：`docs/teamflow-run-notes/head-1c7ee64/`（从 212 个 `.java` 中抽出 33 个关键文件：23 个服务端/规划/执行层 + 10 个 client 层），§1–§6 与 §7.1 的 C1–C7 以此为准。**第二轮（覆盖补全）**：`docs/teamflow-run-notes/head-sweep/`，`git archive HEAD --format=tar -o %TEMP%/head-audit.tar src/main/java src/test/java` 后解包，得到**完整**的 129 个 `.java`（`src/main/java`，12165 行）+ 83 个（`src/test/java`，6084 行）。§3.6 的 D37–D41、§7.1 的 C8–C16、§8.1 的 D42–D46 与 C17–C21 都用 `head-sweep/` 的行号（文件名是真实包路径，如 `route/client/RouteScreen.java`）。两份快照都是同一提交 `1c7ee6471ad0281e041ca82f5b81bc49c340e398`，**所有行号以此为准**，避免读到并发团队的脏文件。
- AE2 行为用 `javap -p -c` 直接读字节码，不靠记忆：
  - `appeng.crafting.CraftingLink.insert(AEKey,long,Actionable)` —— tie/request/requester 任一为 null → `lconst_0;lreturn`；cancelled → 0；否则委派 `getRequester().insertCraftedItems(...)`。
  - `appeng.crafting.execution.CraftingCpuLogic.storeItems()` —— 逐 key `MEStorage.insert(key,amount,MODULATE,cluster.getSrc())`，按返回值递减。
  - jar：`C:\Users\xun\.gradle\caches\modules-2\files-2.1\org.appliedenergistics\appliedenergistics2\19.2.18\...\appliedenergistics2-19.2.18.jar`
- 并发改写用「两次全树 SHA256 指纹比对」实证，不靠 `git status` 猜。

**踩坑记录**：`javap` 的类名必须是真实路径（`appeng.crafting.CraftingLink`），写成 `appeng.crafting.execution.CraftingLink` 只会打印「找不到类」，看起来像命令没有输出，很费时间。

## 附录 B：本轮未做的事

- 没有修改模组任何源码（工作区里 6 个未提交文件是更早一轮流水线的产物，不是本轮审计的改动）。
- 没有跑构建/测试/真机（本轮是只读静态审计 + 字节码核对）。
- 没有做像素级人工验收（没有启动客户端截图对比）。§7 的 C1–C21 与 §8 的 D42–D46 是**静态实读**结论，每条都给出 HEAD 行号与触发条件，但「掉帧到什么程度」「Esc 误关的体感」「子树拖不动的确认」仍需真机才能定量。
- **覆盖范围（已补全）**：第一轮只精读了 `route/client/` 的 10 个文件；第二轮已把 `src/main/java` 下 **129 个 `.java` / 12165 行全部逐行读完**，（第三轮又用三份第三方子代理报告做交叉验证：报告自报 P0×1、P1×9、P2×40+，逐条亲核后**没有一条 P0 成立**，成立的新增条目 10 条并入 §8.1，另有 3 条证伪、2 条降级并入，全部否掉的误报共 8 条见 §7.2 与 §8.3。）含 `route/client/` 全部 **33** 个（`RouteScreen` 2326 / `RouteJeiRecipes` 533 / `RouteWorkbenchPanel` 227 / `RouteClient` 133 / `RouteRecipeHover` 85 / `RouteMachineTray` 60 / `RouteRecipeBrowser` 44 / `RouteEmcAccess` 44 / `RoutePatternPresentation` / `RouteProviderPresentation` / `RouteTreeGeometry` 40 / `RouteTreeSnapshot` 39 / `RouteNativePatternIcon` 37 / `RouteNodeMenu` 34 / `RouteProviderWindow` 16 / `RoutePopupPage` / `RouteRecipeGroups` / `RouteRecipePreviewBox` / `RouteRecipePreviewMatch` 25 / `RouteNodeInteraction` / `RouteNodeStatus` / `RouteNodeVisual` 11 / `RouteMachineCycle` / `RouteMachineGrid` 24 / `RouteStartupIndex` / `RouteToolbar` / `RouteWheelIntent` / `RouteQuantityField` / `RouteMaterialPort` / `RouteMachineTray` / `RouteAnchor` / `RouteJeiConverters` 34 / `RouteDrafts` 32）、`mixin/` 17、`compat/` 8、`api/` 3、`telemetry/` 4、`vm/` 6、`selftest/` 2（含 `SelftestHarness` 1369 行的关键段落 + F1–F14 逐条核实）、`config/` 1、`compiler/` 1、根 2。
- **`src/test/java`（83 个文件 / 6084 行）只做了针对性检索**（找「本可以抓住 C8/C11/C14 类缺陷的既有测试」），没有逐行审——测试代码的缺陷不会直接伤玩家，优先级低于主代码。已确认两处测试盲区：`src/test/java/com/aecraftroute/addon/route/client/RouteTreeGeometryTest.java:28-31` 只断言约束满足、不断言合法位移被保留（→ C11 测不出来）；`planner/ProductionCursor` 等 planner 单测覆盖了边界校验但没有覆盖 `CertifiedCraftingPlan.emittedItems()` 恒空（→ 自测脚本 F1 的死断言没人抓）。
