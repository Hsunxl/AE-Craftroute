package com.aecraftroute.addon.planner;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;

/** Optimistic presence-only closure for constructive hints, not a quantity proof.
 * If an input cannot ever be present even with unlimited reuse of existing keys,
 * its recipe cannot execute. A reachable recipe may still lack quantities or
 * working capital; callers must retain their normal executable-witness checks.
 */
final class SeedReachability {
    /** Returns null on deadline/cancellation predicate; never returns a partial mask. */
    static boolean[] reachableRecipes(CompiledProductionGraph graph, BigInteger[] stock,
                                      BooleanSupplier expired) {
        if (expired.getAsBoolean()) return null;
        if (stock.length != graph.keys) throw new IllegalArgumentException("Stock dimensions");
        var consumers = new ArrayList<ArrayList<Integer>>(graph.keys);
        var available = new boolean[graph.keys];
        var pending = new ArrayDeque<Integer>();
        for (int k = 0; k < graph.keys; k++) {
            if (expired.getAsBoolean()) return null;
            consumers.add(new ArrayList<>());
            if (stock[k].signum() > 0) { available[k] = true; pending.add(k); }
        }
        var remaining = new int[graph.recipes];
        var reachable = new boolean[graph.recipes];
        for (int r = 0; r < graph.recipes; r++) {
            if (expired.getAsBoolean()) return null;
            remaining[r] = graph.inputKeys[r].length;
            for (int k : graph.inputKeys[r]) {
                if (expired.getAsBoolean()) return null;
                consumers.get(k).add(r);
            }
            if (remaining[r] == 0) {
                reachable[r] = true;
                for (int k : graph.outputKeys[r]) {
                    if (expired.getAsBoolean()) return null;
                    if (!available[k]) { available[k] = true; pending.add(k); }
                }
            }
        }
        while (!pending.isEmpty()) {
            if (expired.getAsBoolean()) return null;
            int key = pending.remove();
            for (int r : consumers.get(key)) {
                if (expired.getAsBoolean()) return null;
                if (--remaining[r] != 0) continue;
                reachable[r] = true;
                for (int k : graph.outputKeys[r]) {
                    if (expired.getAsBoolean()) return null;
                    if (!available[k]) { available[k] = true; pending.add(k); }
                }
            }
        }
        return expired.getAsBoolean() ? null : reachable;
    }
    private SeedReachability() {}
}
