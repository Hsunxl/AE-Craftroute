# 第三方致谢与许可声明

**Third-Party Attributions & License Notice — AE-Craftroute (`aecraftroute`)**

> 本模组引用了他人作品：AE2 及其生态模组的 API、JEI 的配方绘制体系，以及若干整合包模组的兼容适配。
> 本文件逐条列出引用的对象、引用方式、对方的授权，以及分发情况。
>
> 本模组自身以 **LGPL-3.0** 发布，全文见 [LICENSE](LICENSE)。
> 更新日期：2026-10-24 · 对应版本：`1.13.19-local.34-dev`

---

## 0. 三条基本保证

1. **不分发他人代码**：本仓库与本模组的 JAR 中**不包含**任何第三方模组的 class、jar、贴图、模型或数据包。
   所有第三方依赖在 `build.gradle` 中声明为 `compileOnly`（或 `testRuntimeOnly`），由玩家在整合包 / `mods` 目录里自行安装。
2. **只通过公开 API 与 Mixin 交互**：对第三方模组的运行时影响方式只有两种 —— 调用其**公开 API**，或通过 **Mixin 注入**其类（见第 3 节，逐类列出）。
3. **可移除**：若权利人对本项目的使用方式有异议，请在本仓库开 issue 说明，确认后**移除相关代码或数据**。

---

## 1. 核心依赖（编译期 / 运行时必需）

| 作品 | 作者 / 团队 | 授权 | 引用方式 | 分发状态 |
|---|---|---|---|---|
| **Applied Energistics 2** (`ae2`)<br>https://github.com/AppliedEnergistics/Applied-Energistics-2 | Team AppliedEnergistics | **LGPL-3.0** | 整个模组的基础平台：合成计算拦截、样板、Key / 库存 API、菜单与 GUI、`BackgroundGenerator` 调色板、网络包体系 | 否 |
| **Just Enough Items** (`jei`)<br>https://github.com/mezz/JustEnoughItems | mezz | **MIT** | 画布右侧配方显示：`IRecipeLayoutDrawable` 原生绘制、`IGuiProperties`、ghost ingredient 拖拽、配方列表与 catalyst（机器图标） | 否（可选，仅客户端） |
| **AE2 JEI Integration** (`ae2jeiintegration`) | Tamaized, mezz | **LGPL-3.0** | `IngredientConverters`：JEI ingredient ↔ `AEKey` 双向转换，使画布能识别物品 / 流体 / 化学品 | 否 |
| **AE2:Crafting Tree** (`ae2ct`)<br>作者：Neuvillette | Neuvillette | **All Rights Reserved** | 读取其**已同步**的合成计划（最终产物 / 数量 / 配方数），用于「修改合成树」自动导入；另有 Mixin 折叠按钮兼容（第 3 节）。**未复制任何 AE2CT 代码或资源** | 否 |
| **MixinExtras** (`io.github.llamalad7:mixinextras-neoforge`)<br>https://github.com/LlamaLad7/MixinExtras | LlamaLad7 | **MIT** | `@WrapOperation` / `@Local` 等 Mixin 增强注解，用于精确包裹 AE2 方法调用 | 编译期依赖（通常由加载器提供，不重复打包） |
| **NeoForge**<br>https://github.com/neoforged/NeoForge | NeoForged Team | **LGPL-2.1** | 模组加载器与事件总线（`ScreenEvent`、`RegisterKeyMappingsEvent`、网络注册） | 否 |
| **Minecraft** | Mojang Studios / Microsoft | Minecraft EULA | 运行平台 | 否 |

> **关于 AE2CT（All Rights Reserved）**：本项目对 AE2CT 的使用仅限「读取其已同步到客户端的数据」与「一个只调整按钮 Y 坐标与节点提示的 GUI 兼容 Mixin」。
> 由于该模组授权为保留所有权利，这段兼容（`RouteTreeWidgetMixin` 与自动导入）是独立实现，**可以在不改变画布其余功能的前提下移除**。

---

## 2. 可选兼容 / 已适配模组（不打包、不修改，仅在检测到时改变行为）

这些模组**不是依赖**，本项目不含其任何代码；只在玩家同时安装时做兼容适配。

| 模组 | 作者 | 授权 | 本项目的兼容点 |
|---|---|---|---|
| **AdvancedAE** (`advanced_ae`) | Pedroksl | LGPL-3.0 | `AdvancedAECompat`：量子计算机的 CPU 执行桥与任务读取 mixin；未安装时该组 `@Pseudo` mixin 不注入 |
| **ExtendedAE** (`extendedae`) | GlodBlock | LGPL-3.0 | 扩展样板供应器 / 装配矩阵的样板来源识别 |
| **Neo ECO AE Extension** (`neoecoae`) | DancingSnow, ZhuRuoLing, Yang120231 | **GPLv3** | 仅作**运行时**可选的样板来源 / 能力探测，**不与本项目代码链接或派生** |
| **ME 综合工作终端 / WCWT** (`wcwt`) | lhy | **MIT** | `RouteClient.separateTerminalButtons`：把终端按钮上移 22px，避开 JEI 书签按钮 |
| **FTB Library** (`ftblibrary`) | FTB Team | All Rights Reserved | **不注入、不修改**；本项目刻意使用普通 `Screen` 而非 `AbstractContainerScreen`，以**规避** FTB 自动注入的按钮 |
| **Dark Mode Everywhere** (`darkmodeeverywhere`) | Buuz135 | **MIT** | **不注入、不修改**；自绘按钮只重写 `renderWidget`，避免被其染黑 |
| **Configured** (`configured`) | 见其仓库 | 见其仓库 | 可选：自动发现本模组注册的 `ModConfig` 并提供游戏内编辑界面。**配置本身不依赖它**，未安装时 TOML 照常生成并生效 |

---

## 3. Mixin 注入目标（运行时修改的第三方类，逐条列出）

> Mixin 是运行时字节码注入，**不复制、不分发**目标类代码。这里逐条公开，便于权利人审计。

| 目标类 | 所属模组 | 注入目的 |
|---|---|---|
| `appeng.crafting.CraftingService` | AE2 | 拦截 `beginCraftingCalculation`，把纯 AE2 样板的计算交给本扩展的计划器 |
| `appeng.crafting.execution.CraftingCpuLogic` | AE2 | 合成 CPU 的作业执行与生长型任务接入（两个 mixin） |
| `appeng.crafting.execution.ExecutingCraftingJob`（accessor） | AE2 | 读取 / 写入任务进度（只读访问器 + 生长标记） |
| `appeng.crafting.execution.ExecutingCraftingJob$TaskProgress`（accessor） | AE2 | 读取任务进度（只读访问器） |
| `appeng.crafting.execution.ElapsedTimeTracker`（accessor） | AE2 | 读取耗时（只读访问器） |
| `appeng.menu.AEBaseMenu`（accessor） | AE2 | 画布入口：终端菜单访问 |
| `appeng.client.gui.AEBaseScreen`（accessor） | AE2 | 画布入口：终端工具栏按钮注入 |
| `appeng.menu.me.crafting.CraftConfirmMenu`（accessor） | AE2 | 「修改合成树」读取最终产物与数量 |
| `net.pedroksl.advanced_ae.common.logic.AdvCraftingCPULogic` | AdvancedAE | 量子计算机的作业执行接入（两个 mixin，`@Pseudo`） |
| `net.pedroksl.advanced_ae.common.logic.ExecutingCraftingJob`（accessor） | AdvancedAE | 读取 / 写入任务进度（`@Pseudo`） |
| `net.pedroksl.advanced_ae.common.logic.ExecutingCraftingJob$TaskProgress`（accessor） | AdvancedAE | 读取任务进度（`@Pseudo`） |
| `net.pedroksl.advanced_ae.common.logic.ElapsedTimeTracker`（accessor） | AdvancedAE | 读取耗时（`@Pseudo`） |
| `com.neuvillette.ae2ct.gui.CraftingTreeWidget` | AE2CT | 折叠 / 还原按钮行为兼容与节点路径提示；调用其公开的 `com.neuvillette.ae2ct.api.CraftingTreeHelper`（`@Pseudo`） |

全部第三方目标 mixin 均标注 `@Pseudo`：对应模组未安装时不注入，也不报错。

---

## 4. 第三方数据文件

| 文件 | 来源 | 说明 |
|---|---|---|
| `ae2lt-recipes/*.json`（10 个） | 参照 **AE2 Lightning Tech**（`ae2lt`，MOAKIEE 等，LGPL-3.0）的 `ae2lt:overload_processing` 配方格式 | 仅在 `neoforge:mod_loaded` 条件满足（装了 `ae2lt` 且装了 `advanced_ae`）时生效；用于整合包的样板 / 配方对齐 |
| `src/main/resources/assets/ae2/screens/aecraftroute_route.json` | 本项目原创，使用 AE2 的 `BackgroundGenerator` 屏幕定义格式 | 调色板取自 AE2 终端（#CBCCD4 / #9A9FB4 / #696D88） |

---

## 5. 算法、思路与代码来源

| 来源 | 关系 | 说明 |
|---|---|---|
| **AE2 原生 CraftingCalculation / CraftingSimulationState** | 行为基线（LGPL-3.0） | 计划器的语义必须与 AE2 原生一致；本项目同样采用 LGPL-3.0，符合上游授权 |
| **VM-GTL（同作者 1.20.1 Forge 分支）** | 同作者反向移植 | v1.13.x 的环感知样板选择、种子环裁剪、纯转换环可行性守卫、多笔订单卡死修复，均从 VM-GTL 1.12.x–1.15.x 移植而来 |
| **JEI 官方 API / 示例** | API 用法参考（MIT） | `IRecipeLayoutDrawable`、`IGuiProperties`、ghost ingredient 的标准用法 |
| **Thunderbolt-Core** | 早期参考框架 | 该库曾作为参考规划器框架的来源，并以其公开能力定义组织过测试基准。集成于 `v1.13.16` 停用，相关基准随后移除；当前代码不含也不引用该库的任何类型，详见第 6 节 |

---

## 6. 已移除的依赖

**Thunderbolt-Core**（`thunderbolt`，MOAKIEE, CystrySU, gjmhmm8, _leng, TedXenon, MHanHanBing, QianChang，**LGPL-3.0**）曾以 `compileOnly` 形式参与构建至 `v1.13.15`，用于通过其公开 API 把本模组注册为可选合成规划引擎。

`v1.13.16` 起该集成停用：`src/main/java/com/aecraftroute/addon/compat/thunderbolt/` 下的两个类保留为空壳，包内无任何实现；源码中不再引用 `com.moakiee` 下的任何类型；`build.gradle` 不再声明该依赖；本模组的 JAR 中也不含相关 class。因此当前版本**不涉及** Thunderbolt-Core 的任何代码或运行时行为。

恢复该集成须同时还原对应源码与依赖声明。

---

## 7. 商标与从属关系

Applied Energistics 2、AE2 是 **Team AppliedEnergistics** 的项目与商标；Just Enough Items、JEI 属于 **mezz**；其余模组名称与商标归各自作者所有。

**AE-Craftroute 是一个独立的第三方附属模组，与上述任何项目均无隶属、赞助或背书关系。** 模组内出现的其他模组名称仅用于说明兼容性。

---

## 8. English Summary

**AE-Craftroute** is licensed under **LGPL-3.0** (see [LICENSE](LICENSE)). It **does not redistribute** any third-party mod code, assets, or data; every third-party dependency is declared compileOnly and must be installed by the player.

| Component | Author(s) | License | How it is used | Distribution |
|---|---|---|---|---|
| Applied Energistics 2 | Team AppliedEnergistics | LGPL-3.0 | Core platform; crafting calculation is intercepted via Mixin, all AE2 APIs are read-only | No |
| Just Enough Items | mezz | MIT | Native recipe layout rendering on the canvas | No |
| AE2 JEI Integration | Tamaized, mezz | LGPL-3.0 | IngredientConverters (JEI ingredient <-> AEKey) | No |
| AE2:Crafting Tree | Neuvillette | **All Rights Reserved** | Reads its already-synchronised crafting plan; one GUI-only compatibility Mixin. **No AE2CT code is copied.** Removed on request. | No |
| MixinExtras | LlamaLad7 | MIT | Mixin enhancement annotations | Compile-time only |
| NeoForge | NeoForged Team | LGPL-2.1 | Mod loader / event bus | No |
| Minecraft | Mojang | EULA | Platform | No |

Optional compatibility targets (not bundled, not modified): AdvancedAE (LGPL-3.0), ExtendedAE (LGPL-3.0), Neo ECO AE (GPLv3, runtime detection only), WCWT (MIT), FTB Library (All Rights Reserved - deliberately avoided instead of patched), Dark Mode Everywhere (MIT), Configured (optional in-game config editor; the config does not depend on it).

All third-party mixin targets carry `@Pseudo`: without the corresponding mod installed the injection is skipped rather than failing.

**Removed dependency:** Thunderbolt Core (LGPL-3.0) was an optional compile-only dependency up to `v1.13.15`. Since `v1.13.16` its integration is stubbed out: no source references it, `build.gradle` declares nothing, and no related class ships in the jar. The current version therefore involves no Thunderbolt-Core code or runtime behaviour.

Mixin targets are enumerated in section 3 - each one is a runtime bytecode injection into the target mod, not a copy of it.

**If you are a rights holder and object to any use above, please open an issue; the relevant code or data will be removed.**
