package com.ae2vm.addon.route;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEKey;
import net.minecraft.core.HolderLookup;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The exact pattern graph and stock that a plan is computed against.
 *
 * <p>Both the crafting request path and the canvas preview must plan against the same
 * snapshot, otherwise the canvas would show one thing and the CPU execute another. This
 * is the single place that turns a grid plus a player's route rules into that snapshot.</p>
 */
public final class RouteSnapshot {
    private static final int KEY_BUDGET = 512;

    public record Result(Map<AEKey, List<IPatternDetails>> patterns, Map<AEKey, Long> stock) {
        public Result {
            patterns = Map.copyOf(patterns);
            stock = Map.copyOf(stock);
        }
    }

    public static Result build(IGrid grid, RoutePolicy<AEKey> policy,
                               HolderLookup.Provider registries, AEKey target) {
        var service = grid.getCraftingService();
        var patterns = new LinkedHashMap<AEKey, List<IPatternDetails>>();
        var queue = new ArrayDeque<AEKey>();
        queue.add(target);
        while (!queue.isEmpty()) {
            var key = queue.removeFirst();
            if (patterns.containsKey(key)) continue;
            if (patterns.size() >= KEY_BUDGET) {
                throw new com.ae2vm.addon.planner.ProductionPlanningException(
                        com.ae2vm.addon.planner.ProductionPlanningException.Reason.LIMIT,
                        "配方图超过快照预算；不是缺料");
            }
            var allowed = new ArrayList<IPatternDetails>();
            for (var pattern : service.getCraftingFor(key)) {
                boolean permitted = true;
                for (var out : pattern.getOutputs()) {
                    if (!policy.filter(out.what(), List.of(pattern),
                            p -> RouteIdentity.pattern(p, registries)).contains(pattern)) {
                        permitted = false;
                    }
                }
                if (permitted) allowed.add(pattern);
            }
            patterns.put(key, List.copyOf(allowed));
            for (var pattern : allowed) {
                for (var input : pattern.getInputs()) {
                    for (var stack : input.getPossibleInputs()) {
                        if (stack == null) continue;
                        queue.add(stack.what());
                        var remaining = input.getRemainingKey(stack.what());
                        if (remaining != null) queue.add(remaining);
                    }
                }
                for (var out : pattern.getOutputs()) queue.add(out.what());
            }
        }
        var inventory = grid.getStorageService().getInventory().getAvailableStacks();
        var stock = new LinkedHashMap<AEKey, Long>();
        for (var key : patterns.keySet()) {
            stock.put(key, Math.max(0, inventory.get(key)));
        }
        return new Result(patterns, stock);
    }

    private RouteSnapshot() {
    }
}
