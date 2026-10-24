# 参与开发

本文件给出构建、测试、代码位置与提交前自检的规定。所述命令均在仓库根目录执行。

## 构建

```bash
gradlew -p . jar --offline
```

产物为 `build/libs/aecraftroute-<版本>_neoforge_1.21.1.jar`。`--offline` 表示使用本地依赖缓存，不访问网络；依赖缓存不完整时去掉该参数。

构建过程不写入游戏实例。把产物放入 `mods/` 是独立的显式步骤，放入前须删除该目录中已有的 `aecraftroute*.jar` 与 `ae2vm*.jar`，否则启动时因 modId 重复而失败。

`gradle.properties` 中的 `mod_version` 决定产物文件名。同名版本的两次构建由 `generateBuildStamp` 写入构建戳区分，构建戳记录在 `aecraftroute-build.properties` 中。

## 测试

回归与性能测量分属不同任务，不得混用。

```bash
gradlew test                      # 单元与集成回归，排除 benchmark 标签
gradlew routeFunctionalTest       # 路线功能回归，附加 -Xverify:all
gradlew productionBenchmark       # 生产计划器微基准，仅运行 benchmark 标签
```

`test` 与 `routeFunctionalTest` 共同构成完整回归集。当前的完整回归为 38 套 271 项，0 失败、0 错误、0 跳过。全部通过是提交的必要条件。

`productionBenchmark` 的参数由 `-PbenchmarkWarmups`（默认 10）与 `-PbenchmarkSamples`（默认 30）控制。该任务的结果用于横向比较，不作为正确性判据。

真实网格验证在独立实例中进行，不使用安装中的整合包实例：

```bash
gradlew runSelftestServer -PselftestTask=growth
```

`selftestServer` 是构建脚本中的运行配置，对应的 Gradle 任务名为 `runSelftestServer`（客户端质量检查运行配置对应 `runQaClient`）。该任务使用 `run-selftest/` 作为运行目录，激活参数为 `-Daecraftroute.selftest=1`。可用场景为 `growth`、`cancel`、`reload`、`advanced`、`partial`、`paths`、`restart1`、`restart2`；`restart1` 与 `restart2` 须分两次运行，前者在作业在途时保存并停止服务器，后者读取同一存档继续。自检类不进入发布 jar。

## 代码位置

| 目录 | 职责 |
|------|------|
| `src/main/java/com/aecraftroute/addon/planner/` | 计算核心：反向记账搜索、前向重放校验、计划缓存、样板变体、有限耐久工具转移、材料族分配 |
| `src/main/java/com/aecraftroute/addon/route/` | 画布与网络：快照、异步规划服务、有限路径编译、整幅原子应用、网络规则 |
| `src/main/java/com/aecraftroute/addon/route/client/` | 画布界面、JEI 集成、客户端入口 |
| `src/main/java/com/aecraftroute/addon/mixin/` | 合成服务与 CPU 执行桥的注入点 |
| `src/main/java/com/aecraftroute/addon/compat/` | 外部模组适配：包装样板、AdvancedAE、Thunderbolt 空壳 |
| `src/main/java/com/aecraftroute/addon/api/` | 公开接口与第三方注册表 |
| `src/main/java/com/aecraftroute/addon/telemetry/` | 结果记录与日志节流 |
| `src/main/java/com/aecraftroute/addon/vm/` | 回料环识别与净产出判定 |
| `src/main/java/com/aecraftroute/addon/selftest/` | 独立实例自检，不进入发布 jar |

计算相关的改动落在 `planner/`，界面与网络相关的改动落在 `route/`。两者通过不可变快照交换数据，不共享可变状态：`route/` 不直接调用 `planner/` 内部步骤，`planner/` 不持有网格引用。

## 提交前自检

以下各项须逐条确认。

1. **全量回归通过。** `gradlew test routeFunctionalTest` 全部通过，套数与用例数不低于当前基线 38 / 271。
2. **jar 内容核对。** 产物中不含旧标识符：执行 `unzip -l` 或等效方式检查，`com/ae2vm/` 路径与 `ae2vm*.class` 均不应出现；不含 `selftest/` 包内的类。
3. **标识符一致。** 新增代码使用 `aecraftroute` / `com.aecraftroute.addon`。旧键 `ae2vmProductionV1` 一类字符串仅允许出现在读取旧存档的兼容分支中。
4. **无本机路径。** 源码、构建脚本与文档中不出现盘符路径与本机用户名。
5. **持久化兼容。** 改动涉及 NBT 键时，保留对旧键的回退读取，并在写档时清除旧键。
6. **新结论值有对应处理。** 新增判定结果时，界面文案与调用方分支同步更新；不得把非 `UNREACHABLE` 的结果表述为缺少材料。

## 报告问题

报告须包含以下信息，缺少时无法定位。

- 版本号：`/aecraftroute build` 的输出，或 `gradle.properties` 中的 `mod_version`，或 jar 文件名中的版本段。
- 构建戳：`aecraftroute-build.properties` 中的 `built` 字段。
- 环境：Minecraft、NeoForge、AE2 的版本，以及与本模组同时安装的相关模组（AdvancedAE、AE2 Crafting Tree、JEI、Configured）及其版本。
- 复现步骤：从发起合成请求到出现异常的操作序列，注明涉及的材料、数量与是否使用画布。
- 日志：`logs/latest.log` 中包含 `[AE-Craftroute]` 的段落，以及计划失效、`LIMIT`、`UNSUPPORTED` 相关的输出行。
- 预期与实际：请求的结果结论，以及实际的界面提示或 CPU 状态。

涉及计算错误时，另附该网络的样板清单与当时的库存数量。
