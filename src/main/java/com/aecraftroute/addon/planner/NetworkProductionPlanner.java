package com.aecraftroute.addon.planner;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import java.util.*;
import java.util.function.Function;
import static com.aecraftroute.addon.planner.ProductionPlanningException.Reason.*;

/** Finite snapshot -> fresh exact variants -> cached numeric compilation -> certified
 * plan. Compiled entries contain no live patterns; the executable binding is always fresh. */
public final class NetworkProductionPlanner {
    private static final ProductionPlanCache<AEKey> CACHE = new ProductionPlanCache<>();
    public static CertifiedCraftingPlan plan(AEKey target,long increment,
            Function<AEKey,? extends Collection<IPatternDetails>> source,Function<AEKey,Long> stock) {
        return plan(target, increment, source, stock, ProductionPlanner.Limits.defaults());
    }

    /** One worker deadline covers traversal, variant allocation, search and binding.
     * Live grid capture is separately sliced on the server thread. */
    public static CertifiedCraftingPlan plan(AEKey target,long increment,
            Function<AEKey,? extends Collection<IPatternDetails>> source,Function<AEKey,Long> stock,
            ProductionPlanner.Limits limits) {
        return plan(target, increment, source, stock, limits, true);
    }

    /** Package-private uncached path for correctness comparisons and reproducible benchmarks. */
    static CertifiedCraftingPlan plan(AEKey target,long increment,
            Function<AEKey,? extends Collection<IPatternDetails>> source,Function<AEKey,Long> stock,
            ProductionPlanner.Limits limits, boolean cache) {
        if(target==null || increment<=0) throw new ProductionPlanningException(INVALID,"净增量必须大于零");
        long started=System.nanoTime();
        long deadline=started+Math.min(limits.nanos(),Long.MAX_VALUE/2);
        var queue=new ArrayDeque<AEKey>(); queue.add(target);
        var visited=new LinkedHashSet<AEKey>(); var patterns=new LinkedHashSet<IPatternDetails>();
        var recipes=new ArrayList<ProductionPlanner.Recipe<AEKey,PatternVariant>>();
        var snapshot=new LinkedHashMap<AEKey,Long>();
        boolean incomplete=false;
        try {
            while(!queue.isEmpty()) {
                remaining(started,limits);
                var key=queue.remove(); if(!visited.add(key)) continue;
                snapshot.put(key,Math.max(0,stock.apply(key)));
                var choices=source.apply(key);
                if(choices==null) continue;
                for(var pattern:choices) {
                    remaining(started,limits);
                    com.aecraftroute.addon.compat.ScaledPatternCompat.validate(pattern);
                    if(!patterns.add(pattern)) continue;
                    for(var input:pattern.getInputs()) {
                        var options=input.getPossibleInputs();
                        if(options!=null) for(var option:options) if(option!=null && option.what()!=null && option.amount()>0) {
                            remaining(started,limits);
                            queue.add(option.what());
                            var remaining=input.getRemainingKey(option.what()); if(remaining!=null) queue.add(remaining);
                        }
                    }
                    for(var out:pattern.getOutputs()) if(out!=null && out.what()!=null) {
                        // Include byproduct keys and their alternate producers too.
                        queue.add(out.what());
                    }
                    var variants=PatternVariants.enumerate(pattern,stock,deadline);
                    incomplete |= !variants.complete();
                    recipes.addAll(variants.recipes());
                }
            }
        } catch(ArithmeticException | IllegalArgumentException ex) {
            throw new ProductionPlanningException(UNSUPPORTED,"样板数量或返回物语义无法精确建模；未按缺料处理："+ex.getMessage());
        }
        // Cache only a numeric witness for this exact net and stock. Parsing is
        // intentionally repeated: changed routes, remainders, quantities and new
        // pattern instances must never inherit a stale executable allocation.
        var indexed=new ArrayList<ProductionPlanner.Recipe<AEKey,Integer>>(recipes.size());
        for(int i=0;i<recipes.size();i++) {
            var recipe=recipes.get(i);
            indexed.add(new ProductionPlanner.Recipe<>(i,recipe.inputs(),recipe.outputs()));
        }
        var searchLimits=new ProductionPlanner.Limits(limits.states(),remaining(started,limits));
        var result=cache?CACHE.solve(target,increment,indexed,snapshot,searchLimits)
                :new ProductionPlanner<>(indexed,snapshot,searchLimits).solve(target,increment);
        if(result.status()!=ProductionPlanner.Status.FEASIBLE) {
            if(incomplete) throw new ProductionPlanningException(UNSUPPORTED,"部分样板替代输入尚未穷尽；没有已验证方案，不代表缺料");
            if(result.status()==ProductionPlanner.Status.LIMIT) throw new ProductionPlanningException(LIMIT,"计算预算已用尽，尚未判定可行性；不是缺料，也未退回旧算法");
            if(result.maximumAdditionalTarget()!=null) throw new ProductionPlanningException(UNREACHABLE,
                    "资源总量不足：验证全部候选转换后，当前库存至多可净增 "+result.maximumAdditionalTarget()+" 个目标，请求为 "+increment+"；不是合成数量上限");
            throw new ProductionPlanningException(UNREACHABLE,"当前库存与已编码样板无法达到净增量目标（可能缺原料、启动材料或只有无效循环）");
        }
        var used=new KeyCounter(); result.used().forEach(used::add);
        var counts=new LinkedHashMap<IPatternDetails,Long>();
        try {
            result.counts().forEach((id,count)->counts.merge(recipes.get(id).id().pattern(),count,Math::addExact));
            var program=new ArrayList<ProductionPlanner.Block<PatternVariant>>();
            for(var block:result.program()) {
                var word=new ArrayList<ProductionPlanner.Step<PatternVariant>>();
                for(var step:block.word()) word.add(new ProductionPlanner.Step<>(recipes.get(step.recipe()).id(),step.count()));
                program.add(new ProductionPlanner.Block<>(word,block.repetitions()));
            }
            // AE2 charges one crafting byte per pattern firing, not per input slot,
            // program block, or distinct key. Match CraftingTreeProcess.addBytes(count).
            long bytes=0;
            for(var entry:counts.entrySet()) {
                // Check native task/tracker multiplication too; no long saturation.
                for(var out:entry.getKey().getOutputs()) Math.multiplyExact(Math.multiplyExact(out.amount(),entry.getValue()),out.what().getAmountPerUnit());
                bytes=Math.addExact(bytes,entry.getValue());
            }
            remaining(started,limits);
            return new CertifiedCraftingPlan(new GenericStack(target,increment),Math.max(1,bytes),used,counts,program);
        } catch(ArithmeticException ex) { throw new ProductionPlanningException(LIMIT,"计划超过 AE2 的整数容量，未提交不完整任务"); }
    }
    static ProductionPlanCache.Stats cacheStats() { return CACHE.stats(); }
    /** Read-only telemetry, also used by development-only real-grid assertions. */
    public static long cacheHits() { return CACHE.stats().hits(); }
    static void clearCache() { CACHE.clear(); }
    /** Server lifecycle boundary: cached numeric rules and proofs cannot outlive a server. */
    public static void clearPlanningCaches(){CACHE.clear();CompiledProductionGraph.clear();}
    private static long remaining(long start,ProductionPlanner.Limits limits) {
        if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        long elapsed=System.nanoTime()-start;
        if(elapsed>=limits.nanos()) throw new ProductionPlanningException(LIMIT,"规划端到端预算已用尽；尚未判定可行性，不是缺料");
        return limits.nanos()-elapsed;
    }
    private NetworkProductionPlanner() {}
}
