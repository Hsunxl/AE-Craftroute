package com.aecraftroute.addon.planner;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import appeng.crafting.inv.ListCraftingInventory;
import com.aecraftroute.addon.bench.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.aecraftroute.addon.bench.BenchPatternDetails.InputSpec.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkPlanCacheTest {
    private final BenchAEKey ore=BenchAEKey.of("cache_ore"), out=BenchAEKey.of("cache_out"), tool=BenchAEKey.of("cache_tool");
    @BeforeEach void reset() { NetworkProductionPlanner.clearCache(); }
    private CertifiedCraftingPlan plan(List<IPatternDetails> patterns,Map<AEKey,Long> stock,long amount,boolean cached) {
        return NetworkProductionPlanner.plan(out,amount,
                k->patterns.stream().filter(p->p.getOutputs().stream().anyMatch(s->s.what().equals(k))).toList(),
                k->stock.getOrDefault(k,0L),ProductionPlanner.Limits.defaults(),cached);
    }
    @Test void hitRebindsEveryStepAndTaskToFreshPatternObjects() {
        var old=new BenchPatternDetails(out,1,List.of(of(ore,1)));
        var fresh=new BenchPatternDetails(out,1,List.of(of(ore,1)));
        var first=plan(List.of(old),Map.of(ore,5L),5,true);
        var next=plan(List.of(fresh),Map.of(ore,5L),5,true);
        assertEquals(1,NetworkProductionPlanner.cacheStats().hits());
        assertNotSame(first,next);
        assertSame(fresh,next.patternTimes().keySet().iterator().next());
        next.program().forEach(b->b.word().forEach(s->assertSame(fresh,s.recipe().pattern())));
        var inventory=new ListCraftingInventory(k->{});
        inventory.insert(ore,5,Actionable.MODULATE);
        var job=new ProductionJob(next);
        for(int i=0;i<5;i++) {
            assertNotNull(job.extract(fresh,inventory,null,new KeyCounter(),new KeyCounter()));
            job.accepted(fresh);
        }
        assertTrue(job.done());
    }
    @Test void callerCannotPoisonCachedCapitalByMutatingAPlan() {
        var p=new BenchPatternDetails(out,1,List.of(of(ore,1)));
        var first=plan(List.of(p),Map.of(ore,5L),5,true);
        first.usedItems().add(ore,1000);
        var next=plan(List.of(p),Map.of(ore,5L),5,true);
        assertEquals(5,next.usedItems().get(ore));
        assertEquals(1,NetworkProductionPlanner.cacheStats().hits());
    }
    @Test void sameFlowWithDifferentSlotLayoutRebindsCurrentAllocation() {
        var oneSlot=new BenchPatternDetails(out,1,List.of(of(ore,2)));
        var twoSlots=new BenchPatternDetails(out,1,List.of(of(ore,1),of(ore,1)));
        plan(List.of(oneSlot),Map.of(ore,2L),1,true);
        var next=plan(List.of(twoSlots),Map.of(ore,2L),1,true);
        assertEquals(1,NetworkProductionPlanner.cacheStats().hits());
        var inv=new ListCraftingInventory(k->{}); inv.insert(ore,2,Actionable.MODULATE);
        var inputs=new ProductionJob(next).extract(twoSlots,inv,null,new KeyCounter(),new KeyCounter());
        assertNotNull(inputs); assertEquals(2,inputs.length);
        assertEquals(1,inputs[0].get(ore)); assertEquals(1,inputs[1].get(ore));
        assertEquals(0,inv.extract(ore,10,Actionable.SIMULATE));
    }
    @Test void removedRouteAndChangedStockCannotReuseProof() {
        var p=new BenchPatternDetails(out,1,List.of(of(ore,1)));
        plan(List.of(p),Map.of(ore,5L),5,true);
        assertEquals(ProductionPlanningException.Reason.UNREACHABLE,
                assertThrows(ProductionPlanningException.class,()->plan(List.of(),Map.of(ore,5L),5,true)).reason());
        assertThrows(ProductionPlanningException.class,()->plan(List.of(p),Map.of(ore,4L),5,true));
        assertEquals(0,NetworkProductionPlanner.cacheStats().hits());
    }
    @Test void changedRemainderAndQuantityInvalidateProof() {
        var reusable=new BenchPatternDetails(out,1,List.of(returned(tool,1),of(ore,1)));
        plan(List.of(reusable),Map.of(tool,1L,ore,5L),5,true);
        var consumed=new BenchPatternDetails(out,1,List.of(of(tool,1),of(ore,1)));
        assertThrows(ProductionPlanningException.class,()->plan(List.of(consumed),Map.of(tool,1L,ore,5L),5,true));
        var costly=new BenchPatternDetails(out,1,List.of(returned(tool,1),of(ore,2)));
        assertThrows(ProductionPlanningException.class,()->plan(List.of(costly),Map.of(tool,1L,ore,5L),5,true));
        assertEquals(0,NetworkProductionPlanner.cacheStats().hits());
    }
    @Test void unsupportedFiniteDurabilityMustNotReuseInfiniteCatalystProof() {
        var p=new BenchPatternDetails(out,1,List.of(returned(tool,1),of(ore,1)));
        plan(List.of(p),Map.of(tool,1L,ore,5L),5,true);
        var finite=new BenchPatternDetails(out,1,List.of(finiteUse(tool,1,3),of(ore,1)));
        var error=assertThrows(ProductionPlanningException.class,()->plan(List.of(finite),Map.of(tool,1L,ore,5L),5,true));
        assertEquals(ProductionPlanningException.Reason.UNSUPPORTED,error.reason());
        assertEquals(0,NetworkProductionPlanner.cacheStats().hits());
    }
    @Test void cachedAndUncachedProgramsMatchIncludingMixedAllocations() {
        var p=new BenchPatternDetails(out,1,List.of(new BenchPatternDetails.InputSpec(ore,1,2,List.of(tool))));
        var stock=Map.<AEKey,Long>of(ore,1L,tool,1L);
        var direct=plan(List.of(p),stock,1,false);
        plan(List.of(p),stock,1,true);
        var hit=plan(List.of(p),stock,1,true);
        assertEquals(direct.program(),hit.program());
        assertEquals(direct.patternTimes(),hit.patternTimes());
        assertEquals(direct.bytes(),hit.bytes());
        assertEquals(1,hit.usedItems().get(ore)); assertEquals(1,hit.usedItems().get(tool));
    }
}
