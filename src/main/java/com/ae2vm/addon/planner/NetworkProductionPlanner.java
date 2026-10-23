package com.ae2vm.addon.planner;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import java.util.*;
import java.util.function.Function;
import static com.ae2vm.addon.planner.ProductionPlanningException.Reason.*;

/** Finite network snapshot -> exact transitions -> certified plan. No bytecode cache,
 * chosen-primary-only search, stock correction or native fallback participates. */
public final class NetworkProductionPlanner {
    public static CertifiedCraftingPlan plan(AEKey target,long increment,
            Function<AEKey,? extends Collection<IPatternDetails>> source,Function<AEKey,Long> stock) {
        return plan(target, increment, source, stock, ProductionPlanner.Limits.defaults());
    }

    /** Same planning, with an explicit budget. UI previews use a short budget so a tick never stalls. */
    public static CertifiedCraftingPlan plan(AEKey target,long increment,
            Function<AEKey,? extends Collection<IPatternDetails>> source,Function<AEKey,Long> stock,
            ProductionPlanner.Limits limits) {
        if(target==null || increment<=0) throw new ProductionPlanningException(INVALID,"净增量必须大于零");
        var queue=new ArrayDeque<AEKey>(); queue.add(target);
        var visited=new LinkedHashSet<AEKey>(); var patterns=new LinkedHashSet<IPatternDetails>();
        var recipes=new ArrayList<ProductionPlanner.Recipe<AEKey,PatternVariant>>();
        var snapshot=new LinkedHashMap<AEKey,Long>();
        boolean incomplete=false;
        try {
            while(!queue.isEmpty()) {
                if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                var key=queue.remove(); if(!visited.add(key)) continue;
                if(visited.size()>512) throw new ProductionPlanningException(LIMIT,"配方图超过计算预算；尚未判定是否可合成，不是缺料");
                snapshot.put(key,Math.max(0,stock.apply(key)));
                var choices=source.apply(key);
                if(choices==null) continue;
                for(var pattern:choices) if(patterns.add(pattern)) {
                    if(patterns.size()>2048) throw new ProductionPlanningException(LIMIT,"候选样板超过计算预算；不是缺料");
                    var slots=new ArrayList<List<PatternVariant.Slot>>(); boolean supported=true;
                    for(var input:pattern.getInputs()) {
                        if(input instanceof com.ae2vm.addon.compiler.IFiniteUseInput finite && finite.durabilityUses()!=Long.MAX_VALUE) {
                            supported=false; break; // Durability is not an unchanged monotone key.
                        }
                        var options=input.getPossibleInputs();
                        if(options==null || options.length==0 || input.getMultiplier()<=0) { supported=false; break; }
                        var distinct=new LinkedHashSet<GenericStack>();
                        for(var option:options) if(option!=null && option.what()!=null && option.amount()>0) {
                            distinct.add(option); queue.add(option.what());
                            var remaining=input.getRemainingKey(option.what()); if(remaining!=null) queue.add(remaining);
                        }
                        if(distinct.isEmpty()) { supported=false; break; }
                        // Whole-slot variants are exact executable transitions. A mixed
                        // multiplier allocation is not exhaustive: failure stays UNKNOWN.
                        var allocations=new ArrayList<PatternVariant.Slot>();
                        for(var option:distinct) allocations.add(new PatternVariant.Slot(List.of(new PatternVariant.Portion(option,input.getMultiplier()))));
                        if(distinct.size()>1 && input.getMultiplier()>1) {
                            if(input.getMultiplier()>64 || distinct.size()>16) incomplete=true;
                            else {
                                var mixed=new ArrayList<PatternVariant.Slot>();
                                allocate(List.copyOf(distinct),0,input.getMultiplier(),new ArrayList<>(),mixed);
                                if(mixed.size()>4096) incomplete=true;
                                else allocations=mixed;
                            }
                        }
                        slots.add(List.copyOf(allocations));
                    }
                    for(var out:pattern.getOutputs()) if(out!=null && out.what()!=null) {
                        // Include byproduct keys and their alternate producers too.
                        queue.add(out.what());
                    }
                    if(!supported) { incomplete=true; continue; }
                    long combinations=1;
                    for(var slot:slots) { combinations=Math.multiplyExact(combinations,slot.size()); if(combinations>4096) break; }
                    if(combinations>4096 || recipes.size()+combinations>8192) { incomplete=true; continue; }
                    enumerate(pattern,slots,0,new ArrayList<>(),recipes);
                }
            }
        } catch(ArithmeticException | IllegalArgumentException ex) {
            throw new ProductionPlanningException(UNSUPPORTED,"样板数量或返回物语义无法精确建模；未按缺料处理："+ex.getMessage());
        }
        var result=new ProductionPlanner<>(recipes,snapshot,limits).solve(target,increment);
        if(result.status()!=ProductionPlanner.Status.FEASIBLE) {
            if(incomplete) throw new ProductionPlanningException(UNSUPPORTED,"部分样板替代输入尚未穷尽；没有已验证方案，不代表缺料");
            if(result.status()==ProductionPlanner.Status.LIMIT) throw new ProductionPlanningException(LIMIT,"计算预算已用尽，尚未判定可行性；不是缺料，也未退回旧算法");
            throw new ProductionPlanningException(UNREACHABLE,"当前库存与已编码样板无法达到净增量目标（可能缺原料、启动材料或只有无效循环）");
        }
        var used=new KeyCounter(); result.used().forEach(used::add);
        var counts=new LinkedHashMap<IPatternDetails,Long>();
        try {
            result.counts().forEach((variant,count)->counts.merge(variant.pattern(),count,Math::addExact));
            long bytes=Math.max(1,Math.multiplyExact(8L,Math.addExact(result.program().size(),counts.size()+used.size())));
            for(var entry:counts.entrySet()) {
                // Check native task/tracker multiplication too; no long saturation.
                for(var out:entry.getKey().getOutputs()) Math.multiplyExact(Math.multiplyExact(out.amount(),entry.getValue()),out.what().getAmountPerUnit());
                bytes=Math.addExact(bytes,Math.multiplyExact(entry.getValue(),entry.getKey().getInputs().length+1L));
            }
            return new CertifiedCraftingPlan(new GenericStack(target,increment),bytes,used,counts,result.program());
        } catch(ArithmeticException ex) { throw new ProductionPlanningException(LIMIT,"计划超过 AE2 的整数容量，未提交不完整任务"); }
    }
    private static void enumerate(IPatternDetails p,List<List<PatternVariant.Slot>> slots,int at,List<PatternVariant.Slot> chosen,
            List<ProductionPlanner.Recipe<AEKey,PatternVariant>> result) {
        if(at==slots.size()) { result.add(new PatternVariant(p,chosen).recipe()); return; }
        for(var stack:slots.get(at)) { chosen.add(stack); enumerate(p,slots,at+1,chosen,result); chosen.removeLast(); }
    }
    private static void allocate(List<GenericStack> options,int at,long remaining,List<PatternVariant.Portion> chosen,
            List<PatternVariant.Slot> result) {
        if(result.size()>4096) return;
        if(at==options.size()) { if(remaining==0) result.add(new PatternVariant.Slot(chosen)); return; }
        if(at==options.size()-1) {
            if(remaining>0) chosen.add(new PatternVariant.Portion(options.get(at),remaining));
            result.add(new PatternVariant.Slot(chosen));
            if(remaining>0) chosen.removeLast();
            return;
        }
        for(long count=0;count<=remaining && result.size()<=4096;count++) {
            if(count>0) chosen.add(new PatternVariant.Portion(options.get(at),count));
            allocate(options,at+1,remaining-count,chosen,result);
            if(count>0) chosen.removeLast();
        }
    }
    private NetworkProductionPlanner() {}
}
