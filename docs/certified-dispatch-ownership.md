# 认证派发所有权事务

## 范围

原生 AE2 和 AdvancedAE 的认证程序派发共用 ProductionJob.dispatch 和 CertifiedDispatchScope。
当前仍是原生每次一个样板任务，不扩展单批次数、不绕过 CertifiedExecutionScope 或 Thunderbolt 批量隔离。
首份抽料沿用 ProductionJob.extract 的原库存视图。拒收后的持有物由原生 executeCrafting 继续尝试供应器或回注，事务本身不复制或退款实物。

## 所有权状态

- PREPARED：尚未取得接收证据；正常 false 结束表示 REJECTED，允许原生回注仍属于 CPU 的材料。
- ACCEPTED：供应器 true 返回，或在持久材料所有权转移点调用明确回执；游标只推进一次。
- UNKNOWN：供应器异常且没有回执，或游标/持久化回调失败；使当前程序 invalid 并上抛异常。不能把它变成 false 后退款、换供应器或重新下单。
- 已有明确回执后，普通 RuntimeException 清理失败保留成功结果，让原生继续登记 waitingFor 和任务扣减；日志全局限流 30 秒。致命 Error 仍上抛，不宣称其后原生账本已完成结算。

嵌套 wrapper 仅在 job、pattern、真实输入数组身份全部相同的情况下共享事务。相同样板的另一次派发不是同一事务。另一个 CPU/作业的嵌套调用保留独立上下文；finally 恢复线程作用域。上下文不能跨线程、存档或缓存。

## 可选供应器回执

```java
import com.aecraftroute.addon.api.CraftRouteDispatch;

// 先确认供应器已经持久地拥有本次输入；不是 SIMULATE、排队意向或容量检查。
boolean matched = CraftRouteDispatch.confirmAccepted(pattern, actualInputHolders);
// 然后才执行可能抛 RuntimeException 的后处理。
```

pattern 和 actualInputHolders 必须是当前 pushPattern 收到的原对象。数组拷贝、其他样板、异线程或没有匹配调用时返回 false。
重复回执不会重复扣减任务。回执之后即使误返回 false，事务也保持 true，防止原生退款已移交输入。
该 API 只确认当前一次原生派发，不授权外部批量、额外抽料或变更样板倍率。未知所有权仍需人工核对，不保证自动恢复不遵守契约的供应器。

## 同步回料边界

目标 AE2 的 executeCrafting 在 pushPattern 返回 true **之后**才登记 waitingFor。
本次改动不提前造出等待量，也不修改该时序。同步回调 insert 若返回未接收数量，供应器必须保留这部分产物供稍后重试；不能把生成产物或一次模拟插入当作真实结算。
认证最终交付仍在 tick TAIL，根据 CPU 真实库存和原有种子保留规则进行。

## 持久化与验证边界

已接收进度沿用 ProductionJob 的现有 NBT 游标；事务本身不序列化。未知失败的 invalid 标记随现有程序 NBT 保存，不允许恢复后继续重投。
单元测试覆盖拒收回注、明确回执、清理异常、未知失败、嵌套/异线程/数组身份、旧接口重复推进、真实 NBT 游标往返。
未知失败并不能反推出材料归属；本实现不会将它伪装成安全退款。供应器/外部批量的完整共存矩阵仍需独立验证。
