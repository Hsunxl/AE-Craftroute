package com.aecraftroute.addon.route;

import appeng.api.networking.IGrid;
import appeng.api.stacks.AEKey;
import com.aecraftroute.addon.planner.ProductionPlanner;
import net.minecraft.core.HolderLookup;

import java.util.ArrayList;
import java.util.List;

/**
 * Whole-canvas apply, kept out of the packet handler so the exact same code can be
 * exercised against a real grid (the self-test harness) instead of only through a GUI.
 *
 * <p>Steps, in order: decode each node's material, verify the chosen pattern still exists
 * in this network, merge into per-material locks (reporting conflicts instead of picking
 * one), then plan against the merged rules and return the authoritative verdict.</p>
 */
public final class RouteApply {
    /** One canvas node, still in wire form (SNBT key text + pattern identity). */
    public record Node(String keyTag, String choiceId, String label, String path,RouteView.Draft draft) {
        public Node(String keyTag,String choiceId,String label,String path){this(keyTag,choiceId,label,path,null);}
        public Node(String keyTag,String choiceId,String label) {this(keyTag,choiceId,label,null,null);}
    }

    public record Outcome(RoutePlanView plan, RoutePolicy<AEKey> policy,
                          List<String> locked, List<String> problems) {
        public Outcome {
            locked = List.copyOf(locked);
            problems = List.copyOf(problems);
        }
    }

    public record Prepared(RoutePolicy<AEKey> policy,List<String> locked,List<String> problems) {
        public Prepared {locked=List.copyOf(locked);problems=List.copyOf(problems);}
    }
    /** Validation only: no session state is changed until a certified plan commits. */
    public static Prepared prepare(IGrid grid,RoutePolicy<AEKey> base,HolderLookup.Provider registries,
                                    AEKey target,long amount,List<Node> nodes) {
        return prepare(grid,base,registries,target,amount,nodes,null);
    }
    public static Prepared prepare(IGrid grid,RoutePolicy<AEKey> base,HolderLookup.Provider registries,
                                    AEKey target,long amount,List<Node> nodes,net.minecraft.server.level.ServerPlayer player) {
        var paths=new ArrayList<RoutePolicy.Path<AEKey>>();var locked=new ArrayList<String>();var problems=new ArrayList<String>();
        // A canvas can contain many occurrences of the same recipe. Server validation
        // is independent of the path, so do it once per exact target/draft per request.
        var verified=new java.util.HashMap<java.util.List<Object>,RecipePatternMapping.Recipe>();
        for(var node:nodes) {
            String label=node.label()==null?node.path():node.label();
            AEKey material=null;
            String decodeFailure=null;
            try {if(node.keyTag()!=null)material=RouteView.decodeKey(node.keyTag(),registries);}
            catch(Exception failure){
                Throwable cause=failure;
                while(cause.getCause()!=null)cause=cause.getCause();
                decodeFailure=cause.getClass().getSimpleName()+": "+cause.getMessage();
            }
            final AEKey resolved=material;
            if(material==null || node.choiceId()==null || node.choiceId().isBlank()) {
                problems.add("「"+label+"」材料或样板标识无效"+(decodeFailure==null?"":"（"+decodeFailure+"）"));continue;}
            if(!RouteGraphMath.validPath(node.path())) {problems.add("「"+label+"」缺少有效路径，请重新打开画布");continue;}
            RecipePatternMapping.Recipe recipe=null;
            if(node.draft()!=null) {
                try {
                    if(player==null || !node.choiceId().equals("jei:"+node.draft().recipeId()))throw new IllegalArgumentException("配方身份不一致");
                    java.util.List<Object> cacheKey=java.util.List.of(resolved,node.draft());
                    var template=verified.get(cacheKey);
                    if(template==null) {template=RoutePatternEncoder.authoritativeCrafting(player,node.path(),resolved,node.draft());verified.put(cacheKey,template);}
                    recipe=new RecipePatternMapping.Recipe(node.path(),resolved,template.inputs(),template.outputs(),template.expectedDefinition());
                } catch(RuntimeException failure) {problems.add("「"+label+"」配方未通过服务端验证："+failure.getMessage());continue;}
            } else if(node.choiceId().startsWith("jei:")) {problems.add("「"+label+"」缺少配方契约");continue;}
            paths.add(new RoutePolicy.Path<>(node.path(),resolved,node.choiceId(),recipe,node.draft()));locked.add(node.label()==null?node.path():node.label()+" @ "+node.path());
        }
        if(paths.isEmpty())problems.add("画布没有可执行的路径分支");
        var candidate=problems.isEmpty()?new RoutePolicy<>(base.rules(),new RoutePolicy.Canvas<>(target,amount,paths)):base;
        return new Prepared(candidate,locked,problems);
    }
    public static Outcome apply(IGrid grid, RoutePolicy<AEKey> base, HolderLookup.Provider registries,
                                AEKey target, long amount, List<Node> nodes, ProductionPlanner.Limits limits) {
        if(nodes.stream().anyMatch(n->n.path()!=null)) {
            var candidate=prepare(grid,base,registries,target,amount,nodes);
            if(!candidate.problems().isEmpty())return new Outcome(RoutePlanView.error(String.join("；",candidate.problems()),amount),base,List.of(),candidate.problems());
            try {
                var plan=RoutePlanView.of(com.aecraftroute.addon.api.CraftRouteCrafting.planNow(grid,candidate.policy(),registries,target,amount,limits),amount,
                        (k,a)->RouteView.Stack.of(k,a,registries));
                return new Outcome(plan,candidate.policy(),candidate.locked(),List.of());
            } catch(com.aecraftroute.addon.planner.ProductionPlanningException error) {
                return new Outcome(RoutePlanView.of(error,amount),base,List.of(),List.of("未应用任何分支：程序尚未通过证明"));
            }
        }
        var resolved = new ArrayList<RouteIntent.Node>();
        for (var node : nodes) {
            AEKey material = null;
            if (node.keyTag() != null) {
                try {
                    material = RouteView.decodeKey(node.keyTag(), registries);
                } catch (Exception undecodable) {
                    material = null; // reported as a stale/unknown node below
                }
            }
            boolean exists = material != null && node.choiceId() != null
                    && grid.getCraftingService().getCraftingFor(material).stream()
                    .anyMatch(p -> RouteIdentity.pattern(p, registries).equals(node.choiceId()));
            resolved.add(new RouteIntent.Node(material, node.label(), node.choiceId(), exists));
        }
        var merged = RouteIntent.merge(base, resolved);
        RoutePlanView plan;
        if (target == null) {
            plan = RoutePlanView.error("先选择目标材料", Math.max(1, amount));
        } else {
            try {
                plan = RoutePlanView.of(com.aecraftroute.addon.api.CraftRouteCrafting.planNow(
                                grid, merged.policy(), registries, target, Math.max(1, amount), limits),
                        Math.max(1, amount), (k, a) -> RouteView.Stack.of(k, a, registries));
            } catch (com.aecraftroute.addon.planner.ProductionPlanningException failure) {
                plan = RoutePlanView.of(failure, Math.max(1, amount));
            }
        }
        return new Outcome(plan, merged.policy(), merged.locked(), merged.problems());
    }

    private RouteApply() {
    }
}
