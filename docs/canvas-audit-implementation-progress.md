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

## 2026-10-27：D15 拒绝缺失延迟反馈计数的损坏存档

- 只读复核发现两类 CPU 的新协议都无条件 `getLong("aecraftrouteDeferredHeld")`；若 marker 仍 true、held key 缺失或非 LONG，NBT API 默认读 0，账本可恢复成零信用而 CPU 库存/`waitingFor` 尚有实际保留反馈。
- AE2 `GrowthCpuMixin` 与 AdvancedAE `AdvancedGrowthCpuMixin` 在恢复时现要求该 key 存在且类型为 `Tag.TAG_LONG`，否则进入已有 invalid-save 取消路径；不合规任务不会以零信用继续。未注册第三方 requester 与 certified 执行隔离不变。
- `legacy-feedback-corrupt` 与 `legacy-feedback-advanced-corrupt` 真实隔离 NeoForge 服务端场景现删除该字段后都 PASS：各确认 CPU/link 取消、requester 收 0、ME 只归还真实 held=2；异常日志含 `Missing or invalid deferred feedback held count`。另复验有效 `legacy-feedback-reload`（AE2，held=2，50 ticks）与 `legacy-feedback-advanced-reload`（AdvancedAE，held=2，47 ticks）均仍成功完成有效 NBT 往返与交付。
- 完整离线门禁 `test routeFunctionalTest build -x productionBenchmark` BUILD SUCCESSFUL，两个测试任务各582 tests，0 failures/errors/skipped；`git diff --check` 通过。无客户端操作、未安装玩家实例。该负例覆盖缺失字段，尚未逐项覆盖其它所有错误 NBT 类型/损坏组合。

## 2026-10-27：D15 反馈元数据版本/类型校验统一

- 复核发现仅靠缺字段守卫不足以覆盖旧键混合：新键存在但类型错误时读取默认值会遮蔽可用的旧 `ae2vmGrowthFeedback`/`ae2vmRestoreTargetSeed`；`aecraftrouteDeferredFeedback` 类型错误也会伪装成“旧协议”。
- 新增共享解析器 `src/main/java/com/aecraftroute/addon/vm/LegacyFeedbackSave.java`：按 NBT 类型选择新键/旧键（有效新键优先，其次有效旧键，坏键且无可回退值时拒绝），缺省旧协议保持兼容；非负种子、deferred 标记必须为按位布尔、true 时必须同时有增长标记与 LONG held。两类 CPU 的 `readFromNBT` 都在修改任何状态前调用它，非法元数据记录原因并取消任务。
- 新增 `src/test/java/com/aecraftroute/addon/vm/LegacyFeedbackSaveTest.java` 七项：旧键兼容、合法新 false 覆盖旧 true、标记类型错误、缺增长标记/缺 held、坏新键回退有效旧键、无有效回退拒绝、负种子拒绝。定向测试已通过。
- 新增真实隔离服务端场景 `legacy-feedback[-advanced]-marker-corrupt`（deferred 标记写成字符串）与 `...-heldtype-corrupt`（held 写成字符串）；四个均 PASS，均记录 `Invalid saved ... feedback metadata; cancelling`，CPU/链路取消、requester 收 0、ME 只回真实 held=2。旧缺失-held 与合法 reload 场景另做回归。
- 补充孤立计数守卫：deferred 标记为 false/缺失但残留 `aecraftrouteDeferredHeld` 时，非零值或类型错误一律拒绝，正常写出的 0 仍兼容旧协议；新增对应回归。
- 追加一致性守卫：写入端仅在原生 job 存在时写 deferred 标记，因此 marker=true 但缺少 `job` 的存档视为无主信用，解析器直接拒绝；新增「无 job 拒绝 / 有 job 接受」单测。
- 新增真实隔离服务端`legacy-feedback[-advanced]-oldkeys`：在真实 held=2 时把新键改写成未重命名的`ae2vmGrowthFeedback`/`ae2vmRestoreTargetSeed`，仍须通过`LegacyFeedbackSave`恢复、完成 NBT 往返与 2 个绿宝石交付。首次两例均`FAIL delivered=2/3`，根因是任务没有进入 reload 分支、且请求量落到默认 3；改为进入 reload 分支并请求 2 后：AE2 `PASS 50 ticks`、AdvancedAE `PASS 47 ticks`，日志含`legacy-key save rewritten to the pre-rename keys`、`held=2`与`round-trip`。改写后仍断言新键确已移除，避免夹具假阳性。
- 该批元数据修复的最终证据：完整离线门禁`test routeFunctionalTest build -x productionBenchmark` BUILD SUCCESSFUL，两组各 **591** tests、0 failures/errors/skipped；`git diff --check`通过。真实服务端回归全部 PASS：`legacy-feedback`、`legacy-feedback-control`、`legacy-feedback-marker-corrupt`、`legacy-feedback-heldtype-corrupt`、两类`advanced`对应变体、`legacy-feedback[-advanced]-seed-rejectonce`、`legacy-feedback[-advanced]-oldkeys`、`legacy-feedback[-advanced]-reload`、`legacy-feedback[-advanced]-corrupt` 与`foreign-advanced`。未操作客户端、未安装玩家实例；未注册第三方 requester 仍走原生计划/自有执行。

## 2026-10-27：真实ME接口 + AdvancedAE certified真进程重启
- 新增advanced-restart1/2，复用真实ME接口配置槽+crafting card自行请求链，不手工提交或重建requester-side链接。Advanced场景仅在隔离夹具把旁边原生CPU设PLAYER_ONLY，使机器自动选择Advanced；仍独立用机器链接UUID找到实际busy执行CPU，不看advertised空闲placeholder。
- 两类restart存取改用实际链接所属CPU，保存时核对原生job+ProductionJob程序+接口恰1个任务。phase1记录预期UUID到仅供核对的sidecar，世界和机器链接由原生区块存档真正保存；新进程必须看到同一机器UUID和certified程序才承认恢复，拒绝把取消后新建的替代任务算恢复。
- 强化交付验收：原先held+network>=3不足以排除错误改投；现必须真实ME接口held==3且network==0，实际CPU释放。sidecar没有序列化或重建机器requester/链接，与此前legacy测试重建requester方式区分。
- 最终当前代码四个进程依次运行（均-PneoEcoSelftest）：advanced-restart1 PASS24ticks→新进程advanced-restart2 PASS110ticks，同UUID d23cd6e7-95be-479f-aa64-4601fbe8708e、真实AdvCraftingCPU、接口3/网络0；restart1→新进程restart2也同UUID 82af6e00-3d50-4a21-8456-ebfaa913fdf1、原生CPU、接口3/网络0，PASS。日志与逐阶段fresh receipts `build/audit-<task>-machine.*`。
- 初次Advanced两阶段仅检查busy/交付，随后补同UUID与程序恢复硬断言后重新四阶段全部运行；最终证据以强化后上述日志为准。完整离线门禁BUILD SUCCESSFUL，测试数仍各579，diff检查通过。未操作客户端、未安装玩家mods；原生scaled/其它配方重启不由该certified增长链替代。

## 2026-10-27：D15 恢复路径的溢出/畸形任务计数加固

- 只读复核（子代理 03e483e0）确认无高危缺陷，但指出 `DeferredFeedbackReservation.potentialInput` 在 `Math.*Exact` 溢出时返回 `Long.MAX_VALUE`，而 `offerRelease` 把任何非零 pendingInput 当作"未来仍可能有消费者"，于是 `Release(0,0)` 永久成立。任务计数来自存档 job NBT，因此被写成 2^62 的损坏存档会让 CPU 永远保留信用且永不完成原生任务（需手动取消）。
- 新增 `DeferredFeedbackReservation.requireSaneRuns(Map<IPatternDetails,T>,ToLongFunction<T>)`：负计数、加法溢出、或总运行数超过 `MAX_TASK_RUNS=1_000_000_000_000L`（与规划器 `RouteGraphMath.MAX_DEMAND` 同量级）一律抛 `IllegalArgumentException`。两类 CPU 的 `readFromNBT` 在 `ledger.restore(...)` 之前调用它，于是"自身计数永远排不空"的 job 在改变任何 CPU 状态前就被拒绝并按既有 invalid-save 路径取消，不会把信用钉死。
- 释放路径不再因不可解析的样板槽取消健康任务：新增 `pendingInputOrUnknown(...)` 包装 `potentialInput`，捕获 `RuntimeException` 并返回 `Long.MAX_VALUE`（"未知即继续持有"）。`GrowthCpuMixin.aecraftroute$releaseFeedback` 与 `AdvancedGrowthCpuMixin` 对应方法改用它；理由是无法枚举候选槽的第三方 `IPatternDetails` 并不能证明存档损坏，静默取消会每秒杀死一个正常 job。
- 未延迟协议的 insert 预判路径同样容错：`task.getKey().getInputs()` 为 null、或槽为 null 时跳过该槽，而不是抛异常穿出我们的 `@WrapMethod` 进入 provider 的 `insert`。这是与 deferred 路径 `GrowthCpuMixin.java:88-89` 既有的 null 容忍保持一致。
- 解析器把 `job` 检查从无类型 `tag.contains("job")` 收紧为 `tag.contains("job", Tag.TAG_COMPOUND)`：原生 `readFromNBT` 只认 compound，字符串型 job 会让校验通过但原生随后丢弃 job。
- 补充可观测性：两类 CPU 的 `readFromNBT` TAIL 在 `saved.deferred() && job==null`（原生因 finalOutput 不可读而 `finishJob(false)` 并清空 job）时记录 `Saved deferred feedback discarded: native (AdvancedAE) CPU job did not load (held=...)`。库存本身由原生 `storeItems` 归还 ME，丢的只是诊断/取消路径，现在不再无声。
- 新增单测：`pendingInputOrUnknown` 对坏槽返回 MAX_VALUE 且 `offerRelease` 仍为 `Release(0,0)`；`requireSaneRuns` 接受 3、拒绝 `Long.MAX_VALUE`、拒绝负数、拒绝超过 `MAX_TASK_RUNS`、恰好等于上限仍可加载、空表通过；`LegacyFeedbackSaveTest` 新增字符串型 `job` 拒绝。
- 定向 `test --tests 'com.aecraftroute.addon.vm.*'` 通过；完整离线门禁 `test routeFunctionalTest build -x productionBenchmark` BUILD SUCCESSFUL，两个测试任务各 **594** tests、0 failures/errors/skipped（594=591+本次3项新单测），`git diff --check` 通过。真实隔离 NeoForge 服务端回归矩阵全部 PASS（`build/audit-fb8-<task>.log`）：`legacy-feedback`46、`legacy-feedback-control`45、`legacy-feedback-reload`50、`legacy-feedback-corrupt`15、`legacy-feedback-marker-corrupt`15、`legacy-feedback-heldtype-corrupt`15、`legacy-feedback-oldkeys`50、`legacy-feedback-seed-rejectonce`47、`legacy-feedback-advanced`46、`legacy-feedback-advanced-reload`47、`legacy-feedback-advanced-marker-corrupt`15、`legacy-feedback-advanced-heldtype-corrupt`15、`legacy-feedback-advanced-corrupt`15、`legacy-feedback-advanced-oldkeys`47、`legacy-feedback-advanced-seed-rejectonce`47、`foreign-advanced`16。这些真实进程回执证明排队/释放路径在硬化后仍能正常派发并交付，也证明合法 oldkeys/reload 存档未被新守卫误杀。
- 保留判断（记录为有意行为）：写入端在空闲时也写出 `aecraftrouteDeferredHeld=0`，因此严格孤立守卫会拒绝"marker 位丢失 + held!=0"的存档；这是 fail-closed 选择，宁可取消退款也不恢复来源不明的信用。
## 2026-10-27：画布请求数量在服务端按规划上限拒绝

- 缺陷（只读审计发现）：`src/main/java/com/aecraftroute/addon/route/RouteNetwork.java` 的 `planAsync` 只检查 `amount<=0`（`目标数量必须大于零`），直接采信 argument JSON 的 `getAsLong()`；而客户端自己的数量编辑已经在 `RouteQuantityField`/`RouteGraphMath.clampDemand` 里夹到 `[1, MAX_DEMAND]`。改过的客户端可以送任意正 `long`，把失败推到画布规划器深层的 `Math.multiplyExact`/`Math.addExact`，只能以通用 `规划失败：…` 或 `数量超出整数范围` 呈现。这不构成绕过（未证明的计划不会提交），但把一处确定的上限校验变成了不可预期的异常类型。
- 修复：`src/main/java/com/aecraftroute/addon/route/RouteRequestRules.java` 新增 `public static long requirePlanAmount(long amount)`，`amount<=0` 抛 `目标数量必须大于零`，`amount>RouteGraphMath.MAX_DEMAND`（=1_000_000_000_000）抛 `目标数量超过上限 …；未规划`；`planAsync` 改用它替换原来的内联检查。上限复用 `MAX_DEMAND` 而不是新造常量，因为它存在的理由正是让 inputNeed 远离溢出。
- 行为边界：只拒绝、不静默夹取。夹取会把玩家的超大需求悄悄改成 1e12 再照做，与画布上所示数量不一致；拒绝则与客户端 `parseDemand`（只接受 `[0-9]{1,15}` 再夹取）语义一致。`apply` 路径本来就要求请求目标/数量与合成确认页一致（`RouteNetwork.java:196`），因此正常 UI 流程不会碰到该上限。
- 单测：`src/test/java/com/aecraftroute/addon/route/RouteRequestRulesTest.java` 新增 `requestAmountsOutsideThePlanRangeAreRefusedBeforePlanning`：1 与 `MAX_DEMAND` 原样返回，0/-1/`MAX_DEMAND+1`/`Long.MAX_VALUE` 一律 `IllegalArgumentException`，且超大值消息含 `目标数量超过上限`。
- 证据：完整离线门禁 `test routeFunctionalTest build -x productionBenchmark` BUILD SUCCESSFUL，两个测试任务各 **595** tests、0 failures/errors/skipped（595=594+本次1项）；日志 `build/audit-gate-amount.log`。真实隔离服务端画布场景回归 PASS：`vanilla-canvas`17 ticks、`vanilla-canvas-advanced`20、`paths`107（`build/audit-amount-<task>.log`），证明合法请求仍能编码、规划并交付路径分支，新守卫只拦越界值。未操作客户端、未安装玩家实例。

## 2026-10-27：画布报文严格解析

- 缺陷（只读审计）：`src/main/java/com/aecraftroute/addon/route/RouteNetwork.java` 的 `planAsync` 原用 `GSON.fromJson(request.argument(),JsonObject.class)` 后直接 `e.get("k").getAsString()`。改过的客户端送来非字符串的 `k/c/n/p`、非对象的分支项或非数字 `amount` 时，Gson 抛出的异常被外层 `catch(Exception)` 转成泛化的「操作失败：…」，而不是明确的请求拒绝。不构成绕过（只有 `if(apply && view.proven())` 才提交），是输入契约与错误分类问题。
- 修复：`src/main/java/com/aecraftroute/addon/route/RouteCanvasPayload.java` 新增 `record Decoded(long amount,List<RouteApply.Node> nodes)` 与 `static Decoded decode(String)`。空/空白 → 「画布数据为空」；非 JSON → 「画布数据不是有效 JSON」；非对象 → 「画布数据不是 JSON 对象」；`amount` 非 JSON 数字 → 「画布数量不是数字」；`nodes` 非数组 → 「画布分支不是 JSON 列表」；分支项非对象 → 「画布分支第 N 项不是 JSON 对象」；`k/c/n/p` 存在但非字符串 → 「画布分支第 N 项的 k 不是文本」。缺失字段保持 null 交给 `RouteApply.prepare` 拒绝，未知键忽略，不夹取也不放宽任何约束。
- 接入：`planAsync` 改为 `var decoded=RouteCanvasPayload.decode(request.argument())`，随后仍用 `RouteRequestRules.requirePlanAmount(amount)` 校验数量。
- 单测：`src/test/java/com/aecraftroute/addon/route/RouteCanvasPayloadTest.java` 新增三项——正常报文解析 + 128 节点无名称形式往返；7 种畸形消息逐条断言（含 `k` 为数字）；缺失字段保持 null 且 `prepare` 仍报问题。
- 证据：完整离线门禁 BUILD SUCCESSFUL，两组各 **598** tests、0 failures/errors/skipped；真实隔离服务端 `vanilla-canvas` 22 ticks、`vanilla-canvas-advanced` 22、`paths` 147 全部 PASS（`build/audit-decode-<task>.log`）。未操作客户端、未安装玩家实例。

## 2026-10-27：certified 派发按原生模糊模板取料

- 缺陷（只读审计 M-2，反汇编核实）：`appeng.crafting.execution.CraftingCpuHelper.extractPatternInputs` 对每个槽调用 `getValidItemTemplates(inv,input,level)`——把 `input.getPossibleInputs()` 的每个 `GenericStack` 交给 `inv.findFuzzyTemplates(what)` 展开，再用 `input.isValid(key,level)` 过滤——然后逐候选 `extractTemplates` 先 SIMULATE 再 MODULATE。原 `ProductionJob.extract` 只按计划里编码的精确 key 取料，因此当样板槽可接受等价但非同一 key 的库存（`canSubstitute`、仅组件不同的模糊族）时，certified 任务会永久等料，只剩 30 秒的 `aecraftroute$watchStall` 警告。
- 修复：`src/main/java/com/aecraftroute/addon/planner/ProductionJob.java` 的 `extract` 改为逐 portion 调新的 `takePortion(...)`：先用 `fill(...)` 取精确 key（保证计划自身的 key 记账精确），不足部分再遍历 `inventory.findFuzzyTemplates(template.what())`，跳过 null、与精确 key 相同者以及 `input.isValid` 不接受者，逐候选 `fill`。`fill(...)` 用 `Math.multiplyExact` 换算整份数，SIMULATE 求可倍数、MODULATE 扣料，`slot.add(candidate,got)`，并针对**实际扣下的候选 key** 记 `input.getRemainingKey(candidate)` 的容器回收。整个 `extract` 的扣料循环仍在既有 `try/catch(RuntimeException|Error)` 内，任何异常都会 `reinjectPatternInputs` 并取消任务而非把已扣物料丢在 AE2 里。
- 行为边界：仍只扣计划允许的槽与倍数；不足时整体回滚并返回 null（视作等料而非存档损坏），不回退到「任意等价物品都算数」。模糊族仅用于实际存在的库存，不改变证明侧的材料需求。
- 单测：`src/test/java/com/aecraftroute/addon/planner/ProductionExecutionTest.java` 新增 `fuzzyInventory(...)` 夹具与三项——只有等价库存时成功扣该 key 并记在它名下、精确 key 与等价库存同时在时优先扣精确 key、两槽只够一槽时返回 null 且库存全数回滚且任务未被标记损坏。
- 证据：完整离线门禁 `test routeFunctionalTest build -x productionBenchmark` BUILD SUCCESSFUL，两组各 **601** tests、0 failures/errors/skipped（601=598+本次3项）；`build/audit-fuzzy*.log`。真实隔离服务端回归 PASS：`growth` 118 ticks、`paths` 106、`vanilla-canvas` 18（`build/audit-fuzzy-<task>.log`）。未操作客户端、未安装玩家实例；第三方 requester 仍走原生规划与自有执行。

## 2026-10-27：certified 任务身份恢复为样板自带相等语义（不放宽为结构比较）

- 触发点：审计中曾把 `ProductionJob.validateRemainingTasks` 与 `allows` 的样板身份从 `IPatternDetails.equals` 放宽成结构比较（同类名 + 相同 outputs + 每槽 multiplier 与 possibleInputs 逐一相同）。该放宽已回退：容器返还、替换规则与供应器行为都不在可见字段里，结构相同不等于派发相同，放宽属于错误放行。
- 现状（`src/main/java/com/aecraftroute/addon/planner/ProductionJob.java`）：`sameTasks` 只做 `certified.equals(actual)`，`samePattern` 只做 `Objects.equals`；不一致时置 `invalid` 并打 `Native tasks disagree with production cursor ... cancel and resubmit. No inventory mutation.`。
- 结论：M-1（第三方 `IPatternDetails` 未实现 `equals`，重载后对象身份必然不同）按 fail-closed 接受，不修。理由：这类样板本来也无法派发（`allows` 会拒绝，供应器只会重试），而重载时会先取消任务——库存在取消路径上不动、材料回到 ME，只需重新提交；换成结构比较则是用「可能的错误放行」换「少一次取消」，得不偿失。
- 诊断改进：不一致时的错误日志带 `certified=N actual=M` 以及最多 4 条 `[want 类名x数量]` / `[have 类名x数量]`，能直接看出是哪一侧样板不同，而不是只留一句笼统的取消提示。
- 证据：`gradle --offline test --tests com.aecraftroute.addon.planner.*` BUILD SUCCESSFUL；完整离线门禁 `test routeFunctionalTest build -x productionBenchmark` BUILD SUCCESSFUL（`build/audit-gate-identity-safe.log`）；真实隔离 NeoForge 服务端 `reload` 取得 fresh 回执 `RESULT=PASS task=reload ticks=168`（`build/audit-identity-reload.log`）。
- 未新增单测：既有 `ProductionPersistenceTest.completedCursorCannotHideUndispatchedNativeTasks` 与 `TerminalDeliveryTest.noDeliveryBeforeEveryCertifiedStepHasDispatchedEvenWithPlentyOfStock` 已覆盖「原生任务与本模组游标不一致即取消」这条路径；本次改动只是把判定恢复成样板自身语义。
### 2026-10-27 补充：当前树的重启与 scaled 变体真实服务端回归

- 顺序要求（`build.gradle:160-166`）：除 `restart2`/`advanced-restart2`/`legacy-feedback-*-restart2` 外，每个自检任务都会重建测试世界；`restart2` 是 `restart1` 的后半段，必须紧接其后运行。单独跑 `restart2` 会以 `ME interface job ran on wrong CPU family: net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU` 正确 FAIL——那是世界状态缺失，不是产品缺陷。另注意白名单里没有 `advanced-reload` 这个任务名（真实存在的是 `legacy-feedback-advanced-reload` 与 `advanced-restart1/2`），拼错会在 Minecraft 启动前被 `Unknown selftest task` 拒绝。
- fresh 回执（本次在含「certified 模糊取料 + 样板自带相等语义」的当前树上取得，日志 `build/audit-r15b-*.log`、`build/audit-r15c-*.log`）：`advanced-restart1` PASS 24 ticks → `advanced-restart2` PASS 110 ticks；`restart1` PASS 24 ticks → `restart2` PASS 110 ticks；`scaled-processing-execution` PASS 55 ticks、`scaled-processing-plain-execution` PASS 55 ticks、`scaled-advanced` PASS 19 ticks（后三者带 `-PextendedAePlusSelftest` 加载安装版 ExtendedAE Plus）。
- 覆盖含义：跨进程重启后 certified 程序仍能按原生任务表校验并继续交付（两种 CPU 家族各一对），以及 ExtendedAE Plus 的普通/高级 scaled 包装器在真实执行链上交付正常。仍不覆盖 A1 真实 ATM10 最大画布、A2 其余尺寸/并发组合、A5 整包容量与全部客户端视觉交互。
- 回归锁（2026-10-27）：新增 `src/test/java/com/aecraftroute/addon/planner/PatternIdentityTest.java`（4 项）把上面这条判定钉死：同实例、值相等的两份解码视为同一张样板；**可见输出与槽位候选完全相同、只有容器返还不同**的两张样板必须判为不同（若哪天又把身份放宽成结构比较，这条会先失败）；同类的第三方样板若无 `equals` 则一律 fail-closed。
- 计数：本次新增 4 项单测，完整离线门禁 `test routeFunctionalTest build -x productionBenchmark` 两组各 **605** tests、0 failures/errors/skipped（605=601+4），日志 `build/audit-gate-identity-test.log`。

### 2026-10-27：M-3 增长环启发式落下可诊断痕迹

- 问题（审计 M-3）：`src/main/java/com/aecraftroute/addon/vm/TwoNodeGrowthPlanner.java` 的 `containsRing` 只是一个布尔，它直接决定是否建立延迟反馈账本、是否重排派发顺序。多环/歧义（`find` 遇到第二个候选环返回 null）、回收线带副产物、回收器产量不匹配、计数溢出等情形会静默退回原生 HashMap 迭代顺序，日志里没有任何「本该识别却没识别」的线索，饿死现场只能靠猜。
- 改动（行为不变，仅可观测性）：`find` 遇到第二个候选环时 `note("ambiguous two-node rings; leaving dispatch order to the native engine")`；`containsRing` 的 `ArithmeticException` 分支 `note("pattern quantities overflow while detecting a feedback ring")`；`note` 只在 `CraftRouteConfig.isDebugLogging()` 时以 info 打 `[AE-Craftroute] Feedback ring not modelled: …`。判定结果、重排行为、延迟反馈账本的建立条件都没有改。
- 单测：`src/test/java/com/aecraftroute/addon/vm/TwoNodeGrowthPlannerTest.java` 新增 `ambiguousRingsAreDeclinedInsteadOfGuessed`——同一产线的两个回收器各自单独成环都为真，但三者同时在场时必须判 false，且 `prioritizeFeedback` 返回的键顺序保持不变（策略仍是「不猜」，只是现在能被看到）。注：该方法在无环时仍返回新 map（与溢出分支返回原 map 不同），锁的是顺序而不是实例。
- 证据：`gradle --offline test --tests com.aecraftroute.addon.vm.*` BUILD SUCCESSFUL（`build/audit-m3b.log`）；完整离线门禁 `test routeFunctionalTest build -x productionBenchmark` BUILD SUCCESSFUL，两组各 **606** tests、0 failures/errors/skipped（606=605+1，`build/audit-gate-m3.log`）；真实隔离服务端回归 PASS：`growth` 117 ticks、`legacy-feedback` 46、`advanced` 117（`build/audit-r16-<task>.log`）。
- 仍未覆盖：真正的多环建模（把 N 环都排对顺序）仍不在本模组范围内，歧义时依旧交给原生引擎；A1/A2/A5 与客户端交互验收同样仍开放。

### 2026-10-27 补充：606 门禁树的收尾真实服务端矩阵

- 在含「certified 模糊取料 + 样板自带相等语义 + 增长环可诊断」的最终树上补跑（日志 `build/audit-r17-<task>.log`）：`vanilla-canvas` PASS 18 ticks、`vanilla-canvas-advanced` PASS 19、`paths` PASS 144、`legacy-feedback-advanced` PASS 46、`legacy-feedback-reload` PASS 50、`planning-load` PASS 30、`cancel` PASS 69、`legacy-feedback-corrupt` PASS 15、`legacy-feedback-oldkeys` PASS 50。
- 负对照：`gate-failure` 按设计以 `RESULT=FAIL task=gate-failure ticks=0 failures=[intentional negative control for the Gradle selftest gate]` 收场并让 Gradle 退出码为 1 —— 这条证明验收门禁只认 fresh 的精确 `PASS task=<task>` 回执，不会把「跑了但失败」当成通过。不要把它当失败信号。
- 组合起来本轮已覆盖：画布（普通/高级/长路径）、D15 反馈元数据（合法/损坏/旧键/重载）、规划队列背压（planning-load）、取消、以及两种 CPU 家族的重启对与三个 scaled 执行变体（见上一节）。仍未覆盖：A1 真实 ATM10 最大画布、A2 其余尺寸与并发组合、A5 整包容量、以及全部客户端视觉/交互验收（后者需用户授权）。

### 2026-10-27 L 系列文档化 + A1 真实规模静态采样（本轮批次）

- **L1–L14 管线文档**：新增 `docs/canvas-pipeline-l-series.md`（219 行，仅新建该文件）。14 条全部按当前未提交树逐行实读记录，每条含现行 file:line、职责、校验/守卫与 progress/coverage 证据指针；L14 标注为不存在并贴 `RouteView.java:51-52` Reply 全签名为证。历史 `canvas-planner-executor-audit-local59.md` 的行号全部过时（如 L3 60-110→51-173、L10 127-186→120-180、L11 21-92→22-139），文末附行号漂移表。L1/L2 客户端部分全部标未验收/需授权。抽查复核：L14 与 D21（`RouteNetwork.java:124` 条件 put）两处行号与源码一致。
- **A1 静态采样（只读，不改文件、不跑 Gradle/客户端）**：全实例扫描 mods 557/557 jar + kubejs + 两存档 datapack，内存解析零落盘、两轮交叉核对：**85,703** 个配方 JSON（jar 85,574 + kubejs 129；55 个不可读 jar=0.06%；存档 datapack 0 配方），约 53.5k 不同产物。扇出：≥2 配方 9,442、≥10 324、≥16 126、≥32 49；最大 `productivebees:spawn_egg_configurable_bee`=210。配料槽最多 84（chipped:workbench）；144 个配方有多选槽（最大 24）；3,904 个配方含 tag 配料（运行期才展开）。依赖闭包：250 个终局候选中 **62 个闭包 >128**（`extendedae_plus:infinity_core`=1,525、`advanced_ae:quantum_multi_threader`=1,404、`ae2:engineering_processor`=1,362）；最宽配方 BFS 在深度 8–12 跨过 128 节点，均远低于 `MAX_PATH_DEPTH=24`。按 `RouteCanvasPayload.encode` 实际形状模拟真实 128 节点前缀的报文：**17,135–21,562 字符，全部 ≤24,000** ⇒ 实测中 `MAX_NODES=128` 先于 `MAX_CHARACTERS=24000` 成为约束（128 节点无标签时余量约 6.4k，标签均长约 50 字符内不越界）。局限：14.7% 配方无可静态解析输入、KubeJS 循环与 tag 展开使真实规模 ≥ 静态测量；BFS 是代理而非规划器真实展开序；未做任何运行期观测。
- **建议的下一批 A1 自测**（尚未实施）：新自测任务 `canvas-scale`——需先加进 `build.gradle:133` 白名单（回执逻辑无需改，`SelftestHarness.java:2041` 写 `PASS task=<name>`）；harness 生成 9 宽×4 层=128 节点真实形状 datapack，跑 encode→RouteApply.apply→planAsync→真实 CPU，要求 `locked=128`、`proven`、`chars≤24000`，并加 129 节点必须原样拒绝「强制画布有 129 个分支，最多 128」的负例。风险：样板提供器槽位容量、128 节点下 `Limits(6000,500_000_000L)` 余量未知、深树 tick 预算。
- **本轮门禁**：`test routeFunctionalTest build -x productionBenchmark` BUILD SUCCESSFUL，两组各 **606** tests、0 failures/errors（`build/audit-gate-lseries.log`）。真实服务端自检 `vanilla-canvas` PASS ticks=17、delivered=1（`build/audit-selftest-lseries.log`）。
- **环境教训**：本会话沙箱下 `gradlew` 不可用（wrapper jar 缺失 → ClassNotFoundException: org.gradle.wrapper.GradleWrapperMain），直接调缓存 gradle 8.8 会因沙箱拦 `~/.gradle/native/native-platform.dll` 报 `Could not initialize native services`；可行命令为 danger-full-access 下 `gradle -Porg.gradle.java.installations.paths=C:/Users/xun/.gradle/jdks/jdk21/jdk-21.0.12.1+1 test routeFunctionalTest build -x productionBenchmark`（JDK21 在 `~/.gradle/jdks/jdk21/jdk-21.0.12.1+1`，foojay 下载不可用）。
- **仍未覆盖**：A1 的运行期真实画布分支数与 >128 的真实客户端行为、A2 其余尺寸/并发组合、A5 整包容量、全部客户端视觉交互验收（需用户授权）。

### 2026-10-27 补充：606 门禁树的整包服务端自检横扫（第一批）

- 目的：把「这一版最终树」在各条已在白名单里的场景上过一遍，而不是只信上一次改动时的抽样。日志 `build/audit-r18-<task>.log`，全部 fresh `RESULT=PASS`：
- 兼容/第三方边界：`foreign` 16 ticks、`foreign-advanced-plus` 66（带 `-PextendedAePlusSelftest`）、`fallback` 23、`fallback-small` 19、`fallback-busy` 29 —— 未注册 requester 仍走原生链、只在已认证计划上做 CPU 回退。
- 交付与竞态：`partial` 118、`overestimate` 118、`advanced-overestimate` 119、`stock-race` 122、`advanced-stock-race` 123、`rejectonce` 118 —— 部分交付、产量高估、库存抢占与「拒一次再接受」都按真实机器行为收尾。
- scaled 家族补齐：`scaled-native-plan` 5、`scaled-strict-roundtrip` 3、`scaled-molecular-execution` 13（均带 `-PextendedAePlusSelftest`）—— 连同此前 `scaled-advanced` 19、`scaled-processing-execution` 55、`scaled-processing-plain-execution` 55，白名单里七个 scaled 场景在最终树全绿。
- 说明：这些是行为回归证据，不等于 A1（真实 ATM10 最大画布）、A2（其余尺寸/并发组合）或 A5（整包容量）已验收；客户端视觉交互仍未触碰。
### 2026-10-27 发布 local.60（提交 + Release + 本地实装）

- 版本：`gradle.properties` mod_version 1.13.19-local.59-dev → **1.13.19-local.60-dev**；构建 `build/libs/aecraftroute-1.13.19-local.60-dev_neoforge_1.21.1.jar`（634,105 B）+ sources（318,591 B）+ `SHA256SUMS-local60.txt`。
- 门禁复跑：`build`（含 test + routeFunctionalTest）两套各 606 tests / 0 failures / 0 errors。
- 提交：`0c73d3f`（22 files, +978/-68）推到 `wip/planner-vm-ui-2026-10-25`，tag `v1.13.19-local.60-dev` 已推。**个人文档 `docs/codex context.md` 保持未跟踪，未入仓库。**
- Release：<https://github.com/Hsunxl/AE-Craftroute/releases/tag/v1.13.19-local.60-dev>（Pre-release，3 附件齐全，中文正文含原因/加固/验证/限制）。首次 `gh release create` 遇 graphql EOF，重试成功（无重复发布）。
- 本地实装：`D:/pcl/versions/ATM10/mods/` 已删除 local.59 jar、放入 local.60 jar，sha256 与发布附件一致（`0bd5473d…af274`）。


### 2026-10-28 local.61 实装及 A1/A2 补充验证

- 合成回路复现：32 上游分支、13 步增长环，旧实现目标净增 1000 返回 LIMIT；重复窗口限制从 8 扩展至 128，新增独立算术回放测试覆盖 1000 至 10^16。local.61 通过完整门禁后安装，旧 local.60 留存于 build/install-backups。安装文件 SHA-256 `97e22f3f61067f9f9b1296707e7266d45db680f87571daca9bbac96df68ea8c9`。真实玩家网络未采样，不能认定实际恩特罗水晶的根因已确定。
- A1 新增 128 个独立样板、二叉分支的画布规划模型测试，净增 1 / 1000。修改前返回 LIMIT；原因是无标记物理配方先于带路径标记的配方，DAG 选择无法同时利用一次执行满足物料和分支义务，构造快捷方案失败。标记配方优先、普通配方仍保留，测试通过；保留所有分支计数及物理程序的独立证明。此第二项修复仅在工作树，未装入供用户测试的 local.61。此测试不是 128 节点真实服务端或整包网络采样，A1 运行期缺口仍未关闭。
- A2/A4 三模组组合：实际安装版 ExtendedAE Plus + AdvancedAE + NeoECO 共同加载，`scaled-advanced` PASS ticks=19、`scaled-processing-execution` PASS ticks=63、`scaled-molecular-execution` PASS ticks=20，日志 `build/audit-local61-neoeco-scaled-*.log`。仅验证这些特定场景，不代表其它尺寸、持久化、完整 ATM10 网络通过。
- A1 标记配方优先修改后的全量门禁：`test` / `routeFunctionalTest` 各 608 项、0 失败；独立真实服务端 `paths` 113 ticks、`vanilla-canvas` 18 ticks、`growth` 118 ticks 全部 PASS，日志 `build/audit-wide-canvas-{gate,paths,vanilla-canvas,growth}.log`。这些小网络自检不证明 128 节点真实 CPU 已验证。


### 2026-10-28 配方树与执行样板解耦（进行中）
- 用户明确要求画布按配方而非网络样板展开，后续由执行层/供应器选样板。已撤掉基于网络样板的一键展开尝试，工具栏“展开”现以 JEI 产物查询构建草稿树，每 tick 最多处理两个节点；不以库存、网络锁定或已应用样板决定 JEI 树，不把不存在编码样板当成原料。使用首个可转换 JEI 配方，回环、深度和 512 节点限制有提示。
- 尚未完成执行时绑定：现有 RouteCanvasPlanner / ForcedRoutePlanner 依赖已冻结 IPatternDetails 和精确 PatternVariant，ProductionJob 在扣料与 dispatch 时按样板身份校验。JEI Draft 只记录当前显示的材料/产物，未完整表达替代输入、返回物、概率和机器语义，不能直接伪装为已认证样板。JEI 树目前禁止“应用”，没有改变现有执行保证；需要后续实现可信配方契约、执行绑定与一致性校验，并覆盖缺样板、歧义、返还物和跨重启等情况。
- 全量门禁两组各 608 项通过，独立服务端 paths PASS ticks=109（`build/audit-jei-tree-gate.log`、`build/audit-jei-tree-paths.log`）；尚无一键展开 UI 验收，未实装本批改动。

### 2026-10-28 local.62 实装及后续候选映射（未实装）
- `57b7ad3` 已本地提交，local.62 构建门禁通过并实装；SHA-256 `e4a6db8328ae4f784ab90811621762f77f3d40191778af2536398b3d88fa4216`，旧 local.61 备份于 `build/install-backups`。GitHub push 被自动审批拦截，不能绕过；等待用户明确确认远端/载荷。
- 后续工作树取消画布显示固定 512 节点及 apply 的 128 节点检查；24,000 字符协议限制、深度 24、快照和搜索预算仍在，因此不是无限大整包执行。新增 129 分支编码往返测试。
- 新增 `RecipePatternMapping` 候选发现：按配方路径遍历快照中该产物的所有样板，枚举精确输入分配并对照输入输出数量，保留多候选及枚举不完整状态；缓存相同物理样板的枚举结果。候选不是等价证明，不会自动选择、发送或绑定实际供应器，JEI 树执行/同步尚未接入。返还物、歧义、超限和重复路径有回归测试。
- 后续工作树全量门禁两套各 613 项通过；独立服务端 paths PASS ticks=109。后续工作树与已安装 local.62 不同，未覆盖安装版，未经 UI 与整包运行验收。

### 2026-10-28 路径限制与映射证明推进
- 用户确认后，`57b7ad3` 已推送 `origin/wip/planner-vm-ui-2026-10-25`。
- 工作树移除画布显式节点数、24 层深度及单路径段 999 输入索引限制；路径检查改迭代，2,000 层验证、48 层规划及 129 节点编码测试通过。传输仍限 24,000 字符，快照/变体/规划预算和 Java 内存栈仍存在；真实超大画布 UI 未验证，不能称所有容量限制已消除。
- `RecipePatternMapping.prove` 将候选集纳入 `ForcedRoutePlanner` 生成和回放物理证明，缺失候选和枚举不完整时拒绝伪造证明。只接受调用者已验证的配方语义和路径批次；JEI 画布、服务端协议与运行时供应器还没有生产接线，当前仍禁止应用 JEI 树，未改变已安装 local.62。
- 全量门禁 `build/audit-unbounded-mapping-final2.log` 通过；独立服务端 `paths` PASS ticks=110（`build/audit-unbounded-mapping-paths.log`），不代表新的映射 API 已走真实 CPU。

### 2026-10-28 画布分片与容量边界（进行中）
- 工作树将大画布拆成按序分片，客户端每 tick 限量发送，服务器按玩家、菜单、目标及序号重组，缺片/乱序/超时不应用；传输完成后进入原有服务端证明和原子应用。超大路径回显仅省略响应中的可选画布副本，不删服务端约束。分片测试覆盖 1,000 节点和 Unicode、编解码、错序、跨会话与过期；尚未经过真实客户端到服务端的超大画布实测。
- 移除快照 512 材料/2048 样板与画布变体总数 8192 的硬编码检查，保留端到端时间、状态和变体完整性限制。网络样板查询从一生最多 48 个改为最多 8 个并发，回包后继续。规划计算仍有预算，画布布局仍递归，不可宣称无限容量。
- 实际 JEI 配方树到服务端可信配方契约、映射证明、实时供应器绑定仍未生产接入；现有 `RecipePatternMapping.prove` 只在可信调用者提供批次及配方契约时可使用。用户要求的端到端执行尚未完成，不把计数相同冒充机器/概率/返还语义相同。
- 曾尝试放宽 10^12 目标数量上限；执行审批认为原请求不足以授权去除数量安全限制，因超大请求可能导致资源耗尽而拒绝。已撤回该改动，保留现有上限；需用户明确批准数量上限调整。全量测试两组各 618 项通过，`paths` 独立服务端自检 PASS，未实装此工作树。

### 2026-10-28 大数量预算与 mapped-growth 自检
- 用户进一步要求超级大数量级并提高预算：画布目标上限由 10^12 提至 10^16，字段可输入 17 位；真实合成计算和画布 apply 改用 150,000 状态、30 秒异步端到端预算，preview 为 30,000 状态/8 秒，apply 客户端等待 45 秒。同步入口不自动扩大预算；保留取消、tick 切片和算术溢出检查。
- 正常网络规划移除 512 材料、2048 样板和总变体 8192 的固定上限；单样板替代枚举仍有完整性限制，计算时间、状态数、长整数和实际资源仍是边界。`LargeBranchCycleRegressionTest` 及 `RecipePatternMappingTest` 覆盖 10^16 计划，不代表该数量已由真实 CPU 物理交付。
- 候选映射可生成 `CertifiedCraftingPlan`。新增独立服务端 `mapped-growth`：从真实网络的三张循环样板构造测试契约，经过映射/证明，提交真实 AE2 CPU，交付 3 个钻石、CPU 释放，PASS ticks=216（`build/audit-mapped-growth.log`）。此测试契约来自样板本身，不验证 JEI 草稿、网络分片、生产请求接线或客户端交互；JEI 树仍禁止应用，真实执行链尚未完成。
- 两组完整门禁各 620 项通过（`build/audit-large-budget-mapped-final.log`），常规真实服务端 growth/paths 均 PASS。更大预算尚未实装或推送；已安装的仍是 local.62。

### 2026-10-28 工作台 JEI 配方验证和执行映射（部分类型）
- 工作区接通 JEI 草稿的画布 payload、服务端普通工作台配方重建/材料及产物核验、可信定义绑定、快照映射、路径需求推导、认证程序及 CPU 执行。仅接受与服务端重建定义完全一致的编码样板；不支持混合旧样板锁定、加工/概率/特殊配方。客户端可回显已应用的草稿；处理多个完全相同的路径时在单次请求内缓存服务端验证结果。
- 独立服务端 `mapped-canvas` 通过：序列化/反序列化 3 条 JEI 草稿、FakePlayer 服务端校验、篡改输入和缺失样板负例拒绝、真实网格快照、异步规划、真实 AE2 CPU 交付 3 个钻石，PASS ticks=442；`build/audit-mapped-r2-selftest.log`。这是隔离服务器上的直接 payload/prepare 调用，不是实际客户端网络分片或 GUI 实测。模板定义位置、替代材料、加工机器及大规模服务端同步校验仍需验证。完整两组门禁各 621 项通过，日志 `build/audit-mapped-r2-gate.log`。
- 短时纯规划基准（预热 3、样本 10）`build/reports/productionBenchmark/results.csv`：chain-256 1000 未缓存 p50 18.5245ms，缓存 p50 1.1410ms；2 环增量 10^9 未缓存 p50 0.5041ms，缓存 p50 0.0179ms。只反映合成模型，不含真实整包网格采集、客户端和设备派工。
- 当前工作区尚未推送/实装；当前安装仍是 local.62。本节覆盖此前“所有 JEI 树禁止应用”的历史描述，但不声称全 JEI 类别或超大画布端到端已完成。

### 2026-10-28 local.63 构建和本地安装
- 版本 `1.13.19-local.63-dev`；两套门禁各 621 tests、0 失败（`build/audit-local63-gate.log`）；隔离服务端 `mapped-canvas` PASS ticks=441、`growth` PASS ticks=118（`build/audit-local63-{mapped-canvas,growth}.log`）。
- 安装 jar 的 SHA-256 为 `23438d0031573b5124c7e2e7ff5ee0341a3eb4ba0c51ac5101675764002c616b`；local.62 已移出 mods 并备份在 `build/install-backups`。普通工作台受验证的 JEI 配方可走映射，处理机/概率配方、超大分片实网与客户端交互不在此次通过范围。
