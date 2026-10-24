package com.aecraftroute.addon.planner;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.aecraftroute.addon.bench.BenchAEKey;
import com.aecraftroute.addon.bench.BenchPatternDetails;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.aecraftroute.addon.bench.BenchPatternDetails.InputSpec.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The player-visible complaint this pins down: for 1000 data dust the canvas printed
 * "数据晶粉 256" in red, as if 256 dust had to exist up front.
 *
 * <p>The real chain (ae2cs crystal aggregator, verified from the installed jars) is
 * {@code 8 dust + 8 charged certus + 32 sand -> 32 seeds}, then {@code 1 seed -> 1 pure
 * crystal}, then {@code 1 pure -> 1 dust}. Every batch is net +24 dust, so the startup
 * capital is one batch of dust, not the single-pass 256. This test fails if the planner
 * ever goes back to reporting the one-round requirement as the requirement.</p>
 */
class DataCrystalLoopTest {
    private final BenchAEKey dust = BenchAEKey.of("data_energistics:data_dust");
    private final BenchAEKey seed = BenchAEKey.of("ae2cs:data_crystal_seed");
    private final BenchAEKey pure = BenchAEKey.of("ae2cs:purified_data_crystal");
    private final BenchAEKey charged = BenchAEKey.of("ae2:charged_certus_quartz_crystal");
    private final BenchAEKey sand = BenchAEKey.of("minecraft:sand");

    @Test
    void thousandDustStartsFromOneAggregatorBatchNotFrom256() {
        var aggregate = new BenchPatternDetails(seed, 32, List.of(of(dust, 8), of(charged, 8), of(sand, 32)));
        var grow = new BenchPatternDetails(pure, 1, List.of(of(seed, 1)));
        var pulverize = new BenchPatternDetails(dust, 1, List.of(of(pure, 1)));
        List<IPatternDetails> patterns = List.of(aggregate, grow, pulverize);
        Map<AEKey, Long> stock = Map.of(dust, 8L, charged, 1_000_000L, sand, 1_000_000L);

        assertTimeoutPreemptively(Duration.ofSeconds(25), () -> {
            var plan = NetworkProductionPlanner.plan(dust, 1000,
                    key -> patterns.stream()
                            .filter(p -> p.getOutputs().stream().anyMatch(s -> s.what().equals(key)))
                            .toList(),
                    key -> stock.getOrDefault(key, 0L));
            long batches = plan.patternTimes().get(aggregate);
            long startupDust = plan.usedItems().get(dust);
            // Readable evidence: KeyCounter has no toString, so print the entries that matter.
            var startup = new StringBuilder();
            for (var entry : plan.usedItems()) {
                if (entry.getLongValue() > 0) {
                    startup.append(startup.length() == 0 ? "" : ", ")
                            .append(entry.getKey()).append('=').append(entry.getLongValue());
                }
            }
            System.out.println("[data-loop] batches=" + batches + " startup={" + startup + "}"
                    + " charged=" + plan.usedItems().get(charged)
                    + " sand=" + plan.usedItems().get(sand) + " bytes=" + plan.bytes());

            assertEquals(8L, startupDust,
                    "startup capital is one aggregator batch of dust; 256 would be the single-pass artifact");
            assertTrue(batches >= 40,
                    "each batch is net +24 dust, so 1000 net needs at least 40 batches, got " + batches);
            assertEquals(batches * 8L, plan.usedItems().get(charged));
            assertEquals(batches * 32L, plan.usedItems().get(sand));
            assertTrue(plan.simulation() == false && plan.bytes() > 0);
        });
    }
}
