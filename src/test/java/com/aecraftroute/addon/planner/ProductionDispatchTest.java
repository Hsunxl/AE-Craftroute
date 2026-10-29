package com.aecraftroute.addon.planner;

import appeng.api.config.Actionable;
import appeng.api.stacks.*;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ListCraftingInventory;
import com.aecraftroute.addon.api.CraftRouteDispatch;
import com.aecraftroute.addon.bench.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static com.aecraftroute.addon.bench.BenchPatternDetails.InputSpec.of;
import static org.junit.jupiter.api.Assertions.*;
import static com.aecraftroute.addon.planner.ProductionExecutionTest.*;

class ProductionDispatchTest {
    final BenchAEKey input=BenchAEKey.of("dispatch_input"),output=BenchAEKey.of("dispatch_output");
    final BenchPatternDetails pattern=new BenchPatternDetails(output,1,List.of(of(input,1)));
    ProductionJob job(long count) {
        return new ProductionJob(plan(output,count,Map.of(input,count),
                List.of(block(1,step(variant(pattern,new GenericStack(input,1)),count)))));
    }
    KeyCounter[] holders() {var holder=new KeyCounter();holder.add(input,1);return new KeyCounter[]{holder};}

    @Test void rejectionRefundAndNextProviderUseTheSameExtractedHolders() {
        var job=job(2);var inventory=new ListCraftingInventory(k->{});inventory.insert(input,2,Actionable.MODULATE);
        var outputs=new KeyCounter();var inputs=job.extract(pattern,inventory,null,outputs,new KeyCounter());
        assertNotNull(inputs);var before=job.position();
        assertFalse(job.dispatch(pattern,inputs,()->fail("rejection is not a commit"),()->false));
        assertEquals(before,job.position());assertEquals(1,inventory.list.get(input));
        assertTrue(job.dispatch(pattern,inputs,()->{},()->true));
        assertEquals(1,job.position().completed());assertEquals(1,inventory.list.get(input));
        var next=job.extract(pattern,inventory,null,new KeyCounter(),new KeyCounter());
        assertFalse(job.dispatch(pattern,next,()->{},()->false));
        CraftingCpuHelper.reinjectPatternInputs(inventory,next);
        assertEquals(1,inventory.list.get(input));assertEquals(1,job.remainingCounts().get(pattern));
    }
    @Test void cleanupFailureAfterReceiptPreservesNativeSuccessAndOneCursorAdvance() {
        var job=job(2);var inputs=holders();var dirty=new AtomicInteger();
        assertTrue(job.dispatch(pattern,inputs,dirty::incrementAndGet,()->{
            assertTrue(CraftRouteDispatch.confirmAccepted(pattern,inputs));
            assertTrue(CraftRouteDispatch.confirmAccepted(pattern,inputs));
            throw new IllegalStateException("provider cleanup");
        }));
        assertFalse(job.invalid());assertEquals(1,job.position().completed());assertEquals(1,dirty.get());
        assertFalse(CraftRouteDispatch.confirmAccepted(pattern,inputs));
    }
    @Test void unknownFailureFreezesTheJobAndDoesNotRefundUncertainHolders() {
        var job=job(2);var inputs=holders();var before=job.position();
        assertThrows(IllegalStateException.class,()->job.dispatch(pattern,inputs,()->{},()->{
            throw new IllegalStateException("no ownership receipt");
        }));
        assertTrue(job.invalid());assertEquals(before,job.position());
        assertEquals(1,inputs[0].get(input));
        assertFalse(job.dispatch(pattern,inputs,()->{},()->{fail("invalid job dispatched again");return true;}));
    }
    @Test void nestedHooksDoNotAdvanceTheSameJobTwice() {
        var job=job(3);var inputs=holders();var dirty=new AtomicInteger();
        assertTrue(job.dispatch(pattern,inputs,dirty::incrementAndGet,()->
                job.dispatch(pattern,inputs,dirty::incrementAndGet,()->true)));
        assertEquals(1,job.position().completed());assertEquals(1,dirty.get());
    }
    @Test void aLegacyCursorAdvanceInsideTheProviderCannotBeCommittedTwice() {
        var job=job(3);
        assertThrows(IllegalStateException.class,()->job.dispatch(pattern,holders(),()->{},()->{
            job.accepted(pattern);return true;
        }));
        assertTrue(job.invalid());assertEquals(1,job.position().completed());
    }
    @Test void synchronousCallbacksNeverExposeFinalOutputBeforeNativeReturnRegistration() {
        var job=job(1);var inventory=new ListCraftingInventory(k->{});var inputs=holders();
        assertTrue(job.dispatch(pattern,inputs,()->{},()->{
            // Native waitingFor is registered only after pushPattern returns. A
            // synchronous provider must retain rejected output for a later retry.
            assertEquals(0,job.deliverableAmount(inventory.list,output,1));
            assertTrue(CraftRouteDispatch.confirmAccepted(pattern,inputs));
            assertEquals(0,job.deliverableAmount(inventory.list,output,1));
            return true;
        }));
        inventory.insert(output,1,Actionable.MODULATE);
        assertEquals(1,job.deliverableAmount(inventory.list,output,1));
    }
    @Test void acceptedCursorPersistsWithoutAnyThreadLocalScope() throws Exception {
        var persistence=new ProductionPersistenceTest();var p=persistence.pattern(true);var job=persistence.job(p);
        var inputs=new KeyCounter[]{new KeyCounter(),new KeyCounter()};
        assertTrue(job.dispatch(p,inputs,()->{},()->{
            CraftRouteDispatch.confirmAccepted(p,inputs);throw new IllegalStateException("cleanup");
        }));
        var restored=persistence.load(persistence.binary(persistence.save(job)),p);
        assertFalse(restored.invalid());assertEquals(job.position(),restored.position());
        assertTrue(restored.validateRemainingTasks(Map.of(p,19L)));
        assertFalse(CraftRouteDispatch.confirmAccepted(p,inputs));
    }
    @Test void unknownOwnershipCannotResumeAfterNbtReload() throws Exception {
        var persistence=new ProductionPersistenceTest();var p=persistence.pattern(true);var job=persistence.job(p);
        assertThrows(IllegalStateException.class,()->job.dispatch(p,new KeyCounter[0],()->{},()->{
            throw new IllegalStateException("unknown ownership");
        }));
        var saved=persistence.binary(persistence.save(job));assertTrue(saved.getBoolean("invalid"));
        var restored=persistence.load(saved,p);assertTrue(restored.invalid());assertFalse(restored.allows(p));
    }
    @Test void emptyInputPatternsStillCommitOnlyOneNativeCraft() {
        var free=new BenchPatternDetails(output,1,List.of());
        var job=new ProductionJob(plan(output,2,Map.of(),List.of(block(1,step(variant(free),2)))));
        var holders=new KeyCounter[0];
        assertTrue(job.dispatch(free,holders,()->{},()->{
            CraftRouteDispatch.confirmAccepted(free,holders);return true;
        }));
        assertEquals(1,job.remainingCounts().get(free));assertFalse(job.done());
    }
    @Test void malformedHoldersAreRejectedBeforeCallingProvider() {
        var job=job(1);
        assertThrows(NullPointerException.class,()->job.dispatch(pattern,new KeyCounter[]{null},()->{},()->{
            fail("malformed holders reached provider");return true;
        }));
        assertFalse(job.done());assertFalse(job.invalid());
    }
}
