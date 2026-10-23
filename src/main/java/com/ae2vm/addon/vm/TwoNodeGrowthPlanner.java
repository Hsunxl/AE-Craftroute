package com.ae2vm.addon.vm;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Recognises the exact two-node growth ring {@code P: d D + externals -> q R, Q: 1 R -> 1 D, q > d}
 * and keeps AE2's dispatch order compatible with it.
 *
 * <p>Crafting plans themselves come from the certified planner; this class only answers
 * two narrow questions about the executor:</p>
 * <ul>
 *   <li>{@link #containsRing} — does this pattern set contain such a ring, i.e. must the CPU
 *       reserve returned items instead of delivering them straight away?</li>
 *   <li>{@link #prioritizeFeedback} — reorder the dispatched tasks so a recycler runs before
 *       ordinary consumers of its feedback key.</li>
 * </ul>
 *
 * <p>The ordering matters because native {@code ExecutingCraftingJob} uses a HashMap, whose
 * arbitrary iteration can starve a loop nested under e.g. four 32-crystal device crafts.</p>
 */
public final class TwoNodeGrowthPlanner {
    private record Line(IPatternDetails pattern, AEKey output, long amount, Map<AEKey, Long> inputs) {}

    private record Ring(Line production, Line recycle, AEKey dust, long input) {}

    private TwoNodeGrowthPlanner() {
    }

    private static Line line(IPatternDetails p) {
        if (p == null || p.getOutputs() == null || p.getOutputs().size() != 1) return null;
        var out = p.getPrimaryOutput();
        if (out == null || out.what() == null || out.amount() <= 0) return null;
        Map<AEKey, Long> inputs = new LinkedHashMap<>();
        if (p.getInputs() == null) return null;
        for (var in : p.getInputs()) {
            var choices = in.getPossibleInputs();
            // A crafting pattern with canSubstitute=true may expose an entire tag.
            // Account conservatively for the encoded primary, which is an actual
            // valid input. Do NOT abort traversal before visiting its producers.
            // The emitted task remains the original pattern, not a synthetic one.
            if (choices == null || choices.length == 0 || choices[0] == null) return null;
            var s = choices[0];
            if (s.what() == null || s.amount() <= 0 || in.getMultiplier() <= 0
                    || in.getRemainingKey(s.what()) != null) return null;
            inputs.merge(s.what(), Math.multiplyExact(s.amount(), in.getMultiplier()), Math::addExact);
        }
        return new Line(p, out.what(), out.amount(), inputs);
    }

    private static Ring find(IPatternDetails p, java.util.function.Function<AEKey, IPatternDetails> resolver) {
        Line production = line(p);
        if (production == null || production.inputs.size() < 2
                || production.inputs.containsKey(production.output)) return null;
        Ring found = null;
        for (var e : production.inputs.entrySet()) {
            Line back = line(resolver.apply(e.getKey()));
            if (back == null || !back.output.equals(e.getKey()) || back.amount != 1 || back.inputs.size() != 1
                    || back.inputs.getOrDefault(production.output, 0L) != 1
                    || production.amount <= e.getValue()) continue;
            if (found != null) return null; // ambiguous rings: leave to the original engine
            found = new Ring(production, back, e.getKey(), e.getValue());
        }
        return found;
    }

    /** True when the pattern set contains a two-node growth ring (production + recycler). */
    public static boolean containsRing(Collection<IPatternDetails> patterns) {
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        for (var p : patterns) {
            if (p != null && p.getPrimaryOutput() != null) byOutput.put(p.getPrimaryOutput().what(), p);
        }
        try {
            for (var p : patterns) {
                if (find(p, byOutput::get) != null) return true;
            }
        } catch (ArithmeticException ignored) {
            // An overflowing pattern cannot be a ring we can honour; treat as absent.
        }
        return false;
    }

    /** Recyclers must dispatch before ordinary consumers of their feedback key. */
    public static Map<IPatternDetails, ?> prioritizeFeedback(Map<IPatternDetails, ?> tasks) {
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        tasks.keySet().forEach(p -> byOutput.put(p.getPrimaryOutput().what(), p));
        Map<IPatternDetails, Object> ordered = new LinkedHashMap<>();
        for (var p : tasks.keySet()) {
            Ring r = find(p, byOutput::get);
            if (r != null && tasks.containsKey(r.recycle.pattern)) {
                ordered.put(r.recycle.pattern, tasks.get(r.recycle.pattern));
            }
        }
        tasks.forEach(ordered::put);
        return ordered;
    }
}
