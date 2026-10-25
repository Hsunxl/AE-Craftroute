package com.aecraftroute.addon.planner;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ListCraftingInventory;
import com.aecraftroute.addon.bench.BenchAEKey;
import com.aecraftroute.addon.bench.BenchPatternDetails;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.aecraftroute.addon.bench.BenchPatternDetails.InputSpec.of;
import static com.aecraftroute.addon.bench.BenchPatternDetails.InputSpec.returned;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the certified protocol through the same steps native {@code executeCrafting}
 * performs: {@link ProductionJob#extract} takes the inputs, the provider accepts the
 * pattern, AE2 registers the expected outputs in {@code waitingFor}, the provider returns
 * them, and the CPU keeps them in its inventory instead of handing them to the requester.
 *
 * <p>This is the user-visible promise, and none of it needs a Minecraft world:
 * intermediates must stay inside the CPU and feed the next step, catalysts must come back
 * and be reused, byproducts must not delay the target, and the target may only leave the
 * CPU once the program is finished and every outstanding return has landed.
 */
class ProductionExecutionTest {
    final BenchAEKey dust = BenchAEKey.of("exec_dust");
    final BenchAEKey seed = BenchAEKey.of("exec_seed");
    final BenchAEKey slag = BenchAEKey.of("exec_slag");
    final BenchAEKey tool = BenchAEKey.of("exec_tool");

    /** One CPU. {@code inventory} is the cache under test, {@code waitingFor} is AE2's
     * own return ledger, {@code tasks} mirrors the native task map. */
    final class Cpu {
        final ListCraftingInventory inventory = new ListCraftingInventory(key -> { });
        final ListCraftingInventory waitingFor = new ListCraftingInventory(key -> { });
        final Map<IPatternDetails, Long> tasks = new HashMap<>();
        final Map<AEKey, Long> delivered = new HashMap<>();
        final ProductionJob job;
        long remaining;

        Cpu(CertifiedCraftingPlan plan) {
            this.job = new ProductionJob(plan);
            this.remaining = plan.finalOutput().amount();
            this.tasks.putAll(plan.patternTimes());
        }

        long stock(AEKey key) { return inventory.list.get(key); }

        /** Native success path: our extract, the provider push, then AE2's own
         * {@code waitingFor} registration, which this mod leaves untouched. */
        boolean dispatch(IPatternDetails pattern) {
            var outputs = new KeyCounter();
            var containers = new KeyCounter();
            var inputs = job.extract(pattern, inventory, null, outputs, containers);
            if (inputs == null) return false;
            job.accepted(pattern);
            register(outputs);
            register(containers);
            long left = tasks.getOrDefault(pattern, 0L) - 1;
            if (left <= 0) tasks.remove(pattern); else tasks.put(pattern, left);
            return true;
        }

        private void register(KeyCounter counter) {
            for (var entry : counter) waitingFor.insert(entry.getKey(), entry.getLongValue(), Actionable.MODULATE);
        }

        /** The provider finished the pattern and pushes everything back, which is what
         * AE2 turns into {@code insert} calls on the CPU. */
        void settle() {
            var keys = new ArrayList<AEKey>();
            for (var entry : waitingFor.list) keys.add(entry.getKey());
            for (var key : keys) returnToCpu(key, waitingFor.list.get(key));
        }

        /** {@code ProductionCpuMixin.insert}: an accepted return is stored in the CPU
         * inventory, never delivered to the requester. */
        long returnToCpu(AEKey key, long amount) {
            long accepted = waitingFor.extract(key, amount, Actionable.SIMULATE);
            if (accepted > 0) {
                waitingFor.extract(key, accepted, Actionable.MODULATE);
                inventory.insert(key, accepted, Actionable.MODULATE);
            }
            return accepted;
        }

        /** {@code ProductionCpuMixin.tickCraftingLogic} TAIL delivery gate. */
        long deliver(AEKey target) {
            long held = job.deliverableAmount(inventory.list,target,remaining);
            if (held <= 0) return 0;
            inventory.extract(target, held, Actionable.MODULATE);
            remaining -= held;
            delivered.merge(target, held, Long::sum);
            return held;
        }
    }

    static PatternVariant variant(IPatternDetails pattern, GenericStack... templates) {
        var slots = new ArrayList<PatternVariant.Slot>();
        for (var template : templates) slots.add(new PatternVariant.Slot(List.of(new PatternVariant.Portion(template, 1))));
        return new PatternVariant(pattern, slots);
    }

    static CertifiedCraftingPlan plan(AEKey target, long amount, Map<AEKey, Long> used,
                                      List<ProductionPlanner.Block<PatternVariant>> program) {
        var usedItems = new KeyCounter();
        used.forEach(usedItems::add);
        var times = new LinkedHashMap<IPatternDetails, Long>();
        for (var block : program)
            for (var step : block.word())
                times.merge(step.recipe().pattern(), step.count() * block.repetitions(), Math::addExact);
        return new CertifiedCraftingPlan(new GenericStack(target, amount), 256L, usedItems, times, program);
    }

    @SafeVarargs
    static ProductionPlanner.Block<PatternVariant> block(long repetitions, ProductionPlanner.Step<PatternVariant>... steps) {
        return new ProductionPlanner.Block<>(List.of(steps), repetitions);
    }

    static ProductionPlanner.Step<PatternVariant> step(PatternVariant variant, long count) {
        return new ProductionPlanner.Step<>(variant, count);
    }

    /** A growth ring: 1 dust becomes 1 seed, 1 seed becomes 2 dust. Every intermediate is
     * crafted again, so the CPU cache is the only place they may live. */
    @Test
    void ringIntermediatesStayInTheCpuAndFeedTheNextRound() {
        var grow = new BenchPatternDetails(seed, 1, List.of(of(dust, 1)));
        var sprout = new BenchPatternDetails(dust, 2, List.of(of(seed, 1)));
        var plan = plan(dust, 4,
                Map.of(dust, 1L),
                List.of(block(4, step(variant(grow, new GenericStack(dust, 1)), 1),
                        step(variant(sprout, new GenericStack(seed, 1)), 1))));
        var cpu = new Cpu(plan);
        cpu.inventory.insert(dust, 1, Actionable.MODULATE);   // tryExtractInitialItems

        for (long round = 1; round <= 4; round++) {
            assertTrue(cpu.dispatch(grow), "growth dispatch " + round);
            assertEquals(round - 1, cpu.stock(dust), "each generation spends one dust inside the CPU");
            assertEquals(1, cpu.waitingFor.list.get(seed), "the seed is expected back, not delivered");
            cpu.settle();
            assertEquals(1, cpu.stock(seed), "the seed lands in the CPU cache");

            assertTrue(cpu.dispatch(sprout), "sprout dispatch " + round);
            assertEquals(0, cpu.stock(seed), "the seed is consumed by the next step");
            cpu.settle();
            assertEquals(round + 1, cpu.stock(dust), "one net dust per generation");
            assertEquals(0, cpu.stock(seed), "the seed never leaves the CPU");
            assertTrue(cpu.delivered.isEmpty(), "no intermediate may reach the requester");
            assertTrue(cpu.job.validateRemainingTasks(cpu.tasks), "native tasks stay in step with the cursor");
        }

        assertTrue(cpu.job.done());
        assertEquals(4, cpu.deliver(dust), "the grown dust is handed over only at the end");
        assertEquals(Map.of(dust, 4L), cpu.delivered);
        assertEquals(0, cpu.remaining);
        assertEquals(1, cpu.stock(dust), "the working seed is retained for native return-to-network cleanup");
    }

    /** A catalyst is consumed and returned by every firing; one unit must survive the
     * whole program and must not be counted as fresh stock. */
    @Test
    void catalystIsReturnedAfterEveryFiringAndReused() {
        var mix = new BenchPatternDetails(seed, 1, List.of(of(dust, 1), returned(tool, 1)));
        var plan = plan(seed, 4, Map.of(dust, 4L, tool, 1L),
                List.of(block(1, step(variant(mix, new GenericStack(dust, 1), new GenericStack(tool, 1)), 4))));
        var cpu = new Cpu(plan);
        cpu.inventory.insert(dust, 4, Actionable.MODULATE);
        cpu.inventory.insert(tool, 1, Actionable.MODULATE);

        for (int firing = 1; firing <= 4; firing++) {
            assertTrue(cpu.dispatch(mix), "firing " + firing);
            assertEquals(1, cpu.waitingFor.list.get(tool), "the container return is expected back");
            assertEquals(0, cpu.stock(tool), "the catalyst really left the cache for the provider");
            cpu.settle();
            assertEquals(1, cpu.stock(tool), "the same catalyst unit serves every firing");
            assertEquals(firing, cpu.stock(seed));
            assertEquals(4 - firing, cpu.stock(dust));
            assertTrue(cpu.delivered.isEmpty());
        }

        assertTrue(cpu.job.done());
        assertEquals(4, cpu.deliver(seed));
        assertEquals(Map.of(seed, 4L), cpu.delivered);
        assertEquals(1, cpu.stock(tool), "the catalyst stays in the CPU, unspent");
    }

    /** A byproduct leaves the program without a consumer: it must accumulate in the CPU
     * cache and must not delay the target. */
    @Test
    void byproductsAccumulateWithoutBlockingDelivery() {
        var forge = new BenchPatternDetails(seed, 1, List.of(of(dust, 1)),
                List.of(BenchPatternDetails.OutputSpec.of(slag, 1)));
        var plan = plan(seed, 3, Map.of(dust, 3L),
                List.of(block(1, step(variant(forge, new GenericStack(dust, 1)), 3))));
        var cpu = new Cpu(plan);
        cpu.inventory.insert(dust, 3, Actionable.MODULATE);

        for (int firing = 1; firing <= 3; firing++) {
            assertTrue(cpu.dispatch(forge), "firing " + firing);
            assertEquals(1, cpu.waitingFor.list.get(slag), "the byproduct is expected back too");
            cpu.settle();
            assertEquals(firing, cpu.stock(slag), "the byproduct accumulates in the CPU cache");
            assertEquals(firing, cpu.stock(seed));
            assertTrue(cpu.delivered.isEmpty(), "the byproduct never delays or replaces the target");
        }

        assertTrue(cpu.job.done());
        assertEquals(3, cpu.deliver(seed));
        assertEquals(Map.of(seed, 3L), cpu.delivered);
        assertEquals(3, cpu.stock(slag), "unconsumed byproducts wait in the CPU cache");
    }

    /** Delivery is a separate phase: a finished cursor with an outstanding return must
     * not hand over the target yet. */
    @Test
    void outstandingReturnsWithholdDelivery() {
        var forge = new BenchPatternDetails(seed, 1, List.of(of(dust, 1)));
        var plan = plan(seed, 1, Map.of(dust, 1L),
                List.of(block(1, step(variant(forge, new GenericStack(dust, 1)), 1))));
        var cpu = new Cpu(plan);
        cpu.inventory.insert(dust, 1, Actionable.MODULATE);

        assertTrue(cpu.dispatch(forge));
        assertTrue(cpu.job.done());
        assertEquals(0, cpu.deliver(seed), "nothing to deliver while the output is still in flight");
        assertEquals(1, cpu.returnToCpu(seed, 1));
        assertEquals(1, cpu.deliver(seed));
        assertEquals(0, cpu.stock(seed));
    }

    /** A return the machine voided (no byproduct came back, a catalyst was destroyed) must
     * not pin the CPU: only the target's own outstanding returns may delay delivery, and
     * the leftovers stay in the cache for native storeItems. */
    @Test
    void voidedReturnsCannotPinTheCpuForever() {
        var forge = new BenchPatternDetails(seed, 1, List.of(of(dust, 1)),
                List.of(BenchPatternDetails.OutputSpec.of(slag, 1)));
        var plan = plan(seed, 1, Map.of(dust, 1L),
                List.of(block(1, step(variant(forge, new GenericStack(dust, 1)), 1))));
        var cpu = new Cpu(plan);
        cpu.inventory.insert(dust, 1, Actionable.MODULATE);

        assertTrue(cpu.dispatch(forge));
        assertEquals(1, cpu.returnToCpu(seed, 1));
        assertTrue(cpu.job.done());
        assertEquals(1, cpu.waitingFor.list.get(slag), "the byproduct return is still outstanding");
        assertEquals(1, cpu.deliver(seed), "the missing byproduct must not withhold the target");
        assertEquals(Map.of(seed, 1L), cpu.delivered);
        assertEquals(0, cpu.stock(seed));
    }

    /** A refused dispatch must not consume anything: the provider never saw the pattern,
     * so the cache has to be exactly as it was. */
    @Test
    void refusedDispatchLeavesTheCacheUntouched() {
        var grow = new BenchPatternDetails(seed, 1, List.of(of(dust, 1)));
        var other = new BenchPatternDetails(dust, 3, List.of(of(dust, 2)));
        var plan = plan(seed, 1, Map.of(dust, 2L),
                List.of(block(1, step(variant(grow, new GenericStack(dust, 1)), 1),
                        step(variant(other, new GenericStack(dust, 2)), 1))));
        var cpu = new Cpu(plan);
        cpu.inventory.insert(dust, 2, Actionable.MODULATE);

        assertFalse(cpu.dispatch(other), "a pattern that is not the certified next step is refused");
        assertEquals(2, cpu.stock(dust), "a refused dispatch consumes nothing");
        assertTrue(cpu.waitingFor.list.isEmpty());
        assertTrue(cpu.dispatch(grow));
        assertEquals(1, cpu.stock(dust));
    }

    /** The certified program must agree with the native task counts AE2 builds from
     * {@code patternTimes}, at every step, or a reload would cancel a live job. */
    @Test
    void programCountsMatchTheNativeTaskMapAtEveryStep() {
        var grow = new BenchPatternDetails(seed, 1, List.of(of(dust, 1)));
        var sprout = new BenchPatternDetails(dust, 2, List.of(of(seed, 1)));
        var plan = plan(dust, 3, Map.of(dust, 1L),
                List.of(block(3, step(variant(grow, new GenericStack(dust, 1)), 1),
                        step(variant(sprout, new GenericStack(seed, 1)), 1))));
        var cpu = new Cpu(plan);
        cpu.inventory.insert(dust, 1, Actionable.MODULATE);
        assertTrue(cpu.job.validateRemainingTasks(cpu.tasks), "the freshly submitted job is in sync");

        int dispatches = 0;
        while (!cpu.job.done() && dispatches < 16) {
            IPatternDetails next = cpu.tasks.keySet().stream().filter(cpu.job::allows).findFirst().orElse(null);
            assertNotNull(next, "the cursor's next pattern must exist in the native task map");
            assertTrue(cpu.dispatch(next));
            dispatches++;
            for (var key : new ArrayList<>(List.of(seed, dust))) cpu.returnToCpu(key, 64);
        }
        assertTrue(cpu.job.done());
        assertTrue(cpu.tasks.isEmpty());
        assertTrue(cpu.job.validateRemainingTasks(cpu.tasks), "completion keeps native tasks empty");
        assertEquals(4, cpu.stock(dust));
        assertEquals(3, cpu.deliver(dust));
        assertEquals(1,cpu.stock(dust));
    }
}
