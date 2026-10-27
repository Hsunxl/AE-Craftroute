package com.aecraftroute.addon.vm;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.aecraftroute.addon.bench.BenchAEKey;
import com.aecraftroute.addon.bench.BenchPatternDetails;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.aecraftroute.addon.bench.BenchPatternDetails.InputSpec.of;
import static org.junit.jupiter.api.Assertions.*;

class TwoNodeGrowthPlannerTest {
    final BenchAEKey dust=BenchAEKey.of("legacy_dust"),fuel=BenchAEKey.of("legacy_fuel"),
            crystal=BenchAEKey.of("legacy_crystal");
    IPatternDetails production() {return new BenchPatternDetails(crystal,2,List.of(of(dust,1),of(fuel,1)));}
    IPatternDetails recycle() {return new BenchPatternDetails(dust,1,List.of(of(crystal,1)));}

    @Test void supportedRingStillDispatchesRecyclerFirst() {
        var p=production();var r=recycle();
        var tasks=new LinkedHashMap<IPatternDetails,Long>();tasks.put(p,2L);tasks.put(r,1L);
        assertTrue(TwoNodeGrowthPlanner.containsRing(tasks.keySet()));
        assertEquals(List.of(r,p),new ArrayList<>(TwoNodeGrowthPlanner.prioritizeFeedback(tasks).keySet()));
    }

    @Test void nullOutputsAndNullInputSlotsAreNotExecutableRingEvidence() {
        IPatternDetails noOutputs=new IPatternDetails() {
            public AEItemKey getDefinition(){return null;}
            public IInput[] getInputs(){return new IInput[0];}
            public List<GenericStack> getOutputs(){return null;}
        };
        IPatternDetails nullSlot=new IPatternDetails() {
            public AEItemKey getDefinition(){return null;}
            public IInput[] getInputs(){return new IInput[]{null};}
            public List<GenericStack> getOutputs(){return List.of(new GenericStack(crystal,2));}
        };
        for(var malformed:List.of(noOutputs,nullSlot)) {
            assertFalse(TwoNodeGrowthPlanner.containsRing(List.of(malformed,recycle())));
            var tasks=new LinkedHashMap<IPatternDetails,Long>();tasks.put(malformed,1L);tasks.put(recycle(),1L);
            assertEquals(tasks,TwoNodeGrowthPlanner.prioritizeFeedback(tasks));
        }
    }

    @Test void overflowingLegacyInputKeepsOriginalTasksWithoutPartialReordering() {
        var overflow=new BenchPatternDetails(crystal,2,List.of(
                new BenchPatternDetails.InputSpec(dust,Long.MAX_VALUE,2),of(fuel,1)));
        var tasks=new LinkedHashMap<IPatternDetails,Long>();tasks.put(overflow,1L);tasks.put(recycle(),1L);
        assertFalse(TwoNodeGrowthPlanner.containsRing(tasks.keySet()));
        assertSame(tasks,TwoNodeGrowthPlanner.prioritizeFeedback(tasks));
    }

    /** Two recyclers for the same production line are ambiguous: the planner declines
     * rather than guessing an order, and says so at debug level instead of silently
     * falling back to the native HashMap order. */
    @Test void ambiguousRingsAreDeclinedInsteadOfGuessed() {
        var p=production();
        var dustRecycler=recycle();
        var fuelRecycler=new BenchPatternDetails(fuel,1,List.of(of(crystal,1)));
        assertTrue(TwoNodeGrowthPlanner.containsRing(List.of(p,dustRecycler)));
        assertTrue(TwoNodeGrowthPlanner.containsRing(List.of(p,fuelRecycler)));
        assertFalse(TwoNodeGrowthPlanner.containsRing(List.of(p,dustRecycler,fuelRecycler)));
        var tasks=new LinkedHashMap<IPatternDetails,Long>();
        tasks.put(p,2L);tasks.put(dustRecycler,1L);tasks.put(fuelRecycler,1L);
        // No ring is modelled, so the dispatch order must come back unchanged (the method
        // still returns a fresh map, unlike the overflow path which returns the input).
        assertEquals(List.copyOf(tasks.keySet()),
                List.copyOf(TwoNodeGrowthPlanner.prioritizeFeedback(tasks).keySet()));
    }
}
