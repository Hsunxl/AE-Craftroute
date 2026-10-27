# 画布—规划—执行审计推进记录

基线：`1c7ee64` / local.59。依据：`canvas-planner-executor-audit-local59.md`。该审计文档是历史快照；实施时均按当前源码重新核对。

## 当前工作区阶段（未提交、未安装）

- 第一批 D1：会话判定改用 `RoutePolicy.empty()`，canvas-only 约束不再漏过关闭开关与失败隔离。
- 第一批 D4/D5：按用户最新要求，显式选择了不兼容 CPU 时允许自动改投可执行的 CPU，以保证合成继续；AdvancedAE 子类沿父类找到桥接字段。未注册第三方 requester 仍不进入本模组规划。
- 第一批 D7：画布材料解析失败保留根因类型与消息。
- 第一批 D16：无 player 的非空策略使用服务端 registry access，不再无消息地 `orElseThrow()`。
- 客户端 C9：嵌套画布关闭时恢复前一个网络回包 handler。
- 客户端 C17：JEI 整张配方解码失败时留在界面展示错误，不把异常抛入鼠标事件。
- 第三方执行边界：未注册的第三方 requester 在任何手动画布策略判断之前直接放行，继续使用其自有规划/执行；只有本模组的 `CertifiedCraftingPlan` 才进入 certified CPU dispatcher。
- 第二批 D8：仅 AE2 `CraftingLink.isStandalone()`（提交时无 requester）的产物可退回 ME 网络；有 requester 时拒收 0 个则留在 CPU 下 tick 重试。原生 AE2 与 AdvancedAE 两条 certified 执行桥同步修改。
- 第二批 D9/D11：恢复的 certified cursor 与原生 tasks 不一致即取消任务，不再永久卡死；稳态宏 word 的读写上限统一为 8192，新增 17 步 word 的二进制 NBT 往返测试。
- 第二批 D10：保存 certified 程序时捕获编码异常，不中断区块保存；写入失败标记，重载时取消该任务，而不是让缺失程序的原生 job 不受约束地继续。
- 第二批 D2：`offer` 的支配扫描与每 32 个 key 都检查截止时间/中断，超时返回 LIMIT。
- D3 复核：数值编译图只存请求局部下标，不存材料身份；相同数值结构跨材料复用后由新请求重新绑定。新增异材料同构测试验证第二次仍指向自己的样板与材料，暂不按审计建议禁用缓存。
- 最终完整离线 `test routeFunctionalTest build -x productionBenchmark` 通过；两个测试任务各 514 项，失败 0、错误 0。
- 独立服务端自检 `paths`、`growth`、`cancel`、`reload`、新增 `rejectonce`、`advanced` 均 PASS。`rejectonce` 验证请求者第一次拒收 0 后最终仍收到全部产物；`advanced` 验证 AdvancedAE CPU 执行桥。
- `restart1` → **真正退出服务端进程** → `restart2` 的独立存档往返均 PASS；重启后的真实 ME 接口收到钻石 3 个，网络内 0 个。
- D12/D13 复核：D12 的 DAG 候选还要经过独立 `certify` 证明，当前 `stock>0`/`reserve=max` 可能损失快路命中率但不能单独造成错误提交；D13 的 `PatternVariants.enumerate(p)` 保留同一个 `p` 引用，而画布协议未携带子路径数量，数量在服务端从父样板计算。暂不照审计文本直接改动这两处，以免破坏现有端到端链路。
- 客户端 C19：删除节点只隐藏该路径，不再连带隐藏其它位置的同种材料分支；新增同材料不同路径的回归测试。
- 客户端 C12/C14/C20/C21：JEI 布局失败每 100 tick 可重试且“重载映射”会强制刷新；催化设备查询失败不再永久缓存空结果；失效图标解码为 null 时回退样板图标；EMC 选项消失后夹紧索引并重建布局。
- 这些客户端调整后的完整离线 `test routeFunctionalTest build -x productionBenchmark` 再次通过；两个测试任务各 515 项，失败 0、错误 0。
- D42 复核：`encoded` 是服务端内存中新构造的物品，`storeEncoded` 先序列化、后扣空白样板、最后提交缓存。提取失败时尚未扣除空白样板，也没有玩家持有的实体样板被删除；审计称其为“丢样板”不准确。暂不把缓存提交提前到扣样板之前，以免引入复制漏洞。
- 服务端 D45/D46：锁定/禁用样板前只接受能生成稳定身份的有效样板，坏样板不会让别的样板操作整体失败；供应器槽位分页数取当前页所有供应器的最大值，不再只取第一台。完整离线门禁复测通过（515/515）。
- 服务端 D43：缓存视图逐槽隔离无法读取的样板，其它槽仍可查看与移入背包；损坏槽保留原始数据。客户端在损坏槽两次右键（5 秒内）后才发清理请求，服务端同时校验槽位、缓存修订号和仍然无法读取，健康样板不可通过该请求删除。新增纯 NBT 边界测试。
- 客户端 C18：折叠缓存展开后，检查器底边与 JEI 绘制高度改取面板实际顶部，避免编码按钮被缓存格盖住。该轮完整离线门禁通过，两组各 516 项、失败 0；尚未做像素级客户端验收。
- 客户端 C11：画布几何约束改按实际二维盒碰撞处理，合法的向左/向上子树位移不会再被旧的深度带钳回；新增紧凑默认布局的拖动回归测试。完整离线门禁再次通过。
- 客户端 C10/C1：确认页同步计划无法提取终产物时仍回退确认页目标数量；搜索框聚焦时 Esc 先退出搜索、不关闭整个画布。C8 经 `local47-canvas-p0-verification.md` 的既有语义复核后撤回逐节点改色：任何未应用本地分支都让全树保持保守的未证明外观，和 tooltip 一致。
- 服务端 D36/D25：画布样板展示逐张读取并隔离异常第三方样板（包括 null 候选/输出），不让单张坏样板阻断其它正常配方；权威计划步骤名称在输出为空时回退为「样板」。快照遍历对第三方空输入/输出给出受控 UNSUPPORTED，不再裸抛 NPE。新增坏样板后正常样板仍可读取的测试。
- 客户端 C5：原计划与网络候选的材料量匹配遇到跨版本坏 key 时跳过候选，不再让异常穿出回包/渲染；decode 返回 null 也视为无效。该轮完整离线门禁通过，两组各 518 项、失败 0、错误 0。
- 执行 D19：样板输入槽数量或输入/输出结构在 dispatch 前变更时，certified CPU 不先扣材料也不越界，而是标记程序失效，并在 AE2/AdvancedAE CPU tick 尾取消任务；新增「槽位不一致时材料仍在 CPU」测试。
- 兼容 D23：scaled 样板的倍率 getter 接受可精确转成正整数 long 的 `Number`（例如第三方返回 `int`），仍拒绝非整数、非正数或溢出；保留外层样板执行身份，不改道至本模组执行层。新增整数 getter 编解码测试。
- 服务端 D21：只读 `query`、`begin` 与 `replan` 不再无条件写入玩家路线会话；只有真正改动策略时才 `RouteSessions.put`。完整离线门禁通过，两组各 520 项、失败 0、错误 0；最新独立服务端 `paths` 与 `advanced` 自检均 PASS。
- 服务端/客户端 D22：新增 `clearcanvas` 动作及画布工具栏「撤画」，仅撤销已应用的强制画布、保留逐材料锁定/禁用；有变更时立即重新规划。客户端清掉旧证明并重新询问，纯策略测试验证规则保持且窄窗口 10 个工具按钮仍能容纳。
- 客户端 C2：目标搜索把物品名称与 ID 的小写索引按界面/语言/注册表大小缓存，输入每个字符不再重新构造全注册表的 `ItemStack` 和小写字符串；语言资源实例改变时重建索引。完整离线门禁通过，两组各 521 项、失败 0、错误 0；尚无真实客户端帧时延测量。
- 客户端 C13/C15/C16：草稿保存用 JVM 同步、跨进程文件锁和独立临时文件覆盖整个读改写事务，并发 16 次保存后所有草稿可读；WCWT 深色按钮重复 init 不再累计上漂，重新定位后可再次执行一次正确偏移；确认页计划未就绪时直接打开画布并用既有 `begin` 重试读取服务端目标，不再要求玩家按 F8 两次。
- VM D17/D18：数值程序的 0 输出增量跳过除法，负增量按损坏程序处理；缺少 HALT 时给出明确程序计数器越界错误而非裸数组异常，新增定向测试。
- 复核新出现的 local.60 只读审计：确认 `IdentityHashMap.containsValue` 按引用比较，导致锁定/禁用全失败；已改成内容相等并用不同 String 实例回归测试。样板身份提取失败也计入「异常样板未显示」。几何约束先按深度处理，避免乱序输入使已放置子节点被后来的祖先移动；新增乱序测试。该并行审计文档、版本号及 CHANGELOG 并非本任务产物，依用户要求未保留。
- 最新离线 `test routeFunctionalTest build -x productionBenchmark` 成功；Gradle XML 两组各 525 项、失败 0、错误 0、skipped 0。`git diff --check` 无空白错误。此门禁不替代玩家客户端操作验收。
- 执行 D29：certified 程序存档读写新增总计 120,000 步的预检上限，避免多块各自合规却在主线程解码近十亿步；样板输出与流量指纹按材料+数量多重集合比较，顺序变化不再让同一配方的进行中任务作废。新增超总量和输出顺序回归测试。
- 该轮完整离线门禁两组各 527 项、失败 0、错误 0、skipped 0。`restart1` 真正结束服务器进程后，`restart2` 用同一存档在新进程继续任务并 PASS；真实 ME 接口 requester 收到钻石 3 个，ME 网络内 0 个。
- 执行 D28：对本地 AE2 19.2.18 的 `CraftingTreeProcess` 字节码核对，原生模拟每执行一次样板只调用 `addBytes(count)`，不按输入槽或计划元数据计费。本模组原先的 `8×结构项+输入槽×次数` 会拒绝原本够用的 CPU；现改为各样板执行次数之和，仍保留输出数量乘法溢出检查。新增两输入槽执行五次时 `bytes=5` 的回归测试。完整离线门禁两组各 528 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。
- D28 后真实服务端自检 `paths`（原生 AE2 CPU、交付 1）与 `advanced`（AdvancedAE CPU、交付 3）再次 PASS；均在独立 `run-selftest` 游戏目录运行，未安装进玩家整合包。
- 执行 D27/D31/D32：删除非 certified 重试循环中永远不会命中的 certified CPU 筛选，避免误读为会替第三方计划换执行层；AdvancedAE 的 `CPU_OFFLINE` 诊断改为每 30 秒最多一次且不再声明可取消，保留原返回值；兼容提示只在 debug 已开启时才标记已打印，并明确未注册第三方 requester 保持自己的规划/执行层。新增节流窗口测试。完整离线门禁两组各 530 项、失败 0、错误 0、skipped 0；`advanced` 独立服务端自检交付 3 且 PASS；`git diff --check` 无空白错误。并行新增的 local60、teamflow、版本/CHANGELOG 改动仍未保留。
- 生命周期 D20：服务端停止时先取消规划任务，再 `shutdownNow()` 当前规划线程池并清空引用；下次进世界按需创建新线程池，避免静态 daemon 池与队列跨世界保留。新增「旧池关闭、新池不同实例」回归测试，现有取消/验证边界仍通过。
- 路径协议 D37：提取共享 `MAX_PATH_DEPTH=24` 与 `validPath`；客户端最后可生成的子路径恰好是服务端最后可接受的路径，下一层双方均拒绝。完整离线门禁两组各 532 项、失败 0、错误 0、skipped 0；真实 `paths` 服务端自检交付 1 且 PASS；`git diff --check` 无空白错误。
- 存档恢复 D24（部分）：scaled 样板若恢复时没有真实 `Level`，不再把 null 传进三参构造器，而是选择二参构造器或受控失败；可选附属模组依赖移除引起的 `LinkageError` 变成该任务的无效程序，避免异常穿透服务器载入。新增直接解码和 `ProductionJob.load` 无效化回归测试。仍保留运行时按存档类名恢复兼容包装器，未用封闭类名白名单砍掉其它模组的可执行路线。完整离线门禁两组各 533 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。
- CPU 选择 D6：自动改投 AdvancedAE CPU 时，反射读取 `canBeAutoSelectedFor` 失败不再默认许可，以免选到玩家/机器排除的 CPU；正常返回 true 的 CPU 仍可执行，false 的 CPU 仍跳过。新增四种选择器状态回归测试。完整离线门禁两组各 534 项、失败 0、错误 0、skipped 0；真实 AdvancedAE CPU 独立服务端交付 3 且 PASS；`git diff --check` 无空白错误。静默改投仍允许，但仅对确认可自动选择的兼容执行器。
- 强制画布 D28 补全：发现 `RouteCanvasPlanner` 仍沿用旧的 `8×结构项+输入槽×次数` CPU bytes 估算，虽然网络自动规划已修正。现两条规划路径统一按物理样板实际执行次数计费，虚拟画布标记和压缩块不计入 CPU 容量；保留输出追踪乘法溢出检查，并把此阶段的算术溢出明确报为 LIMIT。既有双路径画布测试新增 `bytes==patternTimes 总和` 断言；完整离线门禁两组各 534 项、失败 0、错误 0、skipped 0，真实 `paths` 服务端自检交付 1 且 PASS。
- D14：强制画布独立物理回放若发现分支未完成、起手库存被重复使用或目标净增量不足，改报有类型的 UNSUPPORTED「内部证明不一致」，不再裸抛 `IllegalStateException` 退化为普通规划错误；这些检查仅在求解器自身证明矛盾时触发，正常路径由上述完整门禁与真实服务器自检覆盖。
- 客户端 C4/C7：保存画布位移时统一用可容忍缺少 `/` 的父路径解析，恢复/外来节点不再触发负下标截取；旧版或跨版本回包的配方若省略输入/输出列表，`Choice` 的访问器仍返回空列表，画布预览不因 `outputAmount` 空列表 NPE。新增父路径及 Gson 旧回包测试。
- D40：删除主模组类里关于已经移除的 Thunderbolt 规划引擎注册的错误说明；明确实际只注册 batch 资格兼容规则，未注册第三方 requester 仍保留其自己的规划与执行链。完整离线门禁两组各 536 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误，并行新增的 local60/teamflow/版本与 CHANGELOG 仍未保留。
- 第三方执行边界复核：未注册附属模组 requester 已绕开本模组规划；现 AE2/AdvancedAE 两条传统增长反馈执行桥也在识别环前检查 requester，未注册第三方自有执行任务不被我们重排或截留产物。本模组自身 requester 明确从「第三方」分类排除，防止自检/内置机器误伤。**仍保留非 certified `CPU_OFFLINE` 的原计划静默 CPU 重试**，不把其它模组的 plan 改成我们的 planner，符合“优先合成串通”。新增字节码边界回归测试；完整离线门禁两组各 537 项、失败 0、错误 0、skipped 0；真实 `growth`、`advanced` 服务端自检均交付 3 且 PASS；`git diff --check` 无空白错误。旧存档已有反馈标记的任务沿旧标记恢复，避免中途切换协议。
- D15 核对：本地 AE2 19.2.18 `CraftingCpuHelper.getValidItemTemplates` 字节码会遍历**全部** `getPossibleInputs()` 并按 CPU 当前库存生成可用模板，故旧反馈桥只看 `[0]` 确会遗漏其它候选；但无条件按所有候选的最大需求截留会把最终产物留在 CPU、造成 requester 永久欠货。暂不做这种会破坏交付的盲修，后续需按真实选中模板/剩余任务设计可证明的保留量。
- 服务端 D44：操作与异步规划错误统一提取最深层可读原因；无消息的 `ArithmeticException` 明确显示「数量超出整数范围」，不再回包「操作失败：null」。新增裸溢出、包装溢出及空消息回归测试。
- 配置 D41：旧滚轮模式补中文说明；配置值暂不可用或为空时安全回退 false，不让渲染/滚轮路径抛异常。完整离线门禁两组各 539 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。
- 交付 D34：删除没有任何生产调用点、却暗示要等待估算回包的旧 `readyToDeliver` 闸门；实际交付仍只根据 certified 程序是否完成和 CPU 中的真实终端结余计算，避免后续误把预估 `waitingFor` 当交付条件。完整离线门禁再次两组各 539 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。
- 隔离客户端实测：在 `run-qa` 世界中用 F8 打开真实确认页画布，铁块根节点与铁锭子节点可见；点击「应用」收到服务端 `verdict=feasible` 与「已应用强制画布程序」，再点击「撤画」收到服务端「已撤销强制画布，保留逐材料路线设置」，证明 UI→请求→规划→策略回包链通。此前 QA fixture 给分子装配室装的是处理样板，无法真实产出，现改为原版铁块合成配方的可执行 crafting 样板；离线门禁通过。尚未用新 fixture 验证 CPU 实际执行与交付。第二次隔离客户端已经启动，但电脑操作被用户按 Esc 停止；随后通过已知 Gradle 会话发送 Ctrl-C 结束该客户端，不继续 UI 操作。
- 客户端 C6：未知配方 ID 的悬停预览不再同一帧创建最多 64 个 JEI 布局，而是每个悬停 tick 最多核对 4 个候选；搜索未完时保留只读预览对象与样板输入/输出兜底，搜完才展示唯一等价配方或停用原生预览，避免错误选中第一个同产物配方。完整离线门禁 `test routeFunctionalTest build -x productionBenchmark` 通过，两组各 539 项、失败 0、错误 0、skipped 0；仍需真实客户端帧时延验收。
- C6 补强：将分帧扫描抽成可测试状态机，覆盖「每次最多 4 次布局构建」「唯一结果在扫完前不提前公布」「发现第二个匹配立即判歧义」「目标改变/清空后重扫」；完整离线门禁两组各 542 项、失败 0、错误 0、skipped 0。D26 对只用于防溢出的物理输出量校验补充原因说明，D39 明示 `LogThrottle` 仅供拥有它的服务端 CPU tick 线程使用，不误称现状是并发 bug。
- 隔离服务端端到端 QA：新增 `vanilla` 场景，复用客户端 QA 命令**同一个**原版铁块样板编码函数。独立 `run-selftest` 世界放置真实 ME 网络、样板供应器、分子装配室和 AE2 CPU，输入铁锭 9 个；实际预览 `feasible`、计划 `bytes=1/patterns=1`，真实 certified 程序附着 AE2 CPU，requester 收到 `minecraft:iron_block x1` 后 CPU 释放，`RESULT=PASS task=vanilla`。客户端 QA 的填充命令显式刷新供应器样板映射。最终完整离线门禁两组各 542 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。此证据证明样板与执行链可用，但不能替代客户端按钮点选后真实 CPU 交付的 UI 验收。
- 第三方执行边界实机自检：独立服务端 `foreign` 与 `foreign-advanced` 场景用未注册的动态代理 requester 发起相同原版铁块请求，并先断言其分类确为未注册第三方；两次均得到 AE2 原生 `appeng.crafting.CraftingPlan`，没有 certified 程序附着，分别交由原生 AE2 CPU 和 **AdvancedAE 自有 `AdvCraftingCPU`** 执行，requester 均收到铁块 1 个、CPU 释放。`foreign-advanced` 在最终代码上再次 `RESULT=PASS`；证明本模组没有强行把附属 requester 的请求送入自己的规划/执行层，而原生计划仍可由附属 CPU 完成。离线门禁两组各 542 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。
- 客户端 C3：机器托盘仅真实机器图标命中时消费点击；标题行、空白槽、边距不再吞掉点击但不执行动作。新增托盘点选回归，保留非左键命中机器时的事件消费，完整离线门禁两组各 543 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。此交互仍待真实客户端像素级验收。
- CPU 静默改投补强：已选 certified CPU 若离线、忙碌、容量不足或执行协议不兼容，尝试其它**兼容且允许自动选择**的 CPU；材料不足、计划不完整不改投，失败时保留原始明确错误。自动候选走直接提交，避免候选失败后递归重新选择；非 certified 的第三方原生计划完全不走此 dispatcher。新增错误类别测试；完整离线门禁两组各 544 项、失败 0、错误 0、skipped 0，隔离无头服务端 `paths`、`advanced`、`foreign-advanced` 三场景均 `RESULT=PASS`，`git diff --check` 无空白错误。用户明确选择暂不操作客户端，因此继续无头逻辑/交付验证；按钮视觉与像素交互暂不声称通过。
- 静默改投真实场景：独立无头服务端新增 `fallback`，先生成 certified 铁块计划，再显式选择一个未接入网络的离线 AE2 CPU；日志确认 `CPU_OFFLINE` 后自动选择真实可用 AE2 CPU，交付铁块 1 个并释放任务，`RESULT=PASS task=fallback`。它与已通过的 `foreign-advanced` 形成边界对照：仅本模组 certified 计划进入该改投逻辑，未注册第三方仍保持原生计划+附属 CPU。最终完整离线门禁两组各 544 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。
- 容量改投无头实测：新增 `fallback-small`，在真实 AE2 1k CPU 与 AdvancedAE 量子 CPU 同网时，将 certified 铁块计划的 CPU 需求设为 1025 bytes，显式选中在线但容量仅 1024 的 AE2 CPU；日志先确认 `CPU_TOO_SMALL`，再验证 certified 程序**只附着 AdvancedAE CPU**，真实铁块交付 requester，`RESULT=PASS task=fallback-small cpu=advanced_ae delivered=1`。这覆盖自动候选的 AdvancedAE `canBeAutoSelectedFor` 正向路径，不改变第三方原生计划。最终完整离线门禁两组各 544 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。用户选择继续无头验证，客户端 UI 仍未复测。
- 忙碌改投无头实测：新增 `fallback-busy`，同网备有 AE2 与 AdvancedAE CPU，先用独立 requester 将选定 AE2 CPU 占用，再对第二个 certified 铁块请求显式选择该 CPU。日志确认 `CPU_BUSY`，第二单自动进入 AdvancedAE CPU、其 requester 收到铁块 1 个并释放任务，`RESULT=PASS task=fallback-busy cpu=advanced_ae delivered=1`；先前占用 CPU 的第一单也各自交付，没有借用第三方执行层。完整离线门禁两组各 544 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。
- 当前代码的真进程重启门禁复测：无头 `restart1` 在 AE2 CPU `busy=true`、原生 job 与 certified 程序 NBT 均存在、ME 接口保存 1 个请求时落盘并结束服务端进程；随后**另一个进程**运行 `restart2` 加载同一世界，恢复后的任务仍 busy、`cpuLink=true`，最终同一 ME 接口持有钻石 3 个而网络内 0 个，两个阶段均 `RESULT=PASS`。此项覆盖 D9/D10/D11 的存档往返与交付，不依赖客户端 UI。
- 回包兼容补强：`RouteView.Reply` 的 `choices` / `banned` 在新构造与 Gson 读取省略字段的旧回包两条路径上都安全回退为空列表；避免画布节点绘制/选项列表遇到缺字段时裸 NPE。新增旧 JSON 与显式 null 构造回归测试；正确路径的完整离线门禁两组各 545 项、失败 0、错误 0、skipped 0，`git diff --check` 无空白错误。首次调用门禁的 Gradle 路径误拼未执行，已用正确路径重跑并确认通过。
- 余项复核：D30 的 `Reply` 目前所有 `with` 方法均复制现有全部字段，原有线协议测试验证 canvas 与 requestTarget 共存；它主要是未来加字段的维护风险，而非当前丢字段。D35 是 D2 截止时间检查位置的同一根因，D2 已在 `offer` 支配扫描中补检查。D38 的反射失败降级仅供诊断且明确不改变执行行为。D33 每帧 `getMousePoint()` 分配仍存在；本地 AE2CT 1.1.1 字节码表明坐标还依赖滚轮缩放、GUI 偏移及节点格位，简单按鼠标坐标缓存会错选节点，暂不做无像素验收的侵入式 Mixin 重写。D15 多候选反馈保留量也仍待可证明的方案，不盲目按最大候选截留最终产物。
- 当前代码再次完成文档标准无头门禁：`growth`（请求者收钻石 3）、`cancel`（请求者 0、CPU idle）、`reload`（同进程 CPU NBT 重载后交付钻石 4）、`rejectonce`（请求者首次拒收 0 后最终收钻石 3）均 `RESULT=PASS`。结合上一轮独立进程 `restart1`→`restart2` 和 `paths`、`advanced`，覆盖画布应用、反馈循环、取消、存档恢复、拒收重试与第三方执行边界；不声称客户端像素验收。
- AdvancedAE 反射提交边界：若 `submitJob` 方法查找/访问在调用**之前**失败，返回 `NO_SUITABLE_CPU_FOUND` 供其它兼容自动 CPU 接手；若反射已进入方法后抛错，则直接保留失败，不重试到另一 CPU，以免重复提交。新增缺方法与已调用抛错的定向测试。离线完整门禁两组各 546 项、失败 0、错误 0、skipped 0；真实无头 `advanced` 与 `fallback-small` 仍 `RESULT=PASS`，`git diff --check` 无空白错误。
- 无头运行确认：继续只用独立服务端与离线测试，不启动或操作客户端。AdvancedAE 反射方法已调用却返回 null 时改报明确的提交契约错误，不把可能已经启动的任务静默改投到另一 CPU；加入 null 回值测试。完整离线门禁两组各 546 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。客户端像素与按钮实操仍不属于无头证据。
- 本轮针对当前代码重跑独立服务端 `paths` 与 `foreign-advanced`，分别 `RESULT=PASS`：前者走真实画布路径应用、异步 certified 规划、CPU 执行与铁块交付；后者保持未注册附属 requester 的原生计划及 AdvancedAE 自有执行。没有启动客户端，也没有把第三方自有执行层强制改道。
- 新增独立无头 `vanilla-canvas` 场景：复用隔离客户端 QA 命令的同一原版铁块样板，在真实网格对 `root` 节点执行 `RouteApply.apply`（`locked=[Block of Iron @ root]`、`verdict=feasible`），再把所得 canvas policy 交异步规划及真实 AE2 CPU 执行；requester 收到铁块 1 个，`RESULT=PASS task=vanilla-canvas`。这将先前分离的「UI 应用回包」和「同样板 CPU 交付」通过无头端到端路径连起来，但仍不冒充按钮像素级验收。完整离线门禁两组各 546 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。
- 同一画布路径再新增 `vanilla-canvas-advanced`：在真实 AdvancedAE 量子 CPU 网络对同一个铁块 `root` 节点应用画布并异步规划，certified 程序附着 AdvancedAE、交付铁块 1 个，`RESULT=PASS task=vanilla-canvas-advanced`。与 `foreign-advanced` 的原生计划/自有执行对照，验证两类请求不混淆。完整离线门禁两组各 546 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。
- 当前代码再跑独立无头 `stock-race` 与 `advanced-stock-race`：服务端捕获库存后、工作线程验证前抽走实际中间物，验证旧快照被拒并在原期限内用实时库存重算；AE2 与 AdvancedAE 两执行器最终均 `RESULT=PASS`（分别 122、124 ticks）。这是库存竞争下的真实网格证据，不是仅靠纯单元测试。
- 交付边界无头复测：`partial`（requester 每次只接受 1 个）、`overestimate` 与 `advanced-overestimate`（预估产物与实际净产出分离）分别 `RESULT=PASS`，覆盖 AE2 与 AdvancedAE 的部分接收/最终交付。继续核查 D15 时反编译本地 AE2 19.2.18 `CraftingCpuLogic.insert`：原生路径在 `waitingFor` 扣除后，终端产物会直接减 `remainingAmount`，降到 0 即 `finishJob`；因此把多候选 pending 无条件取最大值会有把最后一份终端产物永久截在 CPU、requester 欠货的现实风险。D15 仍未据此盲改，须补真实多候选执行夹具及释放证明。
- D15 补上安装版 AE2 执行器的定向回归：`CraftingCpuHelper.getValidItemTemplates` 在候选 `[其它材料, 最终反馈物品]` 时，若只有反馈物品在 CPU 库存，会实际选择**第二候选**；若只有其它材料在库存，则任务可不消耗终端物品。两例都调用真实 AE2 helper 而非模拟首项规则，证实「只看 `[0]`」漏算与「永远保留第二候选」欠货风险同时存在。完整离线门禁两组各 548 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。此测试不等于已经修复 D15 的运行时保留/释放协议。
- D15 进一步从「候选列表」推进到真正的输入扣取：用安装版 AE2 `CraftingCpuHelper.extractPatternInputs` 执行双候选样板，两种材料都在 CPU 时先消耗首候选、反馈终端物品仍在；仅有反馈物品时消耗第二候选。这证明保留量不能只根据样板声明，也不能永远把第二候选视为必需，必须随真实 CPU 库存与剩余任务动态结算并处理多保留物的释放。完整离线门禁两组各 549 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。
- 跑通文档列出的非门禁 `productionBenchmark`（JDK 21、预热 10、样本 30），当前结果保存于 `build/reports/productionBenchmark/results.csv`。典型 `chain-256` 纯规划未命中缓存 p50=13.7088 ms、p95=16.4795 ms，命中缓存 p50=0.4360 ms、p95=0.6991 ms；`ring-2-1000000000` 未命中 p50=0.3096 ms、命中 p50=0.0122 ms。基准不采真实网格快照、CPU tick 或 UI 帧时间，因此只证明当前纯规划样本，不以此代替 A1/A5 的整包负载验收。
- A2 发现并修复实际兼容缺口：对已安装 `extendedae_plus-1.6.3.jar` 做 `javap`，`ScaledMolecularAssemblerPattern` 暴露 `getMultiplier()`，`ScaledProcessingPattern` / `ScaledProcessingPatternAdv` 只有可继承的 `multiplier` 字段，三者都**没有**原先唯一查找的 `getOperationsPerPush()`；旧 `ScaledPatternCompat.encode` 因此会直接拒绝这些真实样板。现按公共 `getOperationsPerPush`、公共 `getMultiplier`、沿父类链的 `multiplier` 字段读取正整数，保留乘法溢出与构造器校验；新增两种契约的夹具测试，并把**已安装的真实** `ScaledProcessingPattern` 加入测试运行时，完成编码→解码往返。`ScaledProcessingPatternAdv` 构造器要求真实 `AdvProcessingPattern`，普通测试夹具不能冒充，暂不声称其实例化往返已验证；分子装配 wrapper 也未做真实实例往返。完整离线门禁两组各 551 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。
- A2 再补真实 `ScaledMolecularAssemblerPattern` 回归：已安装 jar 的 wrapper 用 `IMolecularAssemblerSupportedPattern` 接口代理作内层（只检验 wrapper 的 multiplier、原件与构造器契约，不假装执行原版合成），完成真实 wrapper 编码→解码往返，数值 5 与原件身份保持。完整离线门禁两组各 552 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。真实 `ScaledProcessingPatternAdv` 仍需可构造的 AdvancedAE 编码样板及执行验证。
- 尝试把 `ScaledProcessingPatternAdv` 也纳入真实 jar 的 JUnit 往返：其构造器先把内层强转为 `AdvProcessingPattern`，普通 bench 样板不满足；进一步尝试用 `AdvProcessingPattern.encode` 构造真实定义，但 `ItemStack`/`Bootstrap` 在普通 Gradle JUnit 进程没有 NeoForge `LoadingModList`，静态初始化失败。这是测试环境边界，不是 wrapper 生产错误；已撤去失败的测试，完整离线门禁恢复两组各 552 项、失败 0。后续该项需要在真正加载 AdvancedAE + ExtendedAE Plus 的隔离 Minecraft 服务端里验收，不用伪造 AdvancedAE 内部实例冒充通过。
- A2 高级 wrapper 的隔离服务端验收已补：仅在 `-PextendedAePlusSelftest` 时加载安装版 ExtendedAE、ExtendedAE Plus、Glodium、ae2wtlib，不改变普通门禁模组集合；`-PselftestTask=scaled-advanced` 在真实 NeoForge 服务端用 `AdvProcessingPattern.encode` 构造有效高级处理样板，再实例化真实 `ScaledProcessingPatternAdv`，通过 `ScaledPatternCompat.encode/decode` 往返，确认倍率 6、外层类型、内层原件身份及输出数量保持；同场景 AdvancedAE CPU 继续完成独立原版铁块交付，`RESULT=PASS task=scaled-advanced`。此证据**仅覆盖 wrapper 元数据存档往返**，并未将该 scaled 样板装入供应器实际执行；不把后者冒充已验收。完整离线门禁两组各 552 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。
- A2 进一步用同一真实 `ScaledProcessingPatternAdv` 往返后的对象调用安装版 AE2 `CraftingCpuHelper.extractPatternInputs`：CPU 临时库存放 6 个铁锭，样板倍率 6，执行器实际扣完 6 个，`scaled-advanced` 仍 `RESULT=PASS`。这覆盖了 scaled 输入被执行器读取和扣料的关键接口，但仍未覆盖供应器接受该 wrapper 的整单推送/产物返还。完整离线门禁两组各 552 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。
- A2 当前样板改用真实 `AAEItems.ADV_PROCESSING_PATTERN` 定义，并由 AE2 `PatternDetailsHelper.decodePattern` 成功读回 `AdvProcessingPattern`；真实 scaled wrapper 在 `NetworkProductionPlanner` 中对 6 个目标规划为**一次外层物理推送**，其 `ProductionJob.save(registries)` / `load(registries,level)` 后未标 invalid、剩余任务校验通过。`scaled-advanced` 独立服务端再次 `RESULT=PASS`。这把高阶 wrapper 验证扩到计划与存档恢复，仍不等于真实供应器接单及产物返还；完整离线门禁两组各 552 项、失败 0、错误 0、skipped 0，`git diff --check` 无空白错误。
- A2 恢复后的 certified 程序再走 `ProductionJob.extract(decoded,...)`：真实高级 scaled wrapper 从隔离 CPU 库存扣完 6 个铁锭，预期输出计数为 6 个金块，`accepted(decoded)` 后程序 `done()`；安装版 AE2 通用扣取和本模组 certified 扣取两条路径都已在无头真实模组环境核对。`scaled-advanced` 为 `RESULT=PASS`，完整离线门禁两组各 552 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。仍不声称样板供应器物理推送及机器实际返货已完成。

- 第三方自有规划进一步实测：普通 AE2 供应器实际发布有效 `AdvProcessingPattern`，启用 ExtendedAE Plus smart doubling 后，供应器仍发布原始样板；反编译其 `CraftingSimulationStateMixin` 确认 **scaled wrapper 在原生 AE2 计算构造计划时生成，不是供应器发布时生成**。新增 `scaled-native-plan` 用未注册第三方 requester 请求 6 个金块，取得原生 `appeng.crafting.CraftingPlan`，实际含 `ScaledProcessingPatternAdv` 倍率 6、一次外层任务，未附着 certified 程序，`RESULT=PASS`。这直接证明附属原生智能倍增规划未被我们强制改道。
- 尝试把同一设置用于原版铁块 crafting 样板时，原生计划按 6 次普通 `AECraftingPattern` 执行而**未生成** `ScaledMolecularAssemblerPattern`；因此撤掉错误的“必定倍增”断言，不把无 wrapper 的情形冒充 A2 物理倍增。将该场景明确改名 `foreign-advanced-plus`，实际验证 ExtendedAE Plus 已加载且智能倍增开启时，未注册 requester 的原生计划仍由 AdvancedAE CPU 驱动分子装配室、交付铁块 6 个，certified 附着数为 0；`RESULT=PASS`。完整离线门禁两组各 552 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。

- A2 私有倍率字段兼容面收窄：公共 `getOperationsPerPush` / `getMultiplier` 继续按公开契约支持，但私有 `multiplier` 字段仅允许已验证的 `com.extendedae_plus.api.crafting.*` 类层级，不从其它附属模组同名私有字段推断执行语义。新增无关字段拒绝测试；真实 `scaled-advanced` 仍 PASS，上一轮完整离线门禁两组各 553 项、失败 0。此前追加本条记录的工具审批因额度暂不可用未执行，本轮恢复后补记。
- 修复无头验收“假绿”：此前 Minecraft 正常退出时，哪怕日志 `RESULT=FAIL`，`runSelftestServer` 仍可返回 Gradle 成功。现 Gradle 运行前删除旧回执，结束后要求精确匹配本任务的新 `PASS task=...`；harness 在正常停服前写结果回执，缺失/失败/任务不匹配均拒绝。真实负对照 `gate-failure` 输出 FAIL 后 Gradle **EXIT=1 / BUILD FAILED**；随后 `vanilla-canvas` 清除旧 FAIL、写入本次 PASS 并 **EXIT=0 / BUILD SUCCESSFUL**。两次均在隔离服务端运行，未操作客户端。

- 无头任务名称也改为显式校验：此前未识别的 `selftestTask` 会落入默认增长夹具，却用拼错的任务名写 PASS，造成验收对象错位。现 `runSelftestServer` 在启动 Minecraft / 重置隔离世界 / 删除旧回执之前拒绝未知任务。负对照 `not-a-real-task` 返回 `EXIT=1`、明确 `Unknown selftest task`，旧回执哈希保持不变；完整离线门禁再次 BUILD SUCCESSFUL，`git diff --check` 无空白错误。

- A2 物理执行负探测：仅在临时自检里把原生 6 次铁块计划显式改成附属工厂生成的 `ScaledMolecularAssemblerPattern` 一次推送，交真实 AdvancedAE CPU/AE2 供应器/分子装配室后只交付 1/6，2400 ticks 超时；新结果回执门禁正确返回 EXIT=1。安装 jar 字节码显示 wrapper 的 `assemble` 原样委托内层、`fillCraftingGrid` 先除以倍率，故不能据倍率元数据就推断任意装配器都会物理倍增。该探测不是附属模组原生自动生成的计划，不能把失败直接归因为它的正常路径缺陷；已撤掉临时任务和强制压缩，不在生产代码制造此类改道。原生 `foreign-advanced-plus` 随后重新实际交付 6 个、PASS，完整离线门禁通过。另修正自检超时诊断，显示真正执行 CPU 而非同网闲置的原生 CPU。原负探测日志保留在 `build/audit-selftest-scaled-molecular-execution.log`，不计作通过证据。

- 继续检查 D15 邻接的旧反馈桥时补上实际异常边界：`TwoNodeGrowthPlanner` 建输出索引先验证 outputs 非 null/非空及有效 primary，输入槽 null 视为无法证明的环；`prioritizeFeedback` 遇数量溢出返回原任务映射，不在已完成部分重排后抛异常。新增正常环仍 recycler-first、坏输出/空输入槽不被认作环、溢出不改变原映射三项回归。完整离线门禁两组各 556 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。此项修复旧桥异常/排序边界，**不等于 D15 多候选保留释放已经解决**；未注册第三方 requester 的绕过边界未改动。

- A2 高级处理 wrapper 继续补外部推料契约：真实 `ScaledProcessingPatternAdv.pushInputsToExternalInventory` 分别接收原生 AE2 helper 与恢复后 certified 程序扣出的输入，两条路径的计数 sink 均准确收到 6 个铁锭，`scaled-advanced` 实际服务端 PASS。这里的 sink 仅记录 API 推料，不是物理机器库存，更不是产物返还，仍保持验收边界清楚。完整离线门禁 BUILD SUCCESSFUL，`git diff --check` 无空白错误。

- A2 sparse/condensed 输入分支覆盖：将真实高级 scaled 夹具参数化为一个输入槽及两个同材料输入槽；后者 AdvancedAE 会凝聚为一个 `IInput`，触发 `ScaledProcessingPatternAdv.pushInputsToExternalInventory` 的不同实现分支。倍率 6 下，原生与 certified 两路径分别准确扣取/推送 6 与 12 个铁锭，存档恢复、一次外层任务与 6 个目标输出计数均通过；日志明确 `sparseSlots=2 condensedSlots=1 inputAmount=12`，`scaled-advanced` PASS。完整离线门禁 BUILD SUCCESSFUL，`git diff --check` 无空白错误。仍保持 sink/API 与真实机器返货的证据区别。

- D15 保留/释放协议形成测试内参考账本（尚未接入生产 mixin）：保留的反馈包仍留在 waiting 但单独记 held，避免重复接收；只在 provider 真正接受推送后按真实 AE2 扣料消耗冲销 held/waiting，拒收重注入不冲销；剩余任务不再可能消费反馈时，把未用 held 释放给 requester。新增真实 AE2 helper 驱动的三种时间线：替代材料晚到导致保留物未被消耗后可释放、反馈确实消耗不再同时交付、provider 拒收及同包不可重复接受。完整离线门禁两组各 559 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。参考模型只证明这些守恒场景；生产接入仍须验证 tick/推送成功边界、种子净增量恢复、NBT 和两类 CPU，D15 不记完成。

- D15 参考账本补两项生产接入前的关键守恒：requester 拒收不扣 held/waiting、不挪入网络，部分接收仅扣真实接收量，SIMULATE 不改库存或记账；未消费反馈在释放时先补回起手种子，保留为最终归网 surplus，不计作 requester 交付。两项模型回归通过，完整离线门禁两组各 561 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。生产适配不能直接复用原生 `insert` 的提前扣账路径，须按实际接收量结算并同时覆盖 AE2/AdvancedAE；当前仍只在测试参考模型中验证。

- D15 将参考账本计数核提取为共享 `DeferredFeedbackReservation`：intake/release 只给出不改状态的 offer，实际保留、已接受输入消费及真实交付分别 commit；恢复要求 held 不超过 native waiting/库存，负值、重复结算和整数溢出在修改状态前拒绝。已有真实 AE2 时间线模型改用该组件，并新增恢复一致性/非变异 offer、原子溢出与重复结算守卫测试。完整离线门禁两组各 563 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。组件目前仅被测试驱动，AE2/AdvancedAE mixin 适配尚未接入；这是生产集成前的共享计数核，不把它记为 D15 修复完成。

- D15 共用组件继续补上多候选输入量与实际消费快照：pending 上界通过 `IInput.isValid` 覆盖非首候选及模糊有效输入，每槽取候选最大模板量而不把互斥候选求和，逐任务乘数量并对溢出饱和；原样返回的催化剂不算净消耗。实际扣料量须在调用 provider **之前**快照，provider 接受后再冲账，避免 provider 清空可变 `KeyCounter[]` 后读成 0。三项新增回归（候选上界/真实溢出、互斥候选与催化剂、provider 清空计数后的快照）通过；完整离线门禁两组各 566 项、失败 0、错误 0、skipped 0。仍未接入两类生产 CPU mixin，D15 保持未完成。

- D15 生产接入方案简化：核对安装版 AE2 `executeCrafting` 字节码，provider `pushPattern` 成功后才增加 waitingFor、递减任务；因此输入消费快照须在调用前保存、消费结算在成功后，释放只在本 tick 调度结束后做。共享组件不再在接收时预测“应直接交付多少”，改为 `offerBuffer` 缓冲所有已期望但尚未接收的终端产物；只有剩余任务不可能再消耗反馈时才释放。这样避免候选晚到导致错误选择，也避免走原生 `insert` 的提前扣账。先前候选切换/拒收/种子/恢复模型全部改用简化接口并通过完整离线门禁，未改变生产 mixin；D15 仍未完成。

- D15 新增真实无头回归夹具 `legacy-feedback`：实际 datapack 配方 P（钻石+铁锭→绿宝石×2）、Q（绿宝石→钻石）、R（青金石/绿宝石标签→铁锭），编码 R 以青金石为首候选且允许替代；实际 AE2 候选日志为青金石、青金石、绿宝石。手工构造**测试专用**原生计划 P×2/Q×1/R×1，起手钻石与铁锭各 1，目标绿宝石×2；支持的两节点增长环确实激活。旧反馈桥真实 CPU/供应器/分子装配室执行后只交付 **1/2**，600 ticks 时 CPU 仍 active/busy，新门禁正确 EXIT=1。这是未解决回归，不算通过；增加了 AdvancedAE 对应任务入口，尚未跑。
- 同夹具正对照 `legacy-feedback-control` 仅额外提供并借入一个首候选青金石，真实执行交付 2 个、CPU 释放，PASS；requester 每次最多接受 1 个，避免原生 CPU 超量回包影响精确交付断言。起初三轮增长版本被回收任务贪心顺序混淆，已缩为上述两轮/单次回收与补料夹具。生产反馈 mixin 尚未修改，回归保持红色；完整离线 `test routeFunctionalTest build -x productionBenchmark` 通过，不把普通门禁绿色等同 D15 已修复。

- D15 接入前纠正参考账本的催化剂错误：之前将“原样返回”从消费快照/pending 排除，混淆了净消耗与物理离开 CPU；真实 provider 接受后催化剂暂不在库存，而原生代码会另增其返回等待量。现同键催化剂在调度前仍阻止提前释放，接受推送时按物理输入冲销 held，返回包再作为新 waiting 接收。新增真实 AE2 helper 生成容器返回计数的完整时间线回归，旧错误断言同步更正；完整离线门禁两组各 567 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。该修正仍在共享组件/模型层，生产旧桥保持原状，真实 D15 回归仍待转绿。

- D15 **AE2 旧反馈桥生产接入**：仅对已认定为本桥可处理且非 certified/非未注册第三方的新任务启用延迟账本，终端产物缓冲而不提前扣 waiting；provider 调用前快照输入，只有返回成功才按物理转移结算；tick 调度结束后，无剩余反馈消费者才先恢复种子，再按 requester 实际接收量释放，拒收仍留 CPU。新 NBT 标记区分延迟协议，旧无标记存档继续原路径；已加入 held/native waiting/库存的一致性恢复守卫，尚未做该路径的真重启验证。此前真实 `legacy-feedback` 1/2 卡单回归现已 **PASS，2 个交付且 CPU 释放，46 ticks**；首候选正对照也 PASS；`foreign` 仍原生计划/原生执行且 PASS。完整离线门禁两组各 567 项、失败 0、错误 0、skipped 0；`git diff --check` 无空白错误。AdvancedAE 的旧桥尚未接入同协议，D15 仍未整体完成。

- D15 **AdvancedAE 旧桥同步生产接入**：同一共享账本、推送前快照/成功后结算、tick 末实际接收交付及持久化标记用于其独立 CPU/job/tracker；未注册第三方和 certified 的隔离条件保持。真实量子 CPU `legacy-feedback-advanced` 交付 2 个并释放，46 ticks PASS；`foreign-advanced`（原生计划+自有 CPU）与 `advanced`（certified）均复验 PASS。首次完整门禁暴露旧 ASM 测试仍要求“只有 native insert 可交付”，已按新协议精确改为两类桥均仅 `releaseFeedback` 可调用 link 或修改订单剩余量，intake/消费钩子仍禁止交付；重跑两组各 567 项、失败 0、错误 0、skipped 0，`git diff --check` 无空白错误。两类 CPU 的新反馈账本真重启/进行中 NBT 验证仍未补，D15 暂不宣布全部闭合。

- D15 进行中 NBT 已覆盖两类真实 CPU：新增 `legacy-feedback-reload` / `legacy-feedback-advanced-reload`，在首轮已派发且产物未返回时暂停后续调度，等实际 held=2 后把完整 CPU NBT 编码为字节再解码/加载，确认 held/协议/job 标记保持，再解除暂停完成真实交付。AE2 保存 1506 bytes、50 ticks PASS；AdvancedAE 保存 1474 bytes、47 ticks PASS。这是同进程持久化往返，不冒充真正停服重启。
- 此轮修复了验收器的 CPU 身份误判：AdvancedAE 的 advertised 空闲 CPU 与提交时新建的逻辑 CPU 不是同一个对象，检查前者 busy=false **不能推断任务由原生 CPU 接走**。最初 AdvancedAE reload 因看错对象未捕获 held 而正确报 FAIL；现按提交结果 crafting link UUID 在真实 busy CPU 的 job link 中查找所有者，并校验执行器家族，所有保存/加载/释放判断都用实际所有者。AdvancedAE 原生计划夹具仅调整 bytes 使同网 1k CPU 不适合，不改变计划协议。新的 `foreign-advanced` 已依据真实 busy CPU/link 核对原生计划+AdvancedAE 执行并 PASS；先前只打印选中对象的证据强度不足，以此轮重新核对为准。完整离线门禁通过，D15 真进程重启仍待补。

- D15 真停服/新进程恢复已覆盖两类执行器：`legacy-feedback-restart1` 在真实 AE2 CPU 暂停并持有 2 个反馈包、requester 尚收 0 个时保存世界、停服；另一个服务端进程 `legacy-feedback-restart2` 从实际区块存档读回 held=2 和相同 crafting link UUID，再解除暂停实际交付 2 个并释放，两个阶段 PASS。AdvancedAE 的独立 `legacy-feedback-advanced-restart1`→新进程 `...restart2` 也持有 2 个、同 link 恢复并 PASS。CPU 状态确实来自世界存档；测试 requester 不是持久化机器 BE，而是用单独保存的 requester-side link NBT 在新进程重建并接回原 UUID，不冒充 ME 接口自动持久化验收。完整离线门禁两组各 567 项、失败 0、错误 0、skipped 0，`git diff --check` 无空白错误。新协议的主要多候选卡单、进行中 NBT 与真重启链已贯通，旧协议兼容/其它边界仍继续回归，不据此宣称整个审计目标完成。

- D15 生产净增量/拒收边界实测：新增两类 CPU 的 `legacy-feedback[-advanced]-seed-rejectonce`，实际借入 1 个目标绿宝石作为起手种子，requester 首次拒收且每次最多收 1 个；AE2 与实际 link 所属 AdvancedAE CPU 均最终精确交付绿宝石 2 个、ME 库存仅恢复种子 1 个、拒收恰 1 次、CPU 释放，各 47 ticks PASS。这把此前仅模型证明的种子不算交付/拒收不改投/部分接收逐次结算扩到真实生产桥。完整离线门禁 BUILD SUCCESSFUL；无客户端操作。

- D15 损坏存档负例生产验证：两类 CPU 的 `legacy-feedback[-advanced]-corrupt` 在暂停并实际 held=2 时把 NBT held 改为 3，真实 readFromNBT 触发新协议一致性守卫，任务/链接取消、CPU 释放、ME 只归还真实 2 个绿宝石、requester 收 0 个，两场景均 15 ticks PASS。故意损坏未产生虚构物品或永久卡单；完整离线门禁 BUILD SUCCESSFUL。此负例只覆盖 held 超库存，不能据此泛称所有损坏 NBT 均安全。

## 下阶段

1. 用户已选择暂不操作客户端；继续用独立无头服务端覆盖画布 apply、CPU 执行、机器 requester 交付及重启。按钮位置、悬停效果等像素级 UI 项仍未验收，不以无头结果代替。
2. 继续审计剩余真实缺陷与客户端交互；保持每批独立可回滚，不覆盖未跟踪的审计快照。

## 2026-10-27：A5 生产线程池真实满载回归
- 新增 `RoutePlanningServiceTest.actualProductionPoolRejectsFullQueueAndRecoversWithoutLosingAcceptedJobs`：直接取得生产池，锁住两个线程、填满八个槽；第九个 Job 返回 LIMIT/不是缺料、不提交；释放后八个已接收 Job 与后续请求均成功验证。
- 定向测试 PASS，日志 `build/audit-worker-saturation-targeted.log`。这是线程池边界验证，不代表真实整合包并发负载验收完成。
- 全量门禁本轮未通过：568 tests 中 1 项失败；`removedOrReplacedPatternRejectsCachedProgram` 在首次建计划的 validation 超时（类级运行亦复现）。新增满载测试通过。未放宽生产时间预算或掩盖失败；下一步查明该验证超时的冷启动/耗时来源。

## 2026-10-27：区分权威复核切片超时与总预算耗尽
- 复现旧失败：单独运行 `removedOrReplacedPatternRejectsCachedProgram` 仍失败；堆栈位于 LiveCapture.validate 的 usedItems 循环检查，validationMs=8.1564，总请求仅约51ms，并非5秒总预算耗尽。冷启动/瞬时停顿的具体内部耗时来源未进一步归因，不能据此声称网络变化。
- 生产修复：单次2ms复核切片单独标记；最多重试两次，每次从头重新读取当前库存、全部已观察样板和起手材料，不复用已检查前缀。保留原始总deadline；连续三次超过切片仍 typed LIMIT，不无限重试、不误报缺料。第三方执行归属与CPU改投策略未改变。
- 新增三个确定性测试：完整复核重试成功且deadline/worker不变；重试前网络变化拒绝提交；重复切片耗尽有界失败。原缓存测试的完成助手按生产状态机执行至最多三个复核tick，不吞异常、不重建请求。
- 完整 `test routeFunctionalTest build -x productionBenchmark` PASS，两套各571 tests、0 failures、0 errors；`git diff --check` PASS。
- 隔离无头 `runSelftestServer -PselftestTask=paths` PASS（fresh result receipt）；日志 `build/audit-selftest-paths-validation-retry.log`。未操作客户端、未安装到玩家实例。整个审计目标仍有真实规模/附属物理执行/客户端表现待验收。

## 2026-10-27：D33 静止画布悬停避免重复 Point 分配
- 核对安装的 ae2ct-1.21.1-1.1.1.jar `CraftingTreeWidget.getMousePoint` 字节码：依赖鼠标x/y、screen GUI left/top、outputX/Y、spacingX/Y、stackLength、scroll；不能仅按鼠标坐标缓存。原始反汇编证据 `build/audit-ae2ct-widget-javap.txt`。
- RouteTreeWidgetMixin 仅对本模组 hover/Shift右键查询缓存原生返回的 Point；任何上述参数变化即重新调用原生命中算法。不重写坐标数学，不修改原生 Point，不缓存节点或 map，因此同坐标下树重建仍读取当前 NodeManager；边界检测仍每次执行。静止不变帧不再重复申请 Point，鼠标/视口变化仍按需分配。
- 新增无客户端测试：连续10000个不变输入不失效，全部10类输入逐一变动均失效；ASM读取实际安装类，核对原生方法全部字段/调用依赖，避免悄悄遗漏已知坐标状态。
- 完整离线门禁 `test routeFunctionalTest build -x productionBenchmark` PASS，两组各573 tests/0 failures/0 errors；`git diff --check` PASS。尚未加载客户端实际变换该可选Mixin，不能声称已验证像素悬停/缩放表现或实际GC收益；遵守用户暂不操作客户端选择。

## 2026-10-27：必跑四门禁与 A4 NeoECO/AdvancedAE 实际组合
- 用当前工作树重新依次运行审计§6.2的 paths/growth/cancel/reload，四次均 Gradle成功且 fresh receipt 精确匹配各任务；结果分别 delivered=1/3/0（CPU idle）/4，140/119/70/167 ticks。日志及保留回执 `build/audit-required-<task>.log` / `.receipt.txt`，不依赖此前结果。
- 新增仅显式 `-PneoEcoSelftest` 才加入的隔离运行时：实际 neoecoae-21.2.2.jar + ldlib2-neoforge-1.21.1-2.2.42-all.jar；未改变默认发布依赖、未更换玩家 mods。实际服务端 Mod List 核对 AdvancedAE1.6.12、NeoECO21.2.2、AE2 19.2.18、NeoForge21.1.259。
- `advanced -PneoEcoSelftest` PASS：运行时 certified program attached on advanced_ae，精确交付3个 diamond 后执行CPU释放；count=1 hosts={advanced_ae=1}。这把 A4 从仅jar字段核对扩展为实际 NeoForge/两模组Mixin组合下提交执行，日志 `build/audit-selftest-advanced-neoeco.log`。开发服务端运行时证据，不冒充全ATM10所有可选附属组合验收。
- `foreign-advanced -PneoEcoSelftest` PASS：未注册第三方 requester 的实际原生 `appeng.crafting.CraftingPlan` 仍交给 link UUID 所属的真实 `AdvCraftingCPU`，精确交付1且释放，没有强制接管执行层。日志 `build/audit-selftest-foreign-advanced-neoeco.log`。两个组合回执均另外保存在build目录。
- 限定组合加载时存在未安装可选附属类的WARN；没有据此屏蔽报错/禁用兼容Mixin，场景经过实际运行与结果回执成功。未操作客户端。A1真实画布规模、A2所有scaled物理处理路径、A5真实整合包并发容量以及客户端视觉交互仍未全验收，不标记总目标完成。

## 2026-10-27：A2 实际包装器范围盘点
- 逐jar读取当前安装包名称候选，收敛审计提及的五模组：仅ExtendedAE Plus含三种 Scaled*Pattern 类，其余四模组当前jar无该命名候选；记录版本和SHA256至 `build/audit-installed-scaled-pattern-inventory.json`。名称扫描不是任意命名样板完整性证明，也不把本模组ScaledPatternCompat工具类误算为包装器。
- 新增 `docs/canvas-audit-runtime-coverage.md` 对照当前真实运行门禁与A1–A5/客户端验收空白；明确真实scaled机器交付尚缺，不以接口往返或native plan生成冒充完成。下一步应沿附属实际生成的processing包装器验证物理加工，不恢复已经证明不通的强制molecular手工改投。

## 2026-10-27：A2 advanced scaled 真实物理加工交付
- 新增隔离任务 `scaled-processing-execution`，必需 `-PextendedAePlusSelftest` 的实际附属运行时。未注册requester走原生AE2规划，ExtendedAE Plus smart doubling实际生成 ScaledProcessingPatternAdv倍率6、一次物理任务；不手工压缩/替换计划。
- 夹具建真实供应器→熔炉顶部→真实漏斗→真实ME接口回网，再由实际link所属AdvancedAE CPU交付。测试代码仅初始装入煤与ME铁锭，不手动搬运/消耗/生成加工原料或成品。烧炼配方为隔离数据包铁锭→金块、cookingtime1，不是声称原版有该配方。
- 带强化守恒和材料检查的重跑PASS55ticks：初始铁锭9，ME剩3；实际交付金块6、ME金块0；熔炉输入/输出与漏斗均空；CPU释放，certifiedAttaches=0。实际原生CraftingPlan、实际AdvCraftingCPU。回执单独保存；日志 `build/audit-selftest-scaled-processing-execution.log`。初次守恒诊断使用受保护getItems导致编译失败，已改用公共getItem，最终以重跑成功为准。
- 这补上该advanced processing包装器真实物理链证据；不泛化到plain processing/molecular、全部机器、全部稀疏槽形态或进程重启。已同步runtime coverage，保持其它验收空白。

## 2026-10-27：A2 普通 processing 与多附属组合
- 新增 `scaled-processing-plain-execution`，源样板由实际AE2 `encodeProcessingPattern(List,List)` 编码，明确要求原生smart doubling输出确切的 ScaledProcessingPattern（不是Adv包装器）×1/倍率6。第一次编码误用数组，编译指出真实API要求List；已按本地编译证据修正，不改依赖版本。
- 普通processing独立真实物理链PASS55ticks，材料与Advanced变体相同守恒，原生CraftingPlan、真实link所属AdvCraftingCPU，交付6、CPU释放、无certified接管。
- 同时启用 `-PextendedAePlusSelftest -PneoEcoSelftest`，两种processing任务分别PASS（Advanced60ticks；普通任务最终代码重跑PASS，见日志）。实际NeoECO/ExtendedAE/ExtendedAE Plus/AdvancedAE组合加载，不只是分别加载成功；各日志 `build/audit-scaled-processing[-plain]-execution-neoeco.log` 及独立receipt。最终普通夹具只创建普通样板，不先创建/初始化一个无用高级样板。
- 完整离线门禁两套各573 tests/0 failures/errors，build与diff检查通过。molecular物理交付、其它机器/稀疏槽与真重启没有被这些结果覆盖，仍保留为未验收；未操作客户端。

## 2026-10-27：A2 Strict molecular 包装层真实丢失修复
- 追查安装的PatternScaler和native smart-doubling字节码，发现真实super matrix路径有非Scaled命名的StrictMolecularAssemblerPattern，不能由上一轮名称扫描证明不存在。该层getDefinition直接返回delegate定义，原codec把它当leaf保存。
- 真实服务端红例 `scaled-strict-roundtrip -PextendedAePlusSelftest` 复现：ScaledMolecularAssemblerPattern(Strict(...))往返后inner变成AECraftingPattern；RESULT=FAIL且Gradle失败。红日志 `build/audit-selftest-scaled-strict-red.log` 保留。严格输入语义丢失已证，不是推测。
- ScaledPatternCompat对已核对的精确ExtendedAE Plus Strict类显式记录层：透过实际delegate字段递归保存原始定义，保存/恢复allowScaling与multiplierLimit，经该附属公开wrap工厂恢复严格层；不对其它模组任意私有字段推断语义。严格层operations必须1，缺旗标/类型不符受控拒绝。旧普通样板和旧scaled元数据仍按原规则读取；没有对已经丢失严格层信息的旧存档凭空猜测迁移。
- 强化真实服务端绿例同时验证inner确切类型、allowScaling=false、limit17不变，以及实际ProductionJob存储/载入和remainingTasks校验。加NeoECO组合重跑PASS，日志 `build/audit-selftest-scaled-strict-green.log` 与单独receipt。
- 新增损坏严格层元数据负例；完整门禁两套各574tests/0failures/errors，build和diff检查通过。这是包装语义/生产程序持久化修复，不冒充真实super matrix物理交付验收；不恢复强制普通装配室倍率探针，第三方执行边界不变。

## 2026-10-27：A2 molecular 原生Super Matrix物理链贯通
- 核对实际SuperAssemblerMatrixCalculator、Cluster、HybridCore字节码：最小3立方体，棱角frame、面wall、内部hybrid；该hybrid同时计crafter/speed并供pattern inventory，矩阵自身wrap Strict样板。先前普通装配室失败探针不是这个执行器，不能推断矩阵不支持。
- 新增 `scaled-molecular-execution`：在隔离世界实际放置3×3×3结构与网络供电连接，等附属自动成型/发布Strict样板，只把正常AE2铁块样板放入真实hybrid pattern inventory，不手工构造cluster、不替换native计划、不向普通装配室强塞倍率包装器。
- 最终强化运行用ExtendedAE/ExtendedAE Plus/AdvancedAE/NeoECO实际组合：要求确切ScaledMolecularAssemblerPattern、一次任务、输出6；日志实际为 ScaledMolecular[Mult=6] Strict[AECraftingPattern]×1。实际link所属AdvCraftingCPU交付铁块6并释放；初始铁锭54消耗完、ME无剩余铁锭/铁块，材料校验通过；certifiedAttaches=0，13ticks PASS。日志 `build/audit-selftest-scaled-molecular-native.log` 和独立receipt。初次运行缺包装类硬断言，已补上后重新运行，最终证据以前述强化运行结果为准。
- 三种已安装Scaled包装器现在各有至少一条真实机器交付链，且Strict层已有真实持久化红→绿；仍不扩张成所有配方、结构尺寸、并发与跨进程恢复通过。完整离线门禁成功，两套仍各574tests/0failures/errors；diff检查通过。没有客户端操作或玩家实例安装。

## 2026-10-27：A1/D37 合法最深路径误拒绝修复
- 回归实际生产RouteCanvasPlanner：协议接受root加24层，但最深选中样板若仍有原料输入，规划器无条件childPath生成第25层而抛IllegalArgumentException。红例实际失败堆栈位于RouteCanvasPlanner→RouteGraphMath.childPath，日志 `build/audit-canvas-depth-red.log`。最初测试编译泛型误用AEKey而Bench输入需要BenchAEKey，修正后才取得上述真实红例，不把编译失败算行为复现。
- 共享canExtendPath边界；规划器只略过协议不能表示的UI子地址，不删物理配方输入。所有输入仍经primaryInputs和recipes进入完整证明。规划入口同时明确校验路径合法性，深度25/非法地址typed INVALID，不泄露普通childPath异常。
- 强化回归：深度24（25个配方节点）在目标1和100000均证成，所有物理样板次数和末端借入量精确；末端无原料仍UNREACHABLE，第25层仍INVALID。不抬升深度/分支/报文上限，不以隐藏叶子绕过耗料证明。
- 完整离线门禁两套各575tests/0failures/errors，build和diff检查通过；当前代码隔离 `paths` 真实apply→CPU→交付fresh receipt PASS，日志 `build/audit-selftest-paths-depth-fix.log`。这补边界实现正确性，不能推断真实ATM10复杂画布不会超过128/24000上限。未操作客户端。

## 2026-10-27：A1 不削弱约束的报文压缩
- RouteCanvasPayload作为apply生产序列化入口：先保留名称；超过24000字符才省略服务器原本可选的诊断名称n，完整保留每个材料k/样板c/路径p与amount。省略后仍超限则明确字符数与未应用；超过128分支明确实际数与未截断。RouteScreen在加入pending/发送前处理错误，不静默丢节点、不改规划路径、不抬高网络限额。
- 分支和大报文限额共享到RouteApply/RouteNetwork/客户端send。服务器收到省略n的节点若无效，诊断回落该节点path，而非「null」。
- 新增四项回归：128个长名称节点压缩后逐项核对全部约束和100000数量，实际Request CODEC编解码严格相等；普通报文保留中文名；129分支/纯约束仍超限均拒绝；省略名后的服务端无效节点保留明确路径且不更改原policy。
- 完整门禁两套各579tests/0failures/errors，build和diff检查成功；隔离无头paths回归fresh receipt PASS，日志 `build/audit-selftest-paths-payload.log`。该paths为现有真实apply/执行回归，不冒充已经通过客户端按钮发送过128分支压缩报文。真实ATM10画布规模和客户端视觉仍未验收，用户暂不操作客户端要求保持。

## 2026-10-27：A5 真实服务端并发准入/取消/排空后恢复
- 新增 `planning-load` 无头任务，经真实 RoutePlanningService.planAsync 和真实IGrid快照/生产worker/服务端复核一次性发起32个请求；第33个在同tick准入阶段必须完成为typed LIMIT。随后立即取消最前8个outer future，不使用替代调度器、替代executor或假快照。
- 真实结果：passed10、worker queue阶段LIMIT14、cancelled8、准入溢出LIMIT1；所有已接收32个请求在有界等待内终止，没有把排队失败误报为缺料/普通异常。planning-load并不声称8个已运行worker被中断（它们是在首tick前取消），在飞取消由原有Job生命周期测试覆盖。
- 排空后再发起真实规划请求，成功取得certified plan并正常提交AE2 CPU，实际分子装配室交付铁块1个，CPU释放，fresh receipt PASS21ticks。日志与独立回执 `build/audit-selftest-planning-load.*`。多个成功预览没有自动提交生产任务，只有恢复请求真正提交。
- 完整离线 `test routeFunctionalTest build -x productionBenchmark`成功，测试数仍各579，diff检查通过。该结果直接证实2线程/8队列在32请求突发下会拒绝部分任务，不声称容量足够；证明的是typed背压、取消、终止与后续恢复，不代替真实全包大网容量测量。未操作客户端。

## 2026-10-27：真实ME接口 + AdvancedAE certified真进程重启
- 新增advanced-restart1/2，复用真实ME接口配置槽+crafting card自行请求链，不手工提交或重建requester-side链接。Advanced场景仅在隔离夹具把旁边原生CPU设PLAYER_ONLY，使机器自动选择Advanced；仍独立用机器链接UUID找到实际busy执行CPU，不看advertised空闲placeholder。
- 两类restart存取改用实际链接所属CPU，保存时核对原生job+ProductionJob程序+接口恰1个任务。phase1记录预期UUID到仅供核对的sidecar，世界和机器链接由原生区块存档真正保存；新进程必须看到同一机器UUID和certified程序才承认恢复，拒绝把取消后新建的替代任务算恢复。
- 强化交付验收：原先held+network>=3不足以排除错误改投；现必须真实ME接口held==3且network==0，实际CPU释放。sidecar没有序列化或重建机器requester/链接，与此前legacy测试重建requester方式区分。
- 最终当前代码四个进程依次运行（均-PneoEcoSelftest）：advanced-restart1 PASS24ticks→新进程advanced-restart2 PASS110ticks，同UUID d23cd6e7-95be-479f-aa64-4601fbe8708e、真实AdvCraftingCPU、接口3/网络0；restart1→新进程restart2也同UUID 82af6e00-3d50-4a21-8456-ebfaa913fdf1、原生CPU、接口3/网络0，PASS。日志与逐阶段fresh receipts `build/audit-<task>-machine.*`。
- 初次Advanced两阶段仅检查busy/交付，随后补同UUID与程序恢复硬断言后重新四阶段全部运行；最终证据以强化后上述日志为准。完整离线门禁BUILD SUCCESSFUL，测试数仍各579，diff检查通过。未操作客户端、未安装玩家mods；原生scaled/其它配方重启不由该certified增长链替代。
