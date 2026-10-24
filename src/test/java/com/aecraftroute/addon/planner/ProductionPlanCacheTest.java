package com.aecraftroute.addon.planner;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static com.aecraftroute.addon.planner.ProductionPlanner.*;

class ProductionPlanCacheTest {
    private static final Limits LIMITS = new Limits(10_000, 2_000_000_000L);
    private final List<Recipe<String,Integer>> recipes = List.of(new Recipe<>(0, Map.of("ore", 1L), Map.of("out", 1L)));
    private final ProductionPlanCache<String> cache = new ProductionPlanCache<>();
    private Result<String,Integer> solve(long amount) { return cache.solve("out", amount, recipes, Map.of("ore", 100L), LIMITS); }

    @Test void exactRequestHitsAndCertifiedResultIsImmutable() {
        var first = solve(5);
        assertSame(first, solve(5));
        assertEquals(1, cache.stats().hits());
        assertThrows(UnsupportedOperationException.class, () -> first.used().put("ore", 0L));
        assertThrows(UnsupportedOperationException.class, () -> first.counts().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.program().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.program().getFirst().word().clear());
    }

    @Test void stockAndAmountAndTargetChangesMiss() {
        solve(5);
        solve(6);
        var missing = cache.solve("out", 5, recipes, Map.of("ore", 4L), LIMITS);
        assertEquals(Status.UNREACHABLE, missing.status());
        assertEquals(Status.UNREACHABLE, cache.solve("ore", 5, recipes, Map.of("ore", 100L), LIMITS).status());
        assertEquals(0, cache.stats().hits());
        assertEquals(4, cache.stats().misses());
    }

    @Test void equalHashCodesAreNotEqualNets() {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        var stock = Map.of("Aa", 1L, "BB", 0L);
        var a = List.of(new Recipe<>(0, Map.of("Aa", 1L), Map.of("out", 1L)));
        var b = List.of(new Recipe<>(0, Map.of("BB", 1L), Map.of("out", 1L)));
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(Status.FEASIBLE, cache.solve("out", 1, a, stock, LIMITS).status());
        assertEquals(Status.UNREACHABLE, cache.solve("out", 1, b, stock, LIMITS).status());
        assertEquals(0, cache.stats().hits());
    }

    @Test void changedTransitionsAndRecipeOrderDoNotReuseOldIndices() {
        var stock = Map.of("ore", 2L);
        var a = new Recipe<>(0, Map.of("ore", 1L), Map.of("out", 1L));
        var b = new Recipe<>(1, Map.of("ore", 2L), Map.of("out", 1L));
        var first = cache.solve("out", 2, List.of(a,b), stock, LIMITS);
        var second = cache.solve("out", 2, List.of(new Recipe<>(0,b.inputs(),b.outputs()), new Recipe<>(1,a.inputs(),a.outputs())), stock, LIMITS);
        assertEquals(Map.of(0, 2L), first.counts());
        assertEquals(Map.of(1, 2L), second.counts());
        assertEquals(0, cache.stats().hits());
    }

    @Test void neitherLimitsNorUnreachableResultsAreCached() {
        assertEquals(Status.LIMIT, cache.solve("out", 5, recipes, Map.of("ore", 100L), new Limits(1, 2_000_000_000L)).status());
        assertEquals(0, cache.stats().entries());
        assertEquals(Status.FEASIBLE, solve(5).status());
        assertEquals(Status.UNREACHABLE, cache.solve("out", 101, recipes, Map.of("ore", 100L), LIMITS).status());
        assertEquals(1, cache.stats().entries());
        // An existing proof remains valid with a shorter search budget.
        assertEquals(Status.FEASIBLE, cache.solve("out", 5, recipes, Map.of("ore", 100L), new Limits(1, 1)).status());
        assertEquals(1, cache.stats().hits());
    }

    @Test void snapshotsAreFrozenAndMapIterationOrderDoesNotMatter() {
        var stock = new LinkedHashMap<>(Map.of("ore", 100L, "unused", 0L));
        var first = cache.solve("out", 5, recipes, stock, LIMITS);
        stock.put("ore", 1L);
        assertEquals(Status.UNREACHABLE, cache.solve("out", 5, recipes, stock, LIMITS).status());
        assertSame(first, cache.solve("out", 5, recipes, Map.of("unused", 0L, "ore", 100L), LIMITS));
    }

    @Test void expirationDoesNotSlideOnReads() {
        var now = new AtomicLong();
        var c = new ProductionPlanCache<String>(2, 1000, 10, now::get);
        var first = c.solve("out", 1, recipes, Map.of("ore", 1L), LIMITS);
        now.set(9);
        assertSame(first, c.solve("out", 1, recipes, Map.of("ore", 1L), LIMITS));
        now.set(10);
        assertEquals(0, c.stats().entries());
        assertEquals(0, c.stats().weight());
        assertNotSame(first, c.solve("out", 1, recipes, Map.of("ore", 1L), LIMITS));
    }

    @Test void leastRecentlyUsedEntryIsEvicted() {
        var c = new ProductionPlanCache<String>(2, 1000, Long.MAX_VALUE, () -> 0);
        var one = c.solve("out", 1, recipes, Map.of("ore", 100L), LIMITS);
        var two = c.solve("out", 2, recipes, Map.of("ore", 100L), LIMITS);
        assertSame(one, c.solve("out", 1, recipes, Map.of("ore", 100L), LIMITS));
        c.solve("out", 3, recipes, Map.of("ore", 100L), LIMITS);
        assertEquals(2, c.stats().entries());
        assertSame(one, c.solve("out", 1, recipes, Map.of("ore", 100L), LIMITS));
        assertNotSame(two, c.solve("out", 2, recipes, Map.of("ore", 100L), LIMITS));
    }

    @Test void weightBoundAndOversizedEntryBypass() {
        var tiny = new ProductionPlanCache<String>(100, 1, Long.MAX_VALUE, () -> 0);
        assertEquals(Status.FEASIBLE, tiny.solve("out", 1, recipes, Map.of("ore", 100L), LIMITS).status());
        assertEquals(0, tiny.stats().entries());
        var c = new ProductionPlanCache<String>(100, 20, Long.MAX_VALUE, () -> 0);
        for (int i = 1; i <= 10; i++) c.solve("out", i, recipes, Map.of("ore", 100L), LIMITS);
        assertTrue(c.stats().weight() <= 20);
        assertTrue(c.stats().entries() > 0 && c.stats().entries() < 10);
    }

    @Test void warmHitStillHonoursInterruption() {
        solve(1);
        Thread.currentThread().interrupt();
        try { assertThrows(CancellationException.class, () -> solve(1)); }
        finally { Thread.interrupted(); }
        assertEquals(0, cache.stats().hits());
    }

    @Test void concurrentRequestsShareOnlyImmutableProofs() throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            var calls = new ArrayList<Callable<Result<String,Integer>>>();
            for (int i = 0; i < 32; i++) calls.add(() -> solve(7));
            for (var f : pool.invokeAll(calls)) assertEquals(Map.of(0, 7L), f.get().counts());
        }
        assertEquals(1, cache.stats().entries());
        assertTrue(cache.stats().hits() > 0);
        cache.clear();
        assertEquals(new ProductionPlanCache.Stats(0,0,0,0), cache.stats());
    }

    @Test void rejectsNonPositionalIdsRatherThanSilentlyMisbind() {
        assertThrows(IllegalArgumentException.class, () -> cache.solve("out", 1,
                List.of(new Recipe<>(7, Map.of("ore", 1L), Map.of("out", 1L))), Map.of("ore", 1L), LIMITS));
    }
}
