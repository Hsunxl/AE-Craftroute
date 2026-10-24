package com.aecraftroute.addon.route;

import appeng.api.stacks.AEKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Turns "the recipes I picked on the canvas" into route rules the server may apply.
 *
 * <p>The canvas is a picture; the server only accepts constraints it can prove. Two
 * branches that need the same material with different recipes cannot both be expressed by
 * a per-material lock, so that case is reported as a conflict instead of silently keeping
 * one of them. Stale picks (the pattern is gone) are reported the same way: the player is
 * told which node is wrong, not handed a different plan.</p>
 */
public final class RouteIntent {
    /** One canvas node: the material, how it is labelled, the chosen pattern, and whether it still exists. */
    public record Node(AEKey key, String label, String choiceId, boolean choiceExists) {
    }

    public record Result(RoutePolicy<AEKey> policy, List<String> locked, List<String> problems) {
        public Result {
            locked = List.copyOf(locked);
            problems = List.copyOf(problems);
        }

        public boolean clean() {
            return problems.isEmpty();
        }
    }

    public static Result merge(RoutePolicy<AEKey> base, List<Node> nodes) {
        var byKey = new LinkedHashMap<AEKey, List<Node>>();
        for (var node : nodes) {
            if (node.key() == null || node.choiceId() == null || node.choiceId().isBlank()) continue;
            byKey.computeIfAbsent(node.key(), k -> new ArrayList<>()).add(node);
        }
        var locked = new ArrayList<String>();
        var problems = new ArrayList<String>();
        var policy = base;
        for (var entry : byKey.entrySet()) {
            var distinct = new LinkedHashSet<String>();
            for (var node : entry.getValue()) distinct.add(node.choiceId());
            String label = labelOf(entry.getValue());
            if (distinct.size() > 1) {
                problems.add("「" + label + "」在画布上有 " + distinct.size()
                        + " 张不同样板；按材料锁定无法同时表达，请先统一其中一张");
                continue;
            }
            var node = entry.getValue().get(0);
            if (!node.choiceExists()) {
                problems.add("「" + label + "」选中的样板已不在这个网络里，请重新选择");
                continue;
            }
            policy = policy.change(entry.getKey(), "lock", node.choiceId());
            locked.add(label);
        }
        return new Result(policy, locked, problems);
    }

    private static String labelOf(List<Node> nodes) {
        for (var node : nodes) {
            if (node.label() != null && !node.label().isBlank()) return node.label();
        }
        return String.valueOf(nodes.get(0).key());
    }

    private RouteIntent() {
    }
}
