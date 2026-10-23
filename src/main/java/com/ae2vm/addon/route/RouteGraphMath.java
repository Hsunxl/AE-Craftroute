package com.ae2vm.addon.route;

import java.util.List;

/**
 * Pure route-preview rules shared by the canvas and tests.
 *
 * <p>The canvas is a preview of hard constraints, not a second planner. It may show an
 * automatic suggestion, but only {@link RouteView.Reply#locked()} is a hard choice. A
 * missing lock therefore never silently turns into a different recipe.</p>
 */
public final class RouteGraphMath {
    private RouteGraphMath() {}

    /** Visual branches merge identical ingredient slots without changing the encoded recipe grid. */
    public static List<RouteView.Stack> groupedInputs(List<RouteView.Stack> inputs) {
        var grouped=new java.util.LinkedHashMap<String,RouteView.Stack>();
        for(var input:inputs)grouped.merge(input.key(),input,(a,b)->
                new RouteView.Stack(a.key(),a.name(),Math.addExact(a.amount(),b.amount())));
        return List.copyOf(grouped.values());
    }

    /** Selected recipe for one material, plus why it is shown. */
    public record Preview(RouteView.Choice choice, boolean locked, boolean draft, String reason) {
        public boolean executable() { return choice != null && !draft; }
    }

    public static Preview preview(RouteView.Reply reply, RouteView.Choice draft) {
        if (reply == null) {
            return new Preview(draft, false, draft != null, draft == null ? "正在读取可用配方…" : "JEI 配方（仅预览）");
        }
        if (reply.stockOnly()) {
            return new Preview(null, false, false, "只用手头材料，不再制作");
        }
        if (reply.locked() != null) {
            for (var choice : reply.choices()) {
                if (reply.locked().equals(choice.id())) {
                    return new Preview(choice, true, false, "已固定");
                }
            }
            // Deliberately do not replace a missing lock with another pattern.
            return new Preview(null, true, false, "你固定的样板已不存在，请重新选择");
        }
        if (draft != null) {
            return new Preview(draft, false, true, "JEI 配方（仅预览）");
        }
        for (var choice : reply.choices()) {
            if (!reply.banned().contains(choice.id())) {
                return new Preview(choice, false, false, "系统推荐（还没固定）");
            }
        }
        if (!reply.choices().isEmpty()) {
            return new Preview(null, false, false, "这些配方都被你停用了");
        }
        return new Preview(null, false, false, "还没有可用的编码样板");
    }

    /** Amount of the requested key produced by one execution of this pattern. */
    public static long outputAmount(RouteView.Choice choice, String key) {
        if (choice == null || key == null) return 0;
        long total = 0;
        for (var stack : choice.outputs()) {
            if (key.equals(stack.key())) total = safeAdd(total, stack.amount());
        }
        return total;
    }

    /** Number of pattern executions needed for at least {@code demand} output. */
    public static long runs(long demand, long output) {
        if (output <= 0) throw new IllegalArgumentException("配方没有该材料的净产出");
        if (demand <= 0) return 1;
        return 1 + (demand - 1) / output;
    }

    /** Input amount for a number of executions. */
    public static long inputNeed(long perRun, long runs) {
        if (perRun <= 0) return 0;
        if (runs <= 0) return 0;
        return Math.multiplyExact(perRun, runs);
    }

    /** Human-readable amount for the compact canvas. */
    public static String format(long amount) {
        if (amount < 0) return "-" + format(-amount);
        if (amount < 10_000) return Long.toString(amount);
        if (amount < 1_000_000) return trim(amount / 1_000.0) + "k";
        if (amount < 1_000_000_000L) return trim(amount / 1_000_000.0) + "M";
        if (amount < 1_000_000_000_000L) return trim(amount / 1_000_000_000.0) + "G";
        if (amount < 1_000_000_000_000_000L) return trim(amount / 1_000_000_000_000.0) + "T";
        return String.format(java.util.Locale.ROOT, "%.2e", (double) amount);
    }

    private static String trim(double value) {
        return value >= 100 ? String.format(java.util.Locale.ROOT, "%.0f", value)
                : value >= 10 ? String.format(java.util.Locale.ROOT, "%.1f", value)
                : String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static long safeAdd(long a, long b) {
        try { return Math.addExact(a, b); }
        catch (ArithmeticException e) { return Long.MAX_VALUE; }
    }
}
