package com.ae2vm.addon.route;

import appeng.api.networking.IGrid;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.planner.ProductionPlanner;
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
    public record Node(String keyTag, String choiceId, String label) {
    }

    public record Outcome(RoutePlanView plan, RoutePolicy<AEKey> policy,
                          List<String> locked, List<String> problems) {
        public Outcome {
            locked = List.copyOf(locked);
            problems = List.copyOf(problems);
        }
    }

    public static Outcome apply(IGrid grid, RoutePolicy<AEKey> base, HolderLookup.Provider registries,
                                AEKey target, long amount, List<Node> nodes, ProductionPlanner.Limits limits) {
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
                plan = RoutePlanView.of(com.ae2vm.addon.api.AE2VMCrafting.planNow(
                                grid, merged.policy(), registries, target, Math.max(1, amount), limits),
                        Math.max(1, amount), (k, a) -> RouteView.Stack.of(k, a, registries));
            } catch (com.ae2vm.addon.planner.ProductionPlanningException failure) {
                plan = RoutePlanView.of(failure, Math.max(1, amount));
            }
        }
        return new Outcome(plan, merged.policy(), merged.locked(), merged.problems());
    }

    private RouteApply() {
    }
}
