package com.ae2vm.addon.route;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.planner.CertifiedCraftingPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-computed verdict for "what would this request actually cost?".
 *
 * <p>This exists because the canvas used to divide the demand by one recipe run and print
 * the result as if it had to be present up front: for 1000 data dust it showed 256 dust in
 * red even though one aggregator batch (8 dust) is enough to start the loop. The verdict
 * here comes from the certified plan, whose {@code usedItems} is the proven startup capital
 * and whose pattern counts are the real number of executions.</p>
 *
 * <p>Every non-feasible outcome keeps its own verdict. A budget overrun is {@code limit},
 * not "missing"; unsupported semantics is {@code unsupported}, not "missing".</p>
 */
public record RoutePlanView(String verdict, String message, long bytes, long amount,
                            List<RouteView.Stack> startup, List<Step> steps) {
    public static final String FEASIBLE = "feasible";
    public static final String UNREACHABLE = "unreachable";
    public static final String LIMIT = "limit";
    public static final String UNSUPPORTED = "unsupported";
    public static final String ERROR = "error";

    /** One recipe that the plan executes, named by its output, with the execution count. */
    public record Step(String name, long count) {
    }

    public RoutePlanView {
        startup = List.copyOf(startup);
        steps = List.copyOf(steps);
    }

    public interface StackFactory {
        RouteView.Stack make(AEKey key, long amount);
    }

    public static RoutePlanView of(CertifiedCraftingPlan plan, long amount, StackFactory stacks) {
        var startup = new ArrayList<RouteView.Stack>();
        for (var entry : plan.usedItems()) {
            if (entry.getLongValue() > 0) startup.add(stacks.make(entry.getKey(), entry.getLongValue()));
        }
        startup.sort((a, b) -> Long.compare(b.amount(), a.amount()));
        // One row per distinct output signature: aggregate the same recipe used for several keys.
        var byOutput = new LinkedHashMap<String, Long>();
        for (var entry : plan.patternTimes().entrySet()) {
            byOutput.merge(name(entry.getKey()), entry.getValue(), Math::addExact);
        }
        var steps = new ArrayList<Step>();
        byOutput.forEach((name, count) -> steps.add(new Step(name, count)));
        steps.sort((a, b) -> Long.compare(b.count(), a.count()));
        return new RoutePlanView(FEASIBLE,
                "已证明可行：起手 " + startup.size() + " 种材料，" + steps.size() + " 张样板",
                plan.bytes(), amount, startup, steps);
    }

    public static RoutePlanView of(com.ae2vm.addon.planner.ProductionPlanningException error, long amount) {
        var reason = error.reason() == null
                ? com.ae2vm.addon.planner.ProductionPlanningException.Reason.UNSUPPORTED
                : error.reason();
        String verdict = switch (reason) {
            case UNREACHABLE -> UNREACHABLE;
            case LIMIT -> LIMIT;
            case UNSUPPORTED -> UNSUPPORTED;
            case INVALID -> ERROR;
        };
        String prefix = switch (reason) {
            case UNREACHABLE -> "当前库存与已编码样板达不到目标：";
            case LIMIT -> "预算内未判定（不是缺料）：";
            case UNSUPPORTED -> "无法精确建模（未按缺料处理）：";
            case INVALID -> "请求无效：";
        };
        return new RoutePlanView(verdict, prefix + error.getMessage(), 0, amount, List.of(), List.of());
    }

    public static RoutePlanView error(String message, long amount) {
        return new RoutePlanView(ERROR, message, 0, amount, List.of(), List.of());
    }

    /** True only for a proven plan; every other verdict must not be rendered as "missing". */
    public boolean proven() {
        return FEASIBLE.equals(verdict);
    }

    private static String name(IPatternDetails pattern) {
        var outputs = pattern.getOutputs();
        if (outputs.isEmpty()) return "样板";
        var primary = outputs.get(0);
        String label;
        try {
            label = primary.what() == null ? "样板" : primary.what().getDisplayName().getString();
        } catch (RuntimeException unsupported) {
            // Display names must never decide whether a verdict can be shown.
            label = String.valueOf(primary.what());
        }
        if (label == null || label.isBlank()) label = String.valueOf(primary.what());
        return outputs.size() == 1 ? label : label + " 等 " + outputs.size() + " 项";
    }

    /** Exposed for tests and diagnostics. */
    public Map<String, Long> stepMap() {
        var map = new LinkedHashMap<String, Long>();
        for (var step : steps) map.merge(step.name(), step.count(), Math::addExact);
        return map;
    }
}
