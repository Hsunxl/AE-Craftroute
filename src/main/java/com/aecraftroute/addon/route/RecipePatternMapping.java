package com.aecraftroute.addon.route;

import appeng.api.stacks.AEKey;
import com.aecraftroute.addon.planner.*;
import java.util.*;
import java.util.function.Function;

/** Candidate discovery only, after a recipe graph is supplied to execution.
 * Matching quantities do not prove machine, probability or container semantics;
 * callers must certify an exact variant before dispatch. */
public final class RecipePatternMapping {
    public record Recipe(String path, AEKey output, Map<AEKey,Long> inputs, Map<AEKey,Long> outputs,
                         appeng.api.stacks.AEItemKey expectedDefinition) {
        public Recipe(String path,AEKey output,Map<AEKey,Long> inputs,Map<AEKey,Long> outputs) {
            this(path,output,inputs,outputs,null);
        }
        public Recipe {
            Objects.requireNonNull(path);Objects.requireNonNull(output);
            inputs=Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
            outputs=Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
            if(inputs.values().stream().anyMatch(n->n<=0) || outputs.values().stream().anyMatch(n->n<=0)
                    || outputs.getOrDefault(output,0L)<=0)throw new IllegalArgumentException("Invalid recipe quantities");
        }
    }
    public record Candidates(List<PatternVariant> variants,boolean complete) {
        public Candidates {variants=List.copyOf(variants);}
    }
    private record Lookup(AEKey output,appeng.api.stacks.AEItemKey definition) {}
    private record Flow(Map<AEKey,Long> inputs,Map<AEKey,Long> outputs) {}
    private record Index(Map<Flow,List<PatternVariant>> flows,boolean complete) {}
    /** Enumerate each distinct producer group once, then resolve repeated paths by
     * exact flow lookup. This remains only candidate discovery, not a proof. */
    public static Map<String,Candidates> discover(List<Recipe> recipes,RouteSnapshot.Result snapshot,
                                                   Function<AEKey,Long> stock,long deadlineNanos) {
        return discover(recipes,snapshot,stock,deadlineNanos,()->{});
    }
    static Map<String,Candidates> discover(List<Recipe> recipes,RouteSnapshot.Result snapshot,
                                                   Function<AEKey,Long> stock,long deadlineNanos,Runnable indexBuilt) {
        var result=new LinkedHashMap<String,Candidates>();
        // Keep physical pattern identity distinct, even if a third party equals()
        // considers two providers equivalent. A numeric compilation may be reused.
        var enumerated=new IdentityHashMap<appeng.api.crafting.IPatternDetails,PatternVariants.Enumeration>();
        var indices=new HashMap<Lookup,Index>();
        for(var recipe:recipes) {
            RouteCanvasPlanner.check(deadlineNanos);
            if(result.containsKey(recipe.path()))throw new IllegalArgumentException("Duplicate recipe path: "+recipe.path());
            var lookup=new Lookup(recipe.output(),recipe.expectedDefinition());
            var index=indices.get(lookup);
            if(index==null) {
                var flows=new HashMap<Flow,List<PatternVariant>>();boolean complete=true;
                for(var pattern:snapshot.patterns().getOrDefault(recipe.output(),List.of())) {
                    RouteCanvasPlanner.check(deadlineNanos);
                    // The client cannot supply trusted definition identities: only a
                    // server-generated exact definition can establish recipe identity.
                    if(recipe.expectedDefinition()!=null && !recipe.expectedDefinition().equals(pattern.getDefinition()))continue;
                    var variants=enumerated.get(pattern);
                    if(variants==null){variants=PatternVariants.enumerate(pattern,stock,deadlineNanos);enumerated.put(pattern,variants);}
                    complete &= variants.complete();
                    for(var variant:variants.recipes()) {
                        RouteCanvasPlanner.check(deadlineNanos);
                        flows.computeIfAbsent(new Flow(variant.inputs(),variant.outputs()),ignored->new ArrayList<>()).add(variant.id());
                    }
                }
                index=new Index(flows,complete);indices.put(lookup,index);indexBuilt.run();
            }
            var found=index.flows().getOrDefault(new Flow(recipe.inputs(),recipe.outputs()),List.of());
            result.put(recipe.path(),new Candidates(found,index.complete()));
        }
        return Collections.unmodifiableMap(result);
    }
    /** Build and verify a physical witness from all mapped candidates. The caller must
     * already have validated recipe semantics and per-path minimums; JEI display
     * data alone is not a trustworthy source for these contracts. */
    public static ForcedRoutePlanner.Proof<AEKey,PatternVariant> prove(
            AEKey target,long amount,List<Recipe> recipes,Map<String,Long> minimums,
            RouteSnapshot.Result snapshot,ProductionPlanner.Limits limits) {
        long started=System.nanoTime();
        long deadline=started+Math.min(limits.nanos(),Long.MAX_VALUE/2);
        var mapped=discover(recipes,snapshot,k->snapshot.stock().getOrDefault(k,0L),deadline);
        var physical=new LinkedHashMap<PatternVariant,ProductionPlanner.Recipe<AEKey,PatternVariant>>();
        var branches=new ArrayList<ForcedRoutePlanner.Branch<PatternVariant>>();
        boolean incomplete=false;
        for(var recipe:recipes) {
            RouteCanvasPlanner.check(deadline);
            var candidates=mapped.get(recipe.path());incomplete |= !candidates.complete();
            var minimum=minimums.get(recipe.path());
            if(minimum==null || minimum<=0)throw new ProductionPlanningException(
                    ProductionPlanningException.Reason.INVALID,"配方路径缺少有效批次："+recipe.path());
            if(candidates.variants().isEmpty())throw new ProductionPlanningException(
                    candidates.complete()?ProductionPlanningException.Reason.INVALID:ProductionPlanningException.Reason.UNSUPPORTED,
                    "路径没有已验证的样板候选："+recipe.path());
            for(var variant:candidates.variants())physical.putIfAbsent(variant,variant.recipe());
            branches.add(new ForcedRoutePlanner.Branch<>(recipe.path(),new LinkedHashSet<>(candidates.variants()),minimum));
        }
        if(minimums.size()!=recipes.size())throw new ProductionPlanningException(
                ProductionPlanningException.Reason.INVALID,"配方路径与批次不一致");
        try {
            return new ForcedRoutePlanner<AEKey,PatternVariant>().solve(target,amount,List.copyOf(physical.values()),
                    snapshot.stock(),branches,new ProductionPlanner.Limits(limits.states(),RouteCanvasPlanner.remaining(deadline)));
        } catch(ProductionPlanningException failure) {
            if(incomplete && failure.reason()==ProductionPlanningException.Reason.UNREACHABLE)
                throw new ProductionPlanningException(ProductionPlanningException.Reason.UNSUPPORTED,"样板候选未穷尽；不能判定缺料");
            throw failure;
        }
    }
    private RecipePatternMapping() {}
}
