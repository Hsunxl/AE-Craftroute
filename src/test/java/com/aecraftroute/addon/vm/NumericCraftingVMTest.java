package com.aecraftroute.addon.vm;

import com.aecraftroute.addon.planner.ProductionPlanner;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aecraftroute.addon.vm.CompiledNumericProgram.*;
import static com.aecraftroute.addon.vm.NumericCraftingVM.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class NumericCraftingVMTest {
    private CompiledNumericProgram compile(long[] input, long[] output) {
        return NumericProgramCompiler.compile(input.length, new int[][]{indices(input)}, new long[][]{positive(input)},
                new int[][]{indices(output)}, new long[][]{positive(output)}, () -> false);
    }
    private int[] indices(long[] values) {
        return java.util.stream.IntStream.range(0, values.length).filter(i -> values[i] > 0).toArray();
    }
    private long[] positive(long[] values) { return Arrays.stream(values).filter(n -> n > 0).toArray(); }
    private NumericCraftingVM.Execution execute(CompiledNumericProgram p, long requested, long... stock) {
        return NumericCraftingVM.execute(p, 0, requested, stock, () -> false);
    }

    @Test void compilerEmitsSparseInstructionsOperandsAndConstantPool() {
        var p = NumericProgramCompiler.compile(100,
                new int[][]{{3}}, new long[][]{{2}}, new int[][]{{3, 89}}, new long[][]{{3, 5}}, () -> false);
        assertArrayEquals(new byte[]{CHECK_PREFIX, LIMIT_OUTPUT, LIMIT_OUTPUT, APPLY_DELTA, APPLY_DELTA, RECORD_PATTERN, HALT}, p.code());
        assertArrayEquals(new int[]{0}, p.entryPoints());
        assertArrayEquals(new long[]{2, 1, 5}, p.constants());
        assertEquals(3, p.operands()[0]);
        assertEquals(89, p.operands()[6]);
        assertEquals(21, p.operands().length);
        assertEquals(100, p.keyCount());
    }

    @Test void catalystsRequireOneSeedNotOnePerExecution() {
        var p = compile(new long[]{1, 1, 0}, new long[]{1, 0, 1});
        var result = execute(p, 1000, 1, 1000, 0);
        assertEquals(EXECUTED, result.status());
        assertEquals(1000, result.step().count());
        assertArrayEquals(new long[]{1, 0, 1000}, result.inventory());
        assertEquals(BLOCKED, execute(p, 1000, 0, 1000, 0).status());
    }

    @Test void returnedSeedCanGrowButCannotBootstrapFromNothing() {
        var p = compile(new long[]{2}, new long[]{5});
        assertEquals(BLOCKED, execute(p, 20, 1).status());
        var result = execute(p, 20, 2);
        assertEquals(20, result.step().count());
        assertArrayEquals(new long[]{62}, result.inventory());
    }

    @Test void negativeNetDeltaUsesFirstSeedAndThenNetConsumption() {
        var result = execute(compile(new long[]{5}, new long[]{3}), 100, 11);
        assertEquals(4, result.step().count());
        assertArrayEquals(new long[]{3}, result.inventory());
    }

    @Test void sharedInputOutputIsUpdatedExactlyOnce() {
        var result = execute(compile(new long[]{7, 0}, new long[]{11, 2}), 3, 7, 0);
        assertArrayEquals(new long[]{19, 6}, result.inventory());
    }

    @Test void positiveNetOutputCapsBeforeOverflowWithoutSaturation() {
        var p = compile(new long[]{1}, new long[]{3});
        var result = execute(p, Long.MAX_VALUE, Long.MAX_VALUE - 5);
        assertEquals(2, result.step().count());
        assertEquals(Long.MAX_VALUE - 1, result.quantity(0));
        assertEquals(BLOCKED, execute(p, 1, Long.MAX_VALUE).status());
        var maximal = execute(compile(new long[]{0}, new long[]{Long.MAX_VALUE}), Long.MAX_VALUE, 0);
        assertEquals(1, maximal.step().count());
        assertEquals(Long.MAX_VALUE, maximal.quantity(0));
    }

    @Test void negativeExtremeDeltaIsExact() {
        var result = execute(compile(new long[]{Long.MAX_VALUE, 0}, new long[]{0, 1}), Long.MAX_VALUE, Long.MAX_VALUE, 0);
        assertEquals(1, result.step().count());
        assertArrayEquals(new long[]{0, 1}, result.inventory());
    }

    @Test void blockedCandidateDoesNotConsumeFallbackStock() {
        long[] stock = {10, 0, 0};
        var blocked = execute(compile(new long[]{2, 1, 0}, new long[]{0, 0, 1}), 2, stock);
        assertEquals(BLOCKED, blocked.status());
        assertNull(blocked.step()); assertNull(blocked.inventory());
        assertArrayEquals(new long[]{10, 0, 0}, stock);
        var fallback = execute(compile(new long[]{2, 0, 0}, new long[]{0, 0, 1}), 2, stock);
        assertEquals(EXECUTED, fallback.status());
        assertArrayEquals(new long[]{6, 0, 2}, fallback.inventory());
        assertArrayEquals(new long[]{10, 0, 0}, stock);
    }

    @Test void timeoutAndCancellationAtEveryInterpreterCheckpointAreAtomic() {
        var p = compile(new long[]{2, 1, 0}, new long[]{1, 3, 5});
        long[] stock = {20, 2, 0}, original = stock.clone();
        var calls = new AtomicInteger();
        assertEquals(EXECUTED, NumericCraftingVM.execute(p, 0, 4, stock, () -> { calls.incrementAndGet(); return false; }).status());
        // Includes guard, APPLY_DELTA, RECORD_PATTERN and HALT checkpoints,
        // hence also aborts after one or more writes into the private copy.
        for (int checkpoint = 1; checkpoint <= calls.get(); checkpoint++) {
            int stop = checkpoint;
            var checks = new AtomicInteger();
            var timeout = NumericCraftingVM.execute(p, 0, 4, stock, () -> checks.incrementAndGet() == stop);
            assertEquals(TIMED_OUT, timeout.status());
            assertNull(timeout.inventory()); assertNull(timeout.step());
            assertArrayEquals(original, stock);
            var cancellation = new CancellationException("checkpoint " + stop);
            checks.set(0);
            assertSame(cancellation, assertThrows(CancellationException.class, () ->
                    NumericCraftingVM.execute(p, 0, 4, stock, () -> {
                        if (checks.incrementAndGet() == stop) throw cancellation;
                        return false;
                    })));
            assertArrayEquals(original, stock);
        }
    }

    @Test void compilationChecksBudgetAndPropagatesCancellation() {
        int[][] keys = {{0, 1}};
        long[][] quantities = {{1, 2}};
        var calls = new AtomicInteger();
        assertNotNull(NumericProgramCompiler.compile(2, keys, quantities, keys, quantities,
                () -> { calls.incrementAndGet(); return false; }));
        for (int checkpoint = 1; checkpoint <= calls.get(); checkpoint++) {
            int stop = checkpoint;
            var checks = new AtomicInteger();
            assertNull(NumericProgramCompiler.compile(2, keys, quantities, keys, quantities,
                    () -> checks.incrementAndGet() == stop));
            var cancellation = new CancellationException("compile " + stop);
            checks.set(0);
            assertSame(cancellation, assertThrows(CancellationException.class, () ->
                    NumericProgramCompiler.compile(2, keys, quantities, keys, quantities, () -> {
                        if (checks.incrementAndGet() == stop) throw cancellation;
                        return false;
                    })));
        }
        assertArrayEquals(new long[]{1, 2}, quantities[0]);
    }

    @Test void programsAndReturnedInventoriesAreIsolatedWithoutGlobalCache() {
        int[][] inKeys = {{0}}, outKeys = {{1}};
        long[][] in = {{2}}, out = {{3}};
        var first = NumericProgramCompiler.compile(2, inKeys, in, outKeys, out, () -> false);
        in[0][0] = 1; out[0][0] = 9;
        var second = NumericProgramCompiler.compile(2, inKeys, in, outKeys, out, () -> false);
        inKeys[0][0] = 1; outKeys[0][0] = 0;
        first.code()[0] = HALT; first.constants()[0] = 0;
        first.operands()[0] = 1; first.entryPoints()[0] = 999;
        var a = execute(first, 2, 4, 0);
        var b = execute(second, 2, 4, 0);
        assertArrayEquals(new long[]{0, 6}, a.inventory());
        assertArrayEquals(new long[]{2, 18}, b.inventory());
        a.inventory()[0] = 999;
        assertEquals(0, a.quantity(0));
        assertArrayEquals(new long[]{0, 6}, execute(first, 2, 4, 0).inventory());
    }

    @Test void separateRecipeEntryPointsRecordTheirOwnPattern() {
        var p = NumericProgramCompiler.compile(3, new int[][]{{0}, {1}}, new long[][]{{2}, {3}},
                new int[][]{{1}, {2}}, new long[][]{{3}, {5}}, () -> false);
        var first = NumericCraftingVM.execute(p, 0, 2, new long[]{4, 0, 0}, () -> false);
        var second = NumericCraftingVM.execute(p, 1, 2, first.inventory(), () -> false);
        assertEquals(new NumericCraftingVM.Step(1, 2), second.step());
        assertArrayEquals(new long[]{0, 0, 10}, second.inventory());
    }

    @Test void randomTransitionsAgreeWithIndependentBigIntegerScalarFirings() {
        var random = new Random(0x54724e51);
        var max = BigInteger.valueOf(Long.MAX_VALUE);
        for (int trial = 0; trial < 2000; trial++) {
            int size = 1 + random.nextInt(7), requested = 1 + random.nextInt(20);
            long[] input = new long[size], output = new long[size], stock = new long[size];
            for (int k = 0; k < size; k++) {
                input[k] = random.nextInt(7); output[k] = random.nextInt(7);
                stock[k] = random.nextInt(6) == 0 ? Long.MAX_VALUE - random.nextInt(20) : random.nextInt(50);
            }
            output[random.nextInt(size)] = 1 + random.nextInt(6);
            var expected = Arrays.stream(stock).mapToObj(BigInteger::valueOf).toArray(BigInteger[]::new);
            int count = 0;
            while (count < requested) {
                boolean enabled = true;
                var next = expected.clone();
                for (int k = 0; k < size; k++) {
                    enabled &= expected[k].compareTo(BigInteger.valueOf(input[k])) >= 0;
                    next[k] = expected[k].subtract(BigInteger.valueOf(input[k])).add(BigInteger.valueOf(output[k]));
                    enabled &= next[k].signum() >= 0 && next[k].compareTo(max) <= 0;
                }
                if (!enabled) break;
                expected = next; count++;
            }
            var result = execute(compile(input, output), requested, stock);
            assertEquals(count == 0 ? BLOCKED : EXECUTED, result.status(), "trial " + trial);
            if (count > 0) {
                assertEquals(count, result.step().count(), "trial " + trial);
                assertArrayEquals(Arrays.stream(expected).mapToLong(BigInteger::longValueExact).toArray(), result.inventory(), "trial " + trial);
            }
        }
    }

    @Test void productionPlannerUsesCompiledProbeThenIndependentCertificate() {
        var recipe = new ProductionPlanner.Recipe<>("catalyzed", Map.of("tool", 1L, "ore", 1L), Map.of("tool", 1L, "out", 1L));
        var result = new ProductionPlanner<>(List.of(recipe), Map.of("tool", 1L, "ore", 1000L),
                new ProductionPlanner.Limits(30_000, 10_000_000_000L)).solve("out", 1000);
        assertEquals(ProductionPlanner.Status.FEASIBLE, result.status());
        // One explored batch proves the forward probe returned; backward search
        // would at least expand the goal and its predecessor. The probe directly
        // invokes NumericProgramCompiler.compile and NumericCraftingVM.execute.
        assertEquals(1, result.explored());
        assertEquals(Map.of("catalyzed", 1000L), result.counts());
        assertEquals(Map.of("tool", 1L, "ore", 1000L), result.used());
        assertEquals(1, result.program().size());
    }
}
