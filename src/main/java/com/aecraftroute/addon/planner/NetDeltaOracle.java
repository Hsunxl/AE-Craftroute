package com.aecraftroute.addon.planner;

import java.math.BigInteger;
import java.util.*;

/**
 * Independent stoichiometric net-gain oracle, used only to cross-check answers
 * of {@link ProductionPlanner}. It pure-data computes, for one target key,
 * {@code emitted + sum_r times[r] * (out_r(target) - in_r(target))}.
 *
 * <p>It never participates in pruning, ordering or solve/probe/certify behaviour.
 * It ignores stock feasibility, prefix capital and execution order, so it is a
 * LOWER-BOUND-style net check only: a positive net does not prove that the
 * program is executable, and a zero or negative net does not disprove it.
 * Final feasibility is always decided by the planner's certify() replay.
 */
public final class NetDeltaOracle {
    private NetDeltaOracle() {}

    /** Net change of {@code target} per single execution of {@code recipe}; absent keys count as 0. */
    public static <K,R> long netGain(ProductionPlanner.Recipe<K,R> recipe, K target) {
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(target, "target");
        long out = recipe.outputs().getOrDefault(target, 0L);
        long in = recipe.inputs().getOrDefault(target, 0L);
        return Math.subtractExact(out, in);
    }

    /**
     * Net target change under the given run counts. Recipes absent from {@code times}
     * contribute 0. Throws IllegalArgumentException for duplicate recipe ids, unknown
     * recipe ids in {@code times}, negative run counts or a negative {@code emitted}.
     */
    public static <K,R> BigInteger net(Collection<ProductionPlanner.Recipe<K,R>> recipes,
                                       Map<R,Long> times, K target, long emitted) {
        Objects.requireNonNull(recipes, "recipes");
        Objects.requireNonNull(times, "times");
        Objects.requireNonNull(target, "target");
        if (emitted < 0) throw new IllegalArgumentException("Negative emitted");
        var byId = new HashMap<R, ProductionPlanner.Recipe<K,R>>();
        for (var recipe : recipes) {
            Objects.requireNonNull(recipe, "recipe");
            if (byId.put(recipe.id(), recipe) != null) throw new IllegalArgumentException("Duplicate recipe id");
        }
        BigInteger total = BigInteger.valueOf(emitted);
        for (var entry : times.entrySet()) {
            var recipe = byId.get(entry.getKey());
            if (recipe == null) throw new IllegalArgumentException("Unknown recipe id in times");
            long count = Objects.requireNonNull(entry.getValue(), "run count");
            if (count < 0) throw new IllegalArgumentException("Negative run count");
            total = total.add(BigInteger.valueOf(netGain(recipe, target)).multiply(BigInteger.valueOf(count)));
        }
        return total;
    }

    /** True when the oracle's net target change reaches {@code requiredGain}. A false answer is not a proof of infeasibility. */
    public static <K,R> boolean sufficient(Collection<ProductionPlanner.Recipe<K,R>> recipes,
                                           Map<R,Long> times, K target, long emitted, long requiredGain) {
        if (requiredGain < 0) throw new IllegalArgumentException("Negative required gain");
        return net(recipes, times, target, emitted).compareTo(BigInteger.valueOf(requiredGain)) >= 0;
    }
}
