package com.aecraftroute.addon.planner;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.aecraftroute.addon.bench.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.aecraftroute.addon.bench.BenchPatternDetails.InputSpec.of;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Mirrors the in-game selftest topology: a net-growth loop where every step is a
 * plain crafting-table recipe (powder -> crystal -> seed x2 -> powder).
 */
class GrowthLoopTest {
    private final BenchAEKey powder = BenchAEKey.of("powder");
    private final BenchAEKey crystal = BenchAEKey.of("crystal");
    private final BenchAEKey seed = BenchAEKey.of("seed");

    private CertifiedCraftingPlan plan(List<IPatternDetails> patterns, Map<AEKey, Long> stock,
                                       AEKey goal, long amount) {
        return NetworkProductionPlanner.plan(goal, amount,
                k -> patterns.stream().filter(p -> p.getOutputs().stream()
                        .anyMatch(s -> s.what().equals(k))).toList(),
                k -> stock.getOrDefault(k, 0L));
    }

    @Test
    void growthLoopFromOneSeedStockTerminates() {
        var p1 = new BenchPatternDetails(crystal, 1, List.of(of(powder, 1)));
        var p2 = new BenchPatternDetails(seed, 2, List.of(of(crystal, 1)));
        var p3 = new BenchPatternDetails(powder, 1, List.of(of(seed, 1)));
        List<IPatternDetails> patterns = List.of(p1, p2, p3);
        assertTimeoutPreemptively(Duration.ofSeconds(25), () -> {
            long start = System.nanoTime();
            var plan = plan(patterns, Map.of(powder, 1L), crystal, 3);
            System.out.println("[growth-loop] ms=" + (System.nanoTime() - start) / 1_000_000
                    + " bytes=" + plan.bytes() + " patterns=" + plan.patternTimes());
        });
    }

    @Test
    void largerGrowthRequestTerminates() {
        var p1 = new BenchPatternDetails(crystal, 1, List.of(of(powder, 1)));
        var p2 = new BenchPatternDetails(seed, 2, List.of(of(crystal, 1)));
        var p3 = new BenchPatternDetails(powder, 1, List.of(of(seed, 1)));
        List<IPatternDetails> patterns = List.of(p1, p2, p3);
        assertTimeoutPreemptively(Duration.ofSeconds(25), () -> {
            long start = System.nanoTime();
            var plan = plan(patterns, Map.of(powder, 1L), crystal, 8);
            System.out.println("[growth-loop-large] ms=" + (System.nanoTime() - start) / 1_000_000
                    + " bytes=" + plan.bytes() + " patterns=" + plan.patternTimes());
        });
    }
}
