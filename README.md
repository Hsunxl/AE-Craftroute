# AE2 VM — AE2 合成计划器

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-blue?logo=minecraft)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.259+-orange)
![AE2](https://img.shields.io/badge/AE2-19.2.18+-green)
![Java](https://img.shields.io/badge/Java-21-red)
![License](https://img.shields.io/badge/License-LGPL%20v3-blue)

> **English version**: [README_en.md](README_en.md)

**AE2 VM** 是 [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2) 的 NeoForge 扩展模组。它把 AE2 原有的**递归合成树遍历**换成**一次性的可证明计划搜索**：在考虑真实库存的前提下反向推导"到底要做什么、按什么顺序做"，并把结果连同**可执行程序**一起交给合成 CPU。

它解决的是递归算法结构上算不对的三类配方：

- **循环配方**（`A→B`、`B→2A` 这类回料环）——递归展开会无限展开或直接判失败；
- **增量 / 净产出**（产物又参与自身合成，只有净增量才是真正需求）；
- **真实可执行顺序**（回料必须在装配室之间按顺序流转，不能当成一次性消耗）。

---

## 它到底改了什么

AE2 判断"能不能合成"的方式是把样板树递归展开一遍。遇到上面三类配方，展开要么爆栈、要么算出一个**实际执行不了的计划**——于是玩家看到两种互相矛盾的现象：明明能合成却显示缺料，或者计划显示可行但 CPU 卡在 0 进度。

AE2 VM 把这一步换成一个**可证明的搜索**：

1. **快照**（`RouteSnapshot`）：一次读出这台网络上的样板集合与库存，作为整个计算的唯一依据。画布预览与真正下单用的是同一个快照构造器，所以"看到的"和"执行的"不可能对不上。
2. **计划**（`NetworkProductionPlanner`）：反向可覆盖性搜索，显式维护"每个材料要多少、库存给多少、还差多少由哪个样板补"，循环与净产出都在库存记账里自然成立。
3. **证明**（`CertifiedCraftingPlan`）：只有搜索通过前向重放校验（每一步的输入在执行那一刻确实够用）才返回。计划自带 `usedItems`（起手需要什么）和 `program`（按什么顺序执行），`missingItems()` 恒为空——**计划本身就是可执行性的证明**。
4. **执行**（CPU mixin）：CPU 拿到认证程序后按程序顺序派单，把回料留在 CPU 库存里供下一轮复用，等 `waitingFor` 清空、程序走完才把最终产物交付给请求方。

**四种结论严格区分**（这是本模组对"伪缺料"的核心修复）：

| 结论 | 含义 |
|------|------|
| `feasible` | 已证明可合成，附带起手清单与执行程序 |
| `UNREACHABLE` | 已证明**做不到**（真正的缺料/无生产者） |
| `LIMIT` | 预算内**尚未判定**——不是缺料 |
| `UNSUPPORTED` | 无法精确建模——未按缺料处理 |

只有 `UNREACHABLE` 才会说"缺料"。预算耗尽、样板语义无法建模都**不会**被伪装成缺料。

---

## 合成画布

除了替换算法，本模组还提供一个**合成树画布**：把 AE2 算出的路线直接画出来，你可以对每个节点指定用哪个样板、往哪一层展开。

- **权威校验**：画布显示的可行性、起手清单、批次数量全部来自服务端同一个认证计划器，不是客户端估算。目标材料一改就重新请求，看到的结论与下单时一致。
- **按材料锁定**：你可以逐节点指定"这个材料用这张样板"。锁定按材料生效，画布上的选择会原样进入服务端计算。
- **整幅原子应用**：一次把所有节点的选择作为一个整体提交，服务端逐个校验后一次性应用。
- **冲突逐节点诊断**：同一个材料在画布上被指定了两张不同样板时，不会静默挑一个，而是明确告诉你哪个材料冲突、需要先统一；选中的样板若已不在网络里，也会单独指出是哪个材料。

---

## 验证情况

本项目对合成计划的正确性采用**独立实例真机验证**，而不是只看单元测试：

- **离线测试**：189 个测试全通过，覆盖计划器、画布预览/应用、载荷上限、以及用 ae2cs 聚合器链（8 粉 + 8 充能石英 + 32 沙 → 32 种子）做的固定回归。
- **真实网格**：专用开发服务器里搭真实 ME 网络（控制器、驱动、合成存储、样板供应器、分子装配器、合成 CPU），真实编码样板、真实下单：
  - 三节点回料环（绿宝石→钻石→2 青金石→绿宝石），只给 1 个绿宝石请求 3 个钻石 → 交付 3 个，库存清零；
  - 取消场景、CPU 存档往返、**真实停服重启后续跑**（新进程从存档恢复在途作业并完成交付）；
  - requester 只接受一部分产物时的分次交付；
  - AdvancedAE 量子计算机上执行同一套程序。

---

## 使用方法

### 安装

1. 安装 [NeoForge](https://neoforged.net/) 与 [Applied Energistics 2 19.2.18+](https://www.curseforge.com/minecraft/mc-mods/applied-energistics-2)
2. 将 `ae2vm-nodetect-<版本>.jar` 放入 `mods/` 文件夹（**只能保留一个**，否则 duplicate modId 报错）
3. 启动游戏，日志出现 `AE2 VM <版本> (built ...)` 横幅即加载成功

### 在游戏中使用

装好即生效，无需额外设置：

1. 正常编码 AE2 样板放进样板供应器
2. 在 ME 终端发起合成请求
3. 计划与执行全自动

**配置开关**：`config/ae2vm-common.toml` 的 `proxyEnabled=false` 可关闭接管、交回 AE2 原生递归计算（画布上设了约束时会明确报错，而不是悄悄绕过约束）。

**第三方样板**：ECO 等模组自己的请求默认透传给原生逻辑；需要接管的第三方模组可显式注册（见下）。

---

## 第三方模组集成（开发）

```java
import com.ae2vm.addon.api.AE2VMCrafting;
import com.ae2vm.addon.api.AE2VMCraftingRegistry;

// 在 @Mod 构造函数中注册（marker = 类名包含的子串），声明"本模组的请求可以接管"
AE2VMCraftingRegistry.register("mymod");

// 可选集成：仅当 AE2 VM 已加载时使用自己的计算，否则回退原生
if (AE2VMCrafting.isLoaded()) {
    CompletableFuture<ICraftingPlan> plan = AE2VMCrafting.calculate(
        grid, requester, what, amount, strategy);
    plan.thenAccept(p -> submitToCpu(p, requester));
} else {
    nativeCalculate(requester, what, amount, strategy);
}
```

- **注册**（`register`）决定要不要接管本模组发起的请求；**计算**（`calculate`）是主动调用本模组的计划器。
- AE2 自身（`appeng.*`）的请求总是被接管；未注册的第三方模组走自己的逻辑。
- 依赖声明 `compileOnly`，未安装本模组时 `isLoaded()` 返回 `false`，一切照常。
- `calculate()` 异步执行；`calculateSync()` 阻塞调用线程，仅非服务器主线程使用。

---

## 项目结构

```
src/main/java/com/ae2vm/addon/
├── AE2VMAddon.java                  # Mod 入口（兼容性检测 / 构建横幅）
├── BuildInfo.java                   # 构建版本戳读取 + /ae2vm build 命令
├── api/
│   ├── AE2VMCrafting.java           # 公开 API（calculate / planNow）
│   ├── AE2VMCraftingRegistry.java   # 第三方模组注册表
│   └── DispatchTrace.java           # 认证程序挂载归属（AE2 / AdvancedAE）
├── planner/
│   ├── ProductionPlanner.java       # 反向可覆盖性搜索 + 前向重放校验
│   ├── NetworkProductionPlanner.java# 网络样板图 → 计划
│   ├── CertifiedCraftingPlan.java   # 计划 + 执行程序
│   ├── PatternVariant.java          # 样板槽位组合枚举
│   ├── ProductionJob.java           # CPU 内的程序执行状态（可持久化）
│   └── ProductionPlanningException.java # 四类结论
├── route/
│   ├── RouteSnapshot.java           # 唯一快照来源（样板 + 库存）
│   ├── RoutePlanView.java           # 画布的权威计划视图
│   ├── RouteIntent.java             # 按材料锁定 + 冲突诊断
│   ├── RouteApply.java              # 整幅原子应用
│   ├── RouteNetwork.java            # 画布网络协议
│   └── client/                      # 画布界面
├── vm/
│   ├── TwoNodeGrowthPlanner.java    # 两节点回料环识别 + 派发顺序
│   ├── NetProduction.java           # 净产出判定
│   └── FeedbackReservation.java     # 回料预留
├── mixin/
│   ├── CraftingServiceMixin.java    # 接管 beginCraftingCalculation
│   ├── ProductionCpuMixin.java      # AE2 合成 CPU 的执行桥
│   ├── AdvancedProductionCpuMixin.java # AdvancedAE 量子 CPU 的执行桥
│   └── GrowthCpuMixin.java / AdvancedGrowthCpuMixin.java # 非认证计划的兜底
└── selftest/                        # 独立实例自检（不打进发布 jar）
```

---

## 模组信息

| 项目 | 值 |
|------|-----|
| Mod ID | `ae2vm` |
| 名称 | AE2 VM |
| 包名 | `com.ae2vm.addon` |

### 依赖

| 依赖 | 版本 |
|------|------|
| Minecraft | 1.21.1 |
| NeoForge | ≥ 21.1.169（实际测试 21.1.259） |
| Applied Energistics 2 | ≥ 19.2.17（实际测试 19.2.18） |
| AdvancedAE（可选） | 1.6.12 |
| Configured（可选，游戏内配置界面） | 2.x |

---

## 构建

```bash
# 单次构建（产物在 build/libs/）
gradlew -p . jar --offline -PblockedMode=warn

# 构建 + 递增版本号 + 复制进 mods
buildBoth.bat
```

> `-PblockedMode=warn` 产出 `nodetect` 变体。整合包里装有 `data_energistics` 时**必须**用这个变体，否则本模组会在启动时主动拦截。

---

## 许可与出处

LGPL v3，见 [LICENSE](LICENSE)。版本历史见 [CHANGELOG.md](CHANGELOG.md)。
