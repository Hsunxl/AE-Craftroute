package com.aecraftroute.addon.route;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.aecraftroute.addon.bench.BenchAEKey;
import com.aecraftroute.addon.bench.BenchPatternDetails;
import com.aecraftroute.addon.planner.NetworkProductionPlanner;
import com.aecraftroute.addon.planner.ProductionPlanningException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.aecraftroute.addon.bench.BenchPatternDetails.InputSpec.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The verdict the canvas shows must be the certified plan, not a division.
 *
 * <p>Two things are pinned here: the durable startup capital (8 dust, not 256) and the
 * rule that a non-feasible outcome keeps its own verdict instead of collapsing into
 * "missing".</p>
 */
class RoutePlanViewTest {
    private final BenchAEKey dust = BenchAEKey.of("data_energistics:data_dust");
    private final BenchAEKey seed = BenchAEKey.of("ae2cs:data_crystal_seed");
    private final BenchAEKey pure = BenchAEKey.of("ae2cs:purified_data_crystal");
    private final BenchAEKey charged = BenchAEKey.of("ae2:charged_certus_quartz_crystal");
    private final BenchAEKey sand = BenchAEKey.of("minecraft:sand");

    private RoutePlanView view(long amount, Map<AEKey, Long> stock) {
        var aggregate = new BenchPatternDetails(seed, 32, List.of(of(dust, 8), of(charged, 8), of(sand, 32)));
        var grow = new BenchPatternDetails(pure, 1, List.of(of(seed, 1)));
        var pulverize = new BenchPatternDetails(dust, 1, List.of(of(pure, 1)));
        List<IPatternDetails> patterns = List.of(aggregate, grow, pulverize);
        try {
            var plan = NetworkProductionPlanner.plan(dust, amount,
                    key -> patterns.stream()
                            .filter(p -> p.getOutputs().stream().anyMatch(s -> s.what().equals(key)))
                            .toList(),
                    key -> stock.getOrDefault(key, 0L));
            return RoutePlanView.of(plan, amount,
                    (key, a) -> new RouteView.Stack(String.valueOf(key), String.valueOf(key), a));
        } catch (ProductionPlanningException failure) {
            return RoutePlanView.of(failure, amount);
        }
    }

    @Test
    void feasibleVerdictCarriesProvenStartupNotSinglePassDemand() {
        var view = view(1000, Map.of(dust, 8L, charged, 1_000_000L, sand, 1_000_000L));
        assertTrue(view.proven(), view.message());
        long startupDust = view.startup().stream()
                .filter(stack -> String.valueOf(stack.name()).contains("data_dust"))
                .mapToLong(RouteView.Stack::amount).sum();
        assertEquals(8L, startupDust, "startup is one aggregator batch; 256 is the single-pass artifact");
        assertTrue(view.startup().stream().anyMatch(s -> String.valueOf(s.name()).contains("charged_certus")));
        assertTrue(view.startup().stream().anyMatch(s -> String.valueOf(s.name()).contains("sand")));
        assertTrue(view.steps().stream().anyMatch(s -> s.count() >= 40),
                "the plan must show the repeated batches, got " + view.steps());
    }

    @Test
    void noStartupCapitalIsNotProvenAndNeverClaimsMissing() {
        // Without a first batch of dust the loop cannot start. Proving unreachability would
        // require exhausting the search, so the honest answer here is "undecided within the
        // budget" - and it must never be rendered as "you are missing 256 dust".
        var view = view(1000, Map.of(charged, 1_000_000L, sand, 1_000_000L));
        assertFalse(view.proven());
        assertEquals(RoutePlanView.LIMIT, view.verdict());
        assertTrue(view.message().contains("未判定"), view.message());
        assertFalse(view.message().contains("256"));
        assertTrue(view.startup().isEmpty());
    }

    @Test
    void targetWithoutAnyProducerIsUnreachableNotMissing() {
        var nowhere = BenchAEKey.of("example:never_produced");
        try {
            NetworkProductionPlanner.plan(nowhere, 1, key -> List.of(), key -> 0L);
            throw new AssertionError("expected a planning failure");
        } catch (ProductionPlanningException failure) {
            var view = RoutePlanView.of(failure, 1);
            assertEquals(RoutePlanView.UNREACHABLE, view.verdict());
            assertTrue(view.message().contains("达不到目标"), view.message());
            assertTrue(view.startup().isEmpty());
        }
    }
}
