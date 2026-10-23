package com.ae2vm.addon.route;

import appeng.api.stacks.AEKey;
import com.ae2vm.addon.bench.BenchAEKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The canvas may only become constraints the server can actually honour. */
class RouteIntentTest {
    private final BenchAEKey dust = BenchAEKey.of("data_energistics:data_dust");
    private final BenchAEKey seed = BenchAEKey.of("ae2cs:data_crystal_seed");
    private final RoutePolicy<AEKey> empty = new RoutePolicy<>(Map.of());

    @Test
    void distinctMaterialsLockIndependently() {
        var result = RouteIntent.merge(empty, List.of(
                new RouteIntent.Node(dust, "数据晶粉", "ae2cs:aggregator", true),
                new RouteIntent.Node(seed, "数据水晶种子", "ae2cs:growth", true)));
        assertTrue(result.clean(), String.valueOf(result.problems()));
        assertEquals(List.of("数据晶粉", "数据水晶种子"), result.locked());
        assertEquals("ae2cs:aggregator", result.policy().rule(dust).locked());
        assertEquals("ae2cs:growth", result.policy().rule(seed).locked());
    }

    @Test
    void oneMaterialWithTwoDifferentRecipesIsAConflictNotASilentPick() {
        var result = RouteIntent.merge(empty, List.of(
                new RouteIntent.Node(dust, "数据晶粉", "recipe:a", true),
                new RouteIntent.Node(dust, "数据晶粉", "recipe:b", true)));
        assertFalse(result.clean());
        assertEquals(1, result.problems().size());
        assertTrue(result.problems().get(0).contains("2 张不同样板"), result.problems().get(0));
        assertNull(result.policy().rule(dust).locked(), "a conflict must not lock either recipe");
        assertTrue(result.locked().isEmpty());
    }

    @Test
    void stalePatternIsReportedPerNode() {
        var result = RouteIntent.merge(empty, List.of(
                new RouteIntent.Node(dust, "数据晶粉", "recipe:gone", false)));
        assertFalse(result.clean());
        assertTrue(result.problems().get(0).contains("已不在这个网络里"), result.problems().get(0));
        assertNull(result.policy().rule(dust).locked());
    }

    @Test
    void nodesWithoutAChosenRecipeAreIgnored() {
        var result = RouteIntent.merge(empty, List.of(
                new RouteIntent.Node(dust, "数据晶粉", null, false),
                new RouteIntent.Node(seed, "数据水晶种子", "  ", false)));
        assertTrue(result.clean());
        assertTrue(result.locked().isEmpty());
        assertTrue(result.policy().rules().isEmpty());
    }

    @Test
    void previousRulesForOtherMaterialsSurvive() {
        RoutePolicy<AEKey> base = new RoutePolicy<>(Map.of(seed,
                new RoutePolicy.Rule("ae2cs:growth", Set.of(), false)));
        var result = RouteIntent.merge(base, List.of(
                new RouteIntent.Node(dust, "数据晶粉", "ae2cs:aggregator", true)));
        assertEquals("ae2cs:growth", result.policy().rule(seed).locked());
        assertEquals("ae2cs:aggregator", result.policy().rule(dust).locked());
    }
}
