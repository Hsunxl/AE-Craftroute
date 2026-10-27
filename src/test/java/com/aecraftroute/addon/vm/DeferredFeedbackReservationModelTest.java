package com.aecraftroute.addon.vm;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ListCraftingInventory;
import com.aecraftroute.addon.bench.BenchAEKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Counter-model tests for the ledger used by both production growth CPU bridges. */
class DeferredFeedbackReservationModelTest {
    final BenchAEKey alternative=BenchAEKey.of("deferred_alternative"),feedback=BenchAEKey.of("deferred_final");
    IPatternDetails consumer(){return consumer(1,1,1,false);}
    IPatternDetails consumer(long firstAmount,long secondAmount,long multiplier,boolean returned) {
        return new IPatternDetails() {
            public AEItemKey getDefinition(){return null;}
            public List<GenericStack> getOutputs(){return List.of(new GenericStack(feedback,1));}
            public IInput[] getInputs(){return new IInput[]{new IInput() {
                public GenericStack[] getPossibleInputs(){return new GenericStack[]{
                        new GenericStack(alternative,firstAmount),new GenericStack(feedback,secondAmount)};}
                public long getMultiplier(){return multiplier;}
                public boolean isValid(AEKey key,Level level){return key.equals(alternative)||key.equals(feedback);}
                public AEKey getRemainingKey(AEKey key){return returned?key:null;}
            }};}
        };
    }
    final class Ledger {
        final ListCraftingInventory inventory=new ListCraftingInventory(k->{});
        final DeferredFeedbackReservation accounting=new DeferredFeedbackReservation();
        long waiting,held,delivered,restoreSeed;
        Ledger(long waiting){this.waiting=waiting;}
        long stored(){return inventory.extract(feedback,Long.MAX_VALUE,Actionable.SIMULATE);}
        long receive(long incoming) {
            // Held packets have already arrived, although their final use is undecided.
            long keep=accounting.offerBuffer(incoming,waiting);
            inventory.insert(feedback,keep,Actionable.MODULATE);accounting.retain(keep);held=accounting.held();
            return keep;
        }
        void acceptedPush(KeyCounter[] inputs) {
            long consumed=DeferredFeedbackReservation.consumedInput(consumer(),inputs,feedback);
            long used=accounting.consumed(consumed);held=accounting.held();waiting-=used;
        }
        long releaseWhenNoConsumers(long requesterCapacity,Actionable mode) {
            var offer=accounting.offerRelease(0,stored(),restoreSeed,Math.max(0,requesterCapacity));
            long restore=offer.seed();long released=offer.directLimit();
            if(mode==Actionable.SIMULATE)return released;
            // Startup capital is surplus returned to ME on native completion, never order delivery.
            accounting.settle(restore);held=accounting.held();waiting-=restore;restoreSeed-=restore;
            inventory.extract(feedback,released,Actionable.MODULATE);
            accounting.settle(released);held=accounting.held();waiting-=released;delivered+=released;
            return released;
        }
        void releaseWhenNoConsumers(){releaseWhenNoConsumers(Long.MAX_VALUE,Actionable.MODULATE);}
    }

    @Test void laterAlternativeDoesNotPermanentlyWithholdFinalOutput() {
        var ledger=new Ledger(1);
        assertEquals(1,ledger.receive(1));
        assertEquals(1,ledger.held);assertEquals(1,ledger.waiting);assertEquals(0,ledger.delivered);
        // A producer finishes after feedback retention but before this task dispatches.
        ledger.inventory.insert(alternative,1,Actionable.MODULATE);
        var inputs=CraftingCpuHelper.extractPatternInputs(consumer(),ledger.inventory,null,new KeyCounter(),new KeyCounter());
        assertNotNull(inputs);assertEquals(1,inputs[0].get(alternative));
        ledger.acceptedPush(inputs);
        assertEquals(1,ledger.held); // The real executor chose the other candidate.
        ledger.releaseWhenNoConsumers();
        assertEquals(1,ledger.delivered);assertEquals(0,ledger.held);assertEquals(0,ledger.waiting);
        assertEquals(0,ledger.stored());
    }

    @Test void consumedFeedbackIsCreditedOnceAndNotDeliveredAsWell() {
        var ledger=new Ledger(2);
        assertEquals(1,ledger.receive(1));
        var inputs=CraftingCpuHelper.extractPatternInputs(consumer(),ledger.inventory,null,new KeyCounter(),new KeyCounter());
        assertNotNull(inputs);assertEquals(1,inputs[0].get(feedback));
        ledger.acceptedPush(inputs);
        assertEquals(0,ledger.held);assertEquals(1,ledger.waiting);assertEquals(0,ledger.delivered);
        assertEquals(1,ledger.receive(1));
        ledger.releaseWhenNoConsumers();
        assertEquals(1,ledger.delivered);assertEquals(0,ledger.waiting);assertEquals(0,ledger.stored());
    }

    @Test void rejectedProviderPushDoesNotConsumeDeferredCreditOrAcceptPacketTwice() {
        var ledger=new Ledger(1);assertEquals(1,ledger.receive(1));
        assertEquals(0,ledger.receive(1)); // waiting includes the already held packet.
        var inputs=CraftingCpuHelper.extractPatternInputs(consumer(),ledger.inventory,null,new KeyCounter(),new KeyCounter());
        assertNotNull(inputs);
        CraftingCpuHelper.reinjectPatternInputs(ledger.inventory,inputs); // provider refused: no acceptedPush.
        assertEquals(1,ledger.held);assertEquals(1,ledger.waiting);assertEquals(1,ledger.stored());
        ledger.releaseWhenNoConsumers();assertEquals(1,ledger.delivered);assertEquals(0,ledger.waiting);
    }

    @Test void requesterRejectionAndPartialReleaseKeepUndeliveredStockAndCredit() {
        var ledger=new Ledger(2);assertEquals(2,ledger.receive(2));
        assertEquals(1,ledger.releaseWhenNoConsumers(1,Actionable.SIMULATE));
        assertEquals(2,ledger.held);assertEquals(2,ledger.waiting);assertEquals(2,ledger.stored());
        assertEquals(0,ledger.releaseWhenNoConsumers(0,Actionable.MODULATE));
        assertEquals(2,ledger.held);assertEquals(2,ledger.waiting);assertEquals(0,ledger.delivered);
        assertEquals(1,ledger.releaseWhenNoConsumers(1,Actionable.MODULATE));
        assertEquals(1,ledger.held);assertEquals(1,ledger.waiting);assertEquals(1,ledger.stored());
        ledger.releaseWhenNoConsumers();
        assertEquals(2,ledger.delivered);assertEquals(0,ledger.waiting);assertEquals(0,ledger.stored());
    }

    @Test void releasingUnusedFeedbackRestoresStartupSeedWithoutCountingItAsDelivery() {
        var ledger=new Ledger(2);ledger.restoreSeed=1;
        assertEquals(2,ledger.receive(2));
        assertEquals(1,ledger.releaseWhenNoConsumers(Long.MAX_VALUE,Actionable.SIMULATE));
        assertEquals(1,ledger.restoreSeed);assertEquals(2,ledger.held);assertEquals(2,ledger.waiting);
        ledger.releaseWhenNoConsumers();
        assertEquals(1,ledger.delivered);assertEquals(1,ledger.stored());
        assertEquals(0,ledger.held);assertEquals(0,ledger.waiting);assertEquals(0,ledger.restoreSeed);
    }

    @Test void sharedAccountingOffersDoNotCommitAndRestoreChecksNativeState() {
        var accounting=new DeferredFeedbackReservation();accounting.restore(2,3,2);
        assertEquals(1,accounting.offerBuffer(1,3));
        assertEquals(new DeferredFeedbackReservation.Release(0,0),accounting.offerRelease(1,2,0,3));
        assertEquals(2,accounting.held());
        var restored=new DeferredFeedbackReservation();restored.restore(accounting.held(),3,2);
        assertEquals(2,restored.held());
        assertThrows(IllegalArgumentException.class,()->restored.restore(2,3,1));
        assertEquals(2,restored.held()); // Invalid load cannot partially overwrite the ledger.
        assertThrows(IllegalStateException.class,()->restored.offerBuffer(1,1));
    }

    @Test void sharedAccountingRejectsDoubleSettlementNegativeCountsAndOverflowAtomically() {
        var accounting=new DeferredFeedbackReservation();accounting.retain(1);
        assertThrows(IllegalStateException.class,()->accounting.settle(2));assertEquals(1,accounting.held());
        assertThrows(IllegalArgumentException.class,()->accounting.consumed(-1));assertEquals(1,accounting.held());
        assertEquals(1,accounting.consumed(5));assertEquals(0,accounting.held());
        accounting.retain(Long.MAX_VALUE);
        assertThrows(ArithmeticException.class,()->accounting.retain(1));
        assertEquals(Long.MAX_VALUE,accounting.held());
    }

    @Test void potentialDemandIncludesNonPrimaryCandidateAndSaturatesHugeTaskCounts() {
        var pattern=consumer();
        assertEquals(3,DeferredFeedbackReservation.potentialInput(java.util.Map.of(pattern,3L),Long::longValue,feedback,null));
        assertEquals(Long.MAX_VALUE,DeferredFeedbackReservation.potentialInput(
                java.util.Map.of(consumer(1,1,2,false),Long.MAX_VALUE),Long::longValue,feedback,null));
        assertEquals(0,DeferredFeedbackReservation.potentialInput(java.util.Map.of(pattern,0L),Long::longValue,feedback,null));
    }

    @Test void slotAlternativesUseMaximumNotSumAndCatalystMustRemainAvailableUntilDispatch() {
        assertEquals(24,DeferredFeedbackReservation.potentialInput(
                java.util.Map.of(consumer(3,1,4,false),2L),Long::longValue,feedback,null));
        var returned=consumer(1,1,1,true);
        assertEquals(3,DeferredFeedbackReservation.potentialInput(java.util.Map.of(returned,3L),Long::longValue,feedback,null));
        var counter=new KeyCounter();counter.add(feedback,1);
        assertEquals(1,DeferredFeedbackReservation.consumedInput(returned,new KeyCounter[]{counter},feedback));
    }

    @Test void returnedCatalystLeavesCpuThenReentersAsANewExpectedPacket() {
        var ledger=new Ledger(1);ledger.receive(1);
        var pattern=consumer(1,1,1,true);
        long pending=DeferredFeedbackReservation.potentialInput(java.util.Map.of(pattern,1L),Long::longValue,feedback,null);
        assertEquals(new DeferredFeedbackReservation.Release(0,0),ledger.accounting.offerRelease(pending,ledger.stored(),0,2));
        var outputs=new KeyCounter();var containers=new KeyCounter();
        var extracted=CraftingCpuHelper.extractPatternInputs(pattern,ledger.inventory,null,outputs,containers);
        assertNotNull(extracted);assertEquals(1,containers.get(feedback));
        long snapshot=DeferredFeedbackReservation.consumedInput(pattern,extracted,feedback);
        assertEquals(1,ledger.accounting.consumed(snapshot));
        ledger.held=ledger.accounting.held();ledger.waiting-=snapshot;
        assertEquals(0,ledger.held);assertEquals(0,ledger.stored());assertEquals(0,ledger.waiting);
        // Match real executeCrafting ordering: accepted push, then output/container expectations.
        long expected=outputs.get(feedback)+containers.get(feedback);
        ledger.waiting+=expected;assertEquals(2,expected);
        assertEquals(expected,ledger.receive(expected));
        ledger.releaseWhenNoConsumers();
        assertEquals(2,ledger.delivered);assertEquals(0,ledger.waiting);assertEquals(0,ledger.held);
    }

    @Test void unresolvableCandidateSlotsHoldCreditInsteadOfCancellingTheJob() {
        var broken=new IPatternDetails() {
            public AEItemKey getDefinition(){return null;}
            public List<GenericStack> getOutputs(){return List.of(new GenericStack(feedback,1));}
            public IInput[] getInputs(){return new IInput[]{null};}
        };
        assertThrows(IllegalArgumentException.class,()->DeferredFeedbackReservation.potentialInput(
                java.util.Map.of(broken,1L),Long::longValue,feedback,null));
        assertEquals(Long.MAX_VALUE,DeferredFeedbackReservation.pendingInputOrUnknown(
                java.util.Map.of(broken,1L),Long::longValue,feedback,null));
        var accounting=new DeferredFeedbackReservation();accounting.restore(2,4,4);
        assertEquals(new DeferredFeedbackReservation.Release(0,0),
                accounting.offerRelease(Long.MAX_VALUE,4,2,4));
        assertEquals(2,accounting.held());
    }

    @Test void savedTaskCountsBeyondTheDemandCapAreRejectedBeforeTheyCanWedgeRelease() {
        var pattern=consumer();
        DeferredFeedbackReservation.requireSaneRuns(java.util.Map.of(pattern,3L),Long::longValue);
        assertThrows(IllegalArgumentException.class,()->DeferredFeedbackReservation.requireSaneRuns(
                java.util.Map.of(pattern,Long.MAX_VALUE),Long::longValue));
        assertThrows(IllegalArgumentException.class,()->DeferredFeedbackReservation.requireSaneRuns(
                java.util.Map.of(pattern,Long.MAX_VALUE,consumer(1,1,1,true),Long.MAX_VALUE),Long::longValue));
        assertThrows(IllegalArgumentException.class,()->DeferredFeedbackReservation.requireSaneRuns(
                java.util.Map.of(pattern,-1L),Long::longValue));
        assertThrows(IllegalArgumentException.class,()->DeferredFeedbackReservation.requireSaneRuns(
                java.util.Map.of(pattern,DeferredFeedbackReservation.MAX_TASK_RUNS+1),Long::longValue));
        // Exactly capped counts stay loadable: the cap bounds demand, not job size in bytes.
        DeferredFeedbackReservation.requireSaneRuns(java.util.Map.of(pattern,DeferredFeedbackReservation.MAX_TASK_RUNS),Long::longValue);
        DeferredFeedbackReservation.requireSaneRuns(java.util.Map.of(),Long::longValue);
    }

    @Test void consumptionSnapshotSurvivesProviderClearingInputCounters() {
        var ledger=new Ledger(1);ledger.receive(1);
        var pattern=consumer();
        var extracted=CraftingCpuHelper.extractPatternInputs(pattern,ledger.inventory,null,new KeyCounter(),new KeyCounter());
        long snapshot=DeferredFeedbackReservation.consumedInput(pattern,extracted,feedback);
        assertEquals(1,snapshot);
        for(var counter:extracted)counter.clear(); // successful provider owns the mutable inputs
        assertEquals(1,ledger.accounting.consumed(snapshot));
        assertEquals(0,ledger.accounting.held());
    }
}
