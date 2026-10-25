# AE-Craftroute — AE2 合成计划扩展

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-blue?logo=minecraft)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.169+-orange)
![AE2](https://img.shields.io/badge/AE2-19.2.18+-green)
![Java](https://img.shields.io/badge/Java-21-red)
![License](https://img.shields.io/badge/License-LGPL%20v3-blue)

> **English version**: [README_en.md](README_en.md)

AE-Craftroute 是 [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2) 的 NeoForge 扩展。它替换 AE2 的合成可行性计算：合成请求的判定改由一次搜索完成，搜索结果同时给出所需的中间产物与它们的执行顺序，并以可执行程序的形式交给合成 CPU。产物为 `build/libs/` 下的单个 jar 文件。

该扩展的全部计算由本模组完成，不修改 AE2 的样板定义与库存结构。与 AE2 的交互通过公开接口和 Mixin 注入实现，注入目标列于 [ATTRIBUTIONS.md](ATTRIBUTIONS.md)。

---

## 一、递归展开不适用的三类配方

AE2 判断一次合成请求能否完成，依据对样板树的递归展开：从目标开始，逐层把每种材料替换成产出它的样板，直到所有叶子都由库存满足。该判据在多数配方上与执行过程一致，在以下三类配方结构上与执行过程不一致。

**循环配方。** 循环配方由互为输入的样板构成，例如 `A → B` 与 `B → 2A`。这两张样板各自消耗对方的产物，递归展开因此没有自然终止的叶子：展开过程要么持续进行直至栈溢出或触发深度上限，要么在第一个环处判定为不可合成。此类配方在具有任意一种参与物作为起手资本时可以持续运转并产出净增量，其可执行性取决于起手资本与环的净产出，而非展开过程能否终止。

**净增量。** 当一张样板的产物同时是该样板或其他样板的输入时，真实需求是净增量。递归展开把每一层级计为一次新的消耗，同一份材料因此在需求统计中被多次计入，得到的需求量高于实际执行所需的量。

**顺序依赖。** 回料在装配器之间的流转具有先后关系：前一步的产物须先进入缓冲，才能作为后一步的输入。递归展开的产物是一份材料清单，其中不含顺序信息。材料清单成立与计划可执行是两项不同的条件，前者成立不蕴含后者成立。

这三类结构在游戏内产生两类报告：可执行的配方被报告为缺少材料；或计划被报告为可行而 CPU 的进度停留在零。两类报告的共同机制是判定依据与执行过程不一致：递归展开的判据是展开过程能否终止，执行所需的判据是这批产物能否按序产出。

---

## 二、替换后的计算流程

判定被拆为四个阶段。每个阶段的输出是下一阶段的唯一输入，阶段之间不共享可变状态。

**1. 快照。** `RouteSnapshot` 读取网络当前的样板定义与库存，形成一份不可变快照。画布预览与真实下单由同一个构造器产生快照，两条路径的输入因此相同。

**2. 搜索。** `NetworkProductionPlanner` 从目标反向推导，为每种材料分别记账三项数值：总需求、库存可覆盖的部分、补足差额的样板。循环与净增量在该记账方式下不需要独立分支：回料的消耗与再生记在同一份账上，净增量由记账方式直接给出。

**3. 重放校验。** 搜索得到候选方案后不立即返回。候选程序按顺序正向重放一遍，逐步检查每一步的输入在其执行时刻是否满足。通过全部重放的方案以 `CertifiedCraftingPlan` 返回。该对象携带起手资本 `usedItems` 与执行顺序 `program`，其 `missingItems()` 恒为空，因为重放通过即表示每一步的输入在其执行时刻都能被满足。

**4. 执行。** CPU 侧的桥接按程序顺序派单，把回料保留在 CPU 库存中供下一轮使用；直到程序走完、且目标产物自身的待回收量归零，才把最终产物交付请求方。除目标以外的待回收量（副产物、被机器消耗的催化剂、被供应商丢弃的容器）一律不等待：只要有一个回料永远不到，等待就会把 CPU 永久卡住；这些残留留在 CPU 库存中，作业结束时由原生的 `storeItems` 送回网络。程序与执行游标随 CPU 的 NBT 持久化；服务器停止后重新启动时，在途作业从存档恢复并继续执行。派单时若被选中的 CPU 报 `CPU_OFFLINE`，同一份已认证方案会再交给 AE2 自动选择一次执行者——已认证方案只描述样板与数量、不绑定机器，换执行者不改变任何已经证明的内容。

### 四种结论的判定条件

| 结论 | 判定条件 |
|------|----------|
| `feasible` | 搜索找到方案且正向重放全部通过；附起手资本清单与执行程序 |
| `UNREACHABLE` | 已证明不可行：所需材料在库存与可产出范围内均不足，或没有任何样板产出该材料 |
| `LIMIT` | 在给定预算内未完成判定，方案既未证明可行也未证明不可行 |
| `UNSUPPORTED` | 样板语义无法精确建模，方案未参与可行性判定 |

缺料提示仅由 `UNREACHABLE` 产生。`LIMIT` 与 `UNSUPPORTED` 各自返回对应的结论值，其界面文案不表述为缺少材料。

---

## 三、合成画布

除计算之外，本扩展提供合成树画布，把计算结果展开为可编辑的节点图。

- **结论来源。** 画布显示的可行性、起手清单与批次数均由服务端计划器给出，客户端不执行独立估算。目标材料变化时重新发起请求，画布结论与提交时使用的结论来自同一来源。
- **按材料锁定样板。** 可为单个材料指定使用哪一张样板。锁定以材料为单位生效，画布上的选择原样进入服务端计算，不经过客户端换算。
- **整幅原子应用。** 一次提交携带全部节点的选择，服务端逐项校验后一次性应用。应用过程不存在部分生效的中间状态。
- **冲突诊断。** 同一材料被指定两张不同样板时，服务端指出冲突的材料并要求先统一，不静默选择其中之一。被选中的样板离开网络时，同样按材料指出。

---

## 四、异步规划与预算

一次规划涉及样板图遍历、输入变体枚举与重放校验，耗时随网络规模增长。在请求线程内一次算完时，耗时峰值直接计入服务端 tick。规划因此实现为由服务端 tick 驱动的状态机。

- **读取与计算分离。** 对网格与库存的读取只在服务端线程进行；工作线程接收不可变快照，不持有网格引用。
- **预算覆盖全链路。** 队列等待、快照抓取、变体枚举与重放校验共享同一个截止时间。任一阶段耗尽预算即返回 `LIMIT`，未完成的搜索不表述为缺料。
- **提交前重新校验。** 计算完成后先对当前网络状态执行一次校验再提交。期间样板或库存发生变化时，返回计划已失效并要求重试，不提交基于旧状态的计划。
- **取消传播。** 取消请求级联到工作线程的在途任务与队列中的作业。菜单关闭、网络切换、玩家登出与服务器停止均使作业失效。

---

## 五、样板语义的建模

样板并非总是"若干输入换若干输出"。以下三类语义按同一原则处理：能够精确建模时纳入计算，否则返回 `UNSUPPORTED`。

**有限耐久工具。** 参与合成的工具在每次使用后损耗一档耐久。损耗后的物品标识、末次损坏是否返还物品、剩余耐久如何随任务持久化，均属于执行协议的一部分。当前实现依据实际消耗的耐久状态校验转移是否成立（`FiniteToolTransitions`）；无法由该协议完整描述的情形返回 `UNSUPPORTED`。把工具数量摊平为可无限摊销的计数，会得到形式上通过而执行阶段失败的计划，因此不采用该近似。

**替代输入的族级聚合。** 当一张样板的某个输入槽接受一组等价材料，且该槽的需求须在这组材料之间分配时，分配按带容量的二分匹配求解（`FuzzyFamilyAllocator`）。候选集合相同的槽合并为一个需求节点，求解后把流量分配回原始的物理槽位。合成出的族标识不被提取、不写入存档、不发送给样板供应器。

**包装样板。** 对外层包装样板（如倍率包装）仅做公开接口层面的兼容（`ScaledPatternCompat`）。一次外层派发对应一次任务，沿用外层的输入输出数量与供应器标识，内层样板不进入可执行计划。

---

## 六、安装与使用

### 安装

1. 安装 [NeoForge](https://neoforged.net/) 与 [Applied Energistics 2 19.2.18+](https://www.curseforge.com/minecraft/mc-mods/applied-energistics-2)。
2. 把 `aecraftroute-<版本>_neoforge_1.21.1.jar` 放入 `mods/` 目录。目录内只保留这一个副本；多份副本触发 modId 重复错误。
3. 启动游戏。日志中出现构建横幅即表示加载成功。

模组标识为 `aecraftroute`，包名为 `com.aecraftroute.addon`，两者在 `1.13.19-local.34` 由 `ae2vm` 改来。存档中的在途作业不受影响：读取 NBT 时若新键不存在，回退读取旧键，下次写档时按新键写入并清除旧键。第三方集成方按新的包名与类名更新引用，公开方法名不变。

### 依赖

| 依赖 | 类型 | 版本要求 |
|------|------|----------|
| Minecraft | 必需 | 1.21.1 |
| NeoForge | 必需 | ≥ 21.1.169（开发环境使用 21.1.259） |
| Applied Energistics 2 | 必需 | ≥ 19（开发环境使用 19.2.18） |
| AdvancedAE | 可选 | 1.6.12；提供量子计算机执行桥，未安装时该组 mixin 不注入 |
| AE2 Crafting Tree | 可选（客户端） | 画布集成对象（`@Pseudo` mixin）；未安装时跳过该注入，画布使用内置界面 |
| JEI | 可选（客户端） | ≥ 19.57.0；提供合成表页面的导入按钮 |
| Configured | 可选 | 2.x；提供游戏内配置编辑界面，配置本身不依赖它 |

可选依赖按软依赖处理：对应模组缺失时相关入口不加载，主计算与执行路径不受影响。

### 使用

装入后不需要额外设置。

1. 按常规方式编码 AE2 样板并放入样板供应器。
2. 在 ME 终端发起合成请求。
3. 计划与执行自动完成。

### 配置

配置文件为 `config/aecraftroute-common.toml`。`proxyEnabled=false` 关闭接管，计算交回 AE2 原生递归逻辑；此时画布上若设有约束，返回明确错误，不绕过约束执行。

从更名前的版本升级时，配置目录中只有 `ae2vm-common.toml` 的情况下，首次启动将其复制为新的配置文件名，并把旧文件改名为 `ae2vm-common.toml.migrated`。

第三方模组发起的请求默认透传给原生逻辑，需要接管的模组须显式注册，见下章。

---

## 七、第三方集成

```java
import com.aecraftroute.addon.api.CraftRouteCrafting;
import com.aecraftroute.addon.api.CraftRouteCraftingRegistry;

// 在 @Mod 构造函数中注册：声明本模组发起的请求允许被接管（marker 为类名包含的子串）
CraftRouteCraftingRegistry.register("mymod");

// 可选集成：已加载时使用本扩展的计算，否则回退原生
if (CraftRouteCrafting.isLoaded()) {
    CompletableFuture<ICraftingPlan> plan = CraftRouteCrafting.calculate(
        grid, requester, what, amount, strategy);
    plan.thenAccept(p -> submitToCpu(p, requester));
} else {
    nativeCalculate(requester, what, amount, strategy);
}
```

- 注册（`register`）决定本模组发起的请求是否被接管；计算（`calculate`）为主动调用本扩展的计划器。两者相互独立。
- AE2 自身（`appeng.*`）的请求总是被接管；未注册的模组沿用自己的逻辑。
- 依赖声明为 `compileOnly`。未安装本扩展时 `isLoaded()` 返回 `false`，调用方行为不变。
- `calculate()` 异步执行；`calculateSync()` 阻塞调用线程，并拒绝在服务端主线程上使用。

---

## 八、VM 内核与性能优化

为提升大批量合成请求的规划性能，本扩展实现了数值化 VM 内核作为候选路径优化。

### 8.1 VM 内核架构

**设计目标。** 在反向搜索之前尝试构造性前向批处理提示，快速找到可行的合成批次序列。该提示仅作为候选，必须通过独立的 `certify()` 验证才能被采纳。

**核心组件。**

- **`NumericProgramCompiler`**：将样板图编译为紧凑的字节码程序。采用稀疏指令编码，仅发射非零数量的键。操作码包括：
  - `CHECK_PREFIX` (1)：检查库存前缀是否满足输入需求
  - `LIMIT_OUTPUT` (2)：限制产出避免溢出
  - `APPLY_DELTA` (3)：应用净变化量
  - `RECORD_PATTERN` (4)：记录样板使用
  - `HALT` (5)：原子提交

- **`CompiledNumericProgram`**：不可变的请求本地字节码，包含指令数组、操作数、常量池和入口点。

- **`NumericCraftingVM`**：执行编译后的程序。原子语义：所有写操作影响私有副本，仅在 `HALT` 时可见。支持催化剂/种子语义（`count=min(count, 1+(working-key-input)/-delta)`）。

- **`NetDeltaOracle`**：纯数据下界分析，计算每种材料的净增益。仅用于下界估计，不参与剪枝、排序或可行性证明。

### 8.2 候选路径机制

**工作流程。**

1. `ProductionPlanner.probe()` 在反向搜索前调用 `NumericProgramCompiler` 编译样板图（在相同预算内）。
2. 为每个候选批次调用 `NumericCraftingVM.execute()` 模拟执行。
3. 构造候选合成词（word），包含目标和所有批次步骤。
4. **强制独立验证**：`certify(witness, target, increment, explored)` 从零开始正向重放，检查每一步的输入在执行时刻是否满足。
5. 仅当 `certified.status==FEASIBLE` 且预算未超时，才采用该候选。

**关键保证。**

- ✅ **候选词从不替代证明**：VM 结果必须通过 `certify()` 独立验证
- ✅ **Limits 未放宽**：probe 预算与搜索预算共享同一 deadline
- ✅ **原子执行**：VM 执行失败不污染库存
- ✅ **精确溢出保护**：使用 `addExact`/`multiplyExact` 避免静默溢出

### 8.3 性能特性

- **热路径优化**：1000/1360/2000 数量请求在 1-3 ms 内完成（预热后）
- **冷启动**：256 阶段链在 605-619 ms 内回退到反向搜索
- **预算控制**：`probeExpired(allowance)` = `expired() || System.nanoTime()-started>=allowance`

### 8.4 与旧 VM 的关系

版本 `1.13.19-local.32` 移除了旧的栈式 VM 引擎（`CraftingVM`、`CraftingBytecode`、`Opcode` 等）。新 VM 内核是完全独立的实现，仅作为候选路径优化，不影响主搜索算法。

---

## 九、项目结构

```
src/main/java/com/aecraftroute/addon/
├── CraftRouteAddon.java                # 模组入口（构建横幅、配置迁移）
├── BuildInfo.java                      # 构建戳读取与 /aecraftroute build 命令
├── api/                                # 公开接口与第三方注册
├── compat/                             # 包装样板兼容、外部模组适配（advancedae、thunderbolt 空壳）
├── compiler/                           # 有限耐久输入接口
├── config/                             # 配置定义
├── planner/
│   ├── ProductionPlanner.java          # 反向记账搜索与正向重放校验
│   ├── NetworkProductionPlanner.java   # 网络样板图 → 计划
│   ├── CertifiedCraftingPlan.java      # 计划与执行程序
│   ├── ProductionPlanCache.java        # 按值比较的有界计划缓存
│   ├── PatternVariant(s).java          # 样板输入槽位的精确组合
│   ├── FiniteToolTransitions.java      # 有限耐久工具的转移校验
│   ├── FuzzyFamilyAllocator.java       # 材料族的带容量匹配
│   └── ProductionJob.java              # CPU 内执行状态（可持久化）
├── route/                              # 画布、规划服务与网络规则
│   ├── RouteSnapshot.java              # 唯一快照来源（样板与库存）
│   ├── RoutePlanningService.java       # 服务端 tick 驱动的异步规划
│   ├── RouteCanvasPlanner.java         # 画布有限路径 → 认证计划
│   ├── ForcedRoutePlanner.java         # 有限路径执行契约
│   ├── RouteApply.java                 # 整幅原子应用
│   └── client/                         # 画布界面
├── telemetry/                          # 结果记录与日志节流
├── mixin/                              # 合成服务与 CPU 执行桥
├── vm/                                 # 回料环识别与净产出判定
└── selftest/                           # 独立实例自检（不进入发布 jar）

src/main/resources/
├── META-INF/neoforge.mods.toml
├── aecraftroute.mixins.json
├── aecraftroute.png
└── assets/ae2/screens/aecraftroute_route.json
```

---

## 十、构建与测试

```bash
# 单次构建，产物位于 build/libs/
gradlew -p . jar --offline
```

产物为单个 `aecraftroute-<版本>_neoforge_1.21.1.jar`。构建过程不写入游戏实例，把产物放入 `mods/` 是独立的显式步骤。放入前须删除 `mods/` 目录中已有的 `aecraftroute*.jar` 与更名前遗留的 `ae2vm*.jar`，否则启动时因 modId 重复而失败。

测试分为三组任务，正确性回归与性能测量分开：

```bash
gradlew test                      # 单元与集成回归，排除 benchmark 标签
gradlew routeFunctionalTest       # 路线功能回归
gradlew productionBenchmark       # 生产计划器微基准
```

当前离线回归为 38 套 271 项，0 失败、0 错误、0 跳过。真实网格验证在独立实例中进行：搭建包含控制器、驱动器、合成存储器、样板供应器、分子装配器与合成 CPU 的 ME 网络，使用真实编码样板与真实下单。自检按场景逐个运行：净增长型回料环（`growth`）、取消（`cancel`）、CPU 自存档重载（`reload`）、AdvancedAE 量子计算机（`advanced`）、请求方部分接受产物（`partial`）、画布有限路径（`paths`），以及服务器停止前后分两次进行的续跑（`restart1`、`restart2`）。

---

## 十一、许可与致谢

本项目以 LGPL-3.0 授权，全文见 [LICENSE](LICENSE)。版本历史见 [CHANGELOG.md](CHANGELOG.md)。第三方作品的引用方式与授权见 [ATTRIBUTIONS.md](ATTRIBUTIONS.md)，早期实现的历史见 [ACKNOWLEDGEMENTS.md](ACKNOWLEDGEMENTS.md)。
