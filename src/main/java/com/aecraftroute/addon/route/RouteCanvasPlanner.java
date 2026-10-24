package com.aecraftroute.addon.route;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import com.aecraftroute.addon.planner.*;
import java.util.*;
import static com.aecraftroute.addon.planner.ProductionPlanningException.Reason.*;

/** Compiles server-validated finite path occurrences into an executable CPU proof. */
public final class RouteCanvasPlanner {
    public static CertifiedCraftingPlan plan(RouteSnapshot.Result snapshot,RoutePolicy.Canvas<AEKey> canvas,
            AEKey target,long amount,ProductionPlanner.Limits limits) {
        if(!Objects.equals(canvas.target(),target) || canvas.amount()!=amount)
            throw new ProductionPlanningException(INVALID,"强制画布只适用于应用时的目标与数量；请重新应用画布");
        long deadline=System.nanoTime()+limits.nanos();
        var all=new LinkedHashSet<IPatternDetails>();
        for(var list:snapshot.patterns().values()) for(var p:list) {
            try {com.aecraftroute.addon.compat.ScaledPatternCompat.validate(p);}
            catch(IllegalArgumentException invalid) {throw new ProductionPlanningException(UNSUPPORTED,"样板包装无法安全解析："+invalid.getMessage());}
            all.add(p);
        }
        var byIdentity=snapshot.identities();
        var selected=new LinkedHashMap<String,IPatternDetails>();
        var paths=new LinkedHashMap<String,RoutePolicy.Path<AEKey>>();
        for(var path:canvas.paths()) {
            if(path.path()==null || paths.putIfAbsent(path.path(),path)!=null) throw new ProductionPlanningException(INVALID,"画布路径重复或缺失");
            var match=snapshot.patterns().getOrDefault(path.key(),List.of()).stream()
                    .filter(p->path.choiceId().equals(byIdentity.get(p))).findFirst().orElseThrow(()->new ProductionPlanningException(INVALID,"路径 "+path.path()+" 的样板已失效或被规则禁止"));
            selected.put(path.path(),match);
        }
        var root=paths.get("root");
        if(root==null || !target.equals(root.key())) throw new ProductionPlanningException(INVALID,"必须包含最终产物的 root 路径");
        // Infer every demand from native pattern quantities, never from client-supplied numbers.
        var runs=new LinkedHashMap<String,Long>(); var demands=new LinkedHashMap<String,Long>(); demands.put("root",amount);
        var sorted=new ArrayList<>(paths.values()); sorted.sort(Comparator.comparingInt(p->p.path().length()));
        for(var path:sorted) {
            check(deadline);
            Long demand=demands.get(path.path());
            if(demand==null) throw new ProductionPlanningException(INVALID,"孤立路径："+path.path()+"；请展开并应用其父配方");
            var pattern=selected.get(path.path()); long output=0;
            for(var out:pattern.getOutputs()) if(out.what().equals(path.key())) output=Math.addExact(output,out.amount());
            if(output<=0) throw new ProductionPlanningException(INVALID,"路径样板不产出对应材料");
            long count=1+((demand-1)/output); runs.put(path.path(),count);
            var grouped=primaryInputs(pattern); int index=0;
            for(var input:grouped.entrySet()) {
                // v3 protocol uses input group index, not client SNBT hash text.
                String child=RouteGraphMath.childPath(path.path(),index++);
                var chosenChild=paths.get(child);
                if(chosenChild!=null && !chosenChild.key().equals(input.getKey())) throw new ProductionPlanningException(INVALID,"路径输入材料已变化："+child);
                demands.put(child,Math.multiplyExact(input.getValue(),count));
            }
        }
        var allowedForKey=new LinkedHashMap<AEKey,Set<String>>();
        for(var path:paths.values()) allowedForKey.computeIfAbsent(path.key(),k->new HashSet<>()).add(path.choiceId());
        var recipes=new ArrayList<ProductionPlanner.Recipe<AEKey,PatternVariant>>(); boolean incomplete=false;
        for(var p:all) {
            check(deadline); boolean allowed=true;
            for(var out:p.getOutputs()) {
                var ids=allowedForKey.get(out.what()); if(ids!=null && !ids.contains(byIdentity.get(p))) allowed=false;
            }
            if(!allowed) continue;
            var variants=PatternVariants.enumerate(p,k->snapshot.stock().getOrDefault(k,0L),deadline);
            recipes.addAll(variants.recipes()); incomplete|=!variants.complete();
            if(recipes.size()>8192) throw new ProductionPlanningException(LIMIT,"强制画布替代分配超过预算");
        }
        var branches=new ArrayList<ForcedRoutePlanner.Branch<PatternVariant>>();
        for(var path:paths.values()) {
            var pattern=selected.get(path.path());
            var variants=new LinkedHashSet<PatternVariant>();
            // The canvas shows one exact input allocation. Do not satisfy a selected
            // child by silently consuming another fuzzy alternative in its parent.
            var inputs=primaryInputs(pattern);
            for(var r:recipes) if(r.id().pattern()==pattern && r.inputs().equals(inputs)) variants.add(r.id());
            if(variants.isEmpty()) throw new ProductionPlanningException(UNSUPPORTED,"路径 "+path.path()+" 的精确输入分配无法建模");
            branches.add(new ForcedRoutePlanner.Branch<>(path.path(),variants,runs.get(path.path())));
        }
        ForcedRoutePlanner.Proof<AEKey,PatternVariant> proof;
        try {
            proof=new ForcedRoutePlanner<AEKey,PatternVariant>().solve(target,amount,recipes,snapshot.stock(),branches,
                    new ProductionPlanner.Limits(limits.states(),remaining(deadline)));
        } catch(ProductionPlanningException failure) {
            if(incomplete && failure.reason()==UNREACHABLE) throw new ProductionPlanningException(UNSUPPORTED,"强制画布的替代分配未穷尽；没有证明缺料");
            throw failure;
        }
        check(deadline);
        var used=new KeyCounter(); proof.used().forEach(used::add);
        var counts=new LinkedHashMap<IPatternDetails,Long>();
        proof.counts().forEach((p,n)->counts.merge(p.pattern(),n,Math::addExact));
        long bytes=Math.max(1,Math.multiplyExact(8L,proof.program().size()+counts.size()+used.size()));
        for(var e:counts.entrySet()) {
            bytes=Math.addExact(bytes,Math.multiplyExact(e.getValue(),e.getKey().getInputs().length+1L));
            for(var out:e.getKey().getOutputs()) Math.multiplyExact(Math.multiplyExact(out.amount(),e.getValue()),out.what().getAmountPerUnit());
        }
        return new CertifiedCraftingPlan(new GenericStack(target,amount),bytes,used,counts,proof.program());
    }
    static LinkedHashMap<AEKey,Long> primaryInputs(IPatternDetails p) {
        var inputs=new LinkedHashMap<AEKey,Long>();
        for(var in:p.getInputs()) {
            var options=in.getPossibleInputs();
            if(options==null || options.length==0 || options[0]==null) throw new ProductionPlanningException(UNSUPPORTED,"样板没有可显示的精确输入");
            inputs.merge(options[0].what(),Math.multiplyExact(options[0].amount(),in.getMultiplier()),Math::addExact);
        }
        return inputs;
    }
    static long remaining(long deadline) { check(deadline); return Math.max(1,deadline-System.nanoTime()); }
    static void check(long deadline) {
        if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        if(System.nanoTime()>=deadline) throw new ProductionPlanningException(LIMIT,"端到端规划预算已用尽；不是缺料");
    }
    private RouteCanvasPlanner() {}
}
