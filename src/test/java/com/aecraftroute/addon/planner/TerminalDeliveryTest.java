package com.aecraftroute.addon.planner;

import appeng.api.stacks.*;
import com.aecraftroute.addon.bench.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.aecraftroute.addon.bench.BenchPatternDetails.InputSpec.*;
import static com.aecraftroute.addon.planner.ProductionExecutionTest.*;

class TerminalDeliveryTest {
    final BenchAEKey target=BenchAEKey.of("terminal_target"),raw=BenchAEKey.of("terminal_raw"),other=BenchAEKey.of("terminal_byproduct");
    ProductionJob simple(long packet,long firings,long goal) {
        var p=new BenchPatternDetails(target,packet,List.of(of(raw,1)));
        var job=new ProductionJob(plan(target,goal,Map.of(raw,firings),List.of(block(1,step(variant(p,new GenericStack(raw,1)),firings)))));
        for(long i=0;i<firings;i++)job.accepted(p);return job;
    }
    @Test void overestimatedTargetReturnsDoNotPinARealSatisfiedGoal() {
        var job=simple(3,2,4);var inventory=new KeyCounter();inventory.add(target,4);
        // Delivery is based only on physical terminal surplus, never a waitingFor estimate.
        assertEquals(4,job.deliverableAmount(inventory,target,4));
    }
    @Test void extraOutputIsNotOverdeliveredAndCanBeReturnedByNativeCleanup() {
        var job=simple(3,2,4);var inventory=new KeyCounter();inventory.add(target,6);inventory.add(other,17);
        assertEquals(4,job.deliverableAmount(inventory,target,4));inventory.add(target,-4);
        assertEquals(2,inventory.get(target));assertEquals(17,inventory.get(other));
        assertEquals(0,job.deliverableAmount(inventory,target,0));
    }
    @Test void missingTargetAndWrongVariantAreNeverInventedFromProgressOrByproducts() {
        var job=simple(1,1,1);var inventory=new KeyCounter();inventory.add(other,1000);
        assertEquals(0,job.deliverableAmount(inventory,target,1));
    }
    @Test void noDeliveryBeforeEveryCertifiedStepHasDispatchedEvenWithPlentyOfStock() {
        var p=new BenchPatternDetails(target,1,List.of(of(raw,1)));
        var job=new ProductionJob(plan(target,2,Map.of(raw,2L),List.of(block(1,step(variant(p,new GenericStack(raw,1)),2)))));
        var inventory=new KeyCounter();inventory.add(target,100);
        assertEquals(0,job.deliverableAmount(inventory,target,2));job.accepted(p);
        assertEquals(0,job.deliverableAmount(inventory,target,2));
        assertFalse(job.validateRemainingTasks(Map.of())); // a foreign path removed the final native task
        assertEquals(0,job.deliverableAmount(inventory,target,2));
    }
    ProductionJob growth(long firings) {
        var p=new BenchPatternDetails(target,2,List.of(of(target,1)));
        var job=new ProductionJob(plan(target,firings,Map.of(target,1L),List.of(block(1,step(variant(p,new GenericStack(target,1)),firings)))));
        for(int i=0;i<firings;i++)job.accepted(p);return job;
    }
    @Test void anUnderproducingMachineCannotSpendStartupCapitalToPretendTheGoalIsMet() {
        var job=growth(3);var inventory=new KeyCounter();inventory.add(target,3);
        assertEquals(2,job.deliverableAmount(inventory,target,3),"one working seed must not count as new output");
        inventory.add(target,-2);assertEquals(0,job.deliverableAmount(inventory,target,1));
        inventory.add(target,1);assertEquals(1,job.deliverableAmount(inventory,target,1));
    }
    @Test void partialRequesterAcceptanceRetainsCapitalAndRetriesWithoutDuplicateDelivery() {
        var job=growth(4);var inventory=new KeyCounter();inventory.add(target,5);long remaining=4,delivered=0;
        while(remaining>0) {
            long offered=job.deliverableAmount(inventory,target,remaining);assertTrue(offered>0);
            long accepted=1;inventory.add(target,-accepted);remaining-=accepted;delivered+=accepted;
        }
        assertEquals(4,delivered);assertEquals(1,inventory.get(target));
    }
    @Test void savedProgramRestoresTheSameCapitalWithoutChangingItsCursorOrNbtFormat() {
        var codecs=new ProductionPersistenceTest();var p=new BenchPatternDetails(codecs.target,2,List.of(of(codecs.target,1)));
        var job=new ProductionJob(plan(codecs.target,3,Map.of(codecs.target,1L),List.of(block(1,step(variant(p,new GenericStack(codecs.target,1)),3)))));
        for(int i=0;i<3;i++)job.accepted(p);
        var restored=codecs.load(codecs.save(job),p);assertTrue(restored.done());
        var inventory=new KeyCounter();inventory.add(codecs.target,3);
        assertEquals(2,restored.deliverableAmount(inventory,codecs.target,3));
        assertEquals(job.position(),restored.position());
    }
    @Test void backpressureDoesNotRemoveStockAndInvalidRequestsOfferNothing() {
        var job=simple(1,2,2);var inventory=new KeyCounter();inventory.add(target,2);
        assertEquals(2,job.deliverableAmount(inventory,target,2)); // requester/storage accepts zero: do not extract
        assertEquals(2,job.deliverableAmount(inventory,target,2));assertEquals(2,inventory.get(target));
        assertEquals(0,job.deliverableAmount(inventory,null,2));assertEquals(0,job.deliverableAmount(inventory,target,-1));
    }
}
