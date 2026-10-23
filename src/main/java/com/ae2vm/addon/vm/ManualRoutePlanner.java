package com.ae2vm.addon.vm;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.*;
import appeng.crafting.inv.CraftingSimulationState;
import com.ae2vm.addon.api.AE2VMCrafting;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.route.*;
import java.util.*;
import java.util.function.Function;

/** Isolated planning path: no shared warm plan, fuzzy fallback or native fallback. */
public final class ManualRoutePlanner {
    public static Map<AEKey, List<IPatternDetails>> capture(AEKey root, RoutePolicy<AEKey> policy,
            Function<AEKey, ? extends Collection<IPatternDetails>> source, Function<IPatternDetails, String> identity) {
        var result = new HashMap<AEKey, List<IPatternDetails>>();
        var queue = new ArrayDeque<AEKey>(); queue.add(root);
        int edges = 0;
        while (!queue.isEmpty()) {
            AEKey key = queue.removeFirst();
            if (result.containsKey(key)) continue;
            if (result.size() >= 256) throw new RoutePlanningException("手动路线超过 256 个材料，请拆分任务");
            var candidates = source.apply(key);
            if (candidates.size() > 128) throw new RoutePlanningException("单个材料样板过多，请先整理网络");
            var allowed = policy.filter(key, candidates, identity).stream()
                    .filter(p -> p.getPrimaryOutput() != null && key.equals(p.getPrimaryOutput().what())).toList();
            if (policy.rule(key).locked() != null && allowed.isEmpty())
                throw new RoutePlanningException("锁定样板已移除或更改：" + key.getDisplayName().getString());
            result.put(key, allowed);
            for (var p : allowed) for (var input : p.getInputs()) for (var stack : input.getPossibleInputs()) {
                if (++edges > 8192) throw new RoutePlanningException("手动路线输入组合过多，请拆分任务");
                if (stack != null && !result.containsKey(stack.what())) queue.addLast(stack.what());
            }
        }
        return Map.copyOf(result);
    }
    public static ICraftingPlan plan(AEKey root, long amount, Map<AEKey, List<IPatternDetails>> candidates,
            Function<AEKey, Long> stock, CraftingSimulationState state) {
        return com.ae2vm.addon.planner.NetworkProductionPlanner.plan(root,amount,
                key->candidates.getOrDefault(key,List.of()),stock);
    }
}
