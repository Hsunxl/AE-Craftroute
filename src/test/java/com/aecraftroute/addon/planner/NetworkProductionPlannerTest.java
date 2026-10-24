package com.aecraftroute.addon.planner;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import appeng.crafting.inv.ListCraftingInventory;
import com.aecraftroute.addon.bench.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.aecraftroute.addon.bench.BenchPatternDetails.InputSpec.*;

class NetworkProductionPlannerTest {
    private final BenchAEKey a=BenchAEKey.of("generic_a"), b=BenchAEKey.of("generic_b"), c=BenchAEKey.of("generic_c"),
            fuel=BenchAEKey.of("generic_fuel"), target=BenchAEKey.of("generic_target"), tool=BenchAEKey.of("generic_tool");
    private CertifiedCraftingPlan plan(List<IPatternDetails> patterns,Map<AEKey,Long> stock,AEKey goal,long amount) {
        return NetworkProductionPlanner.plan(goal,amount,k->patterns.stream().filter(p->p.getOutputs().stream().anyMatch(s->s.what().equals(k))).toList(),k->stock.getOrDefault(k,0L));
    }
    @Test void threeNodeLoopWithCompetingDownstreamConsumerUsesOnlyActualReturnedStock() {
        var ab=new BenchPatternDetails(b,3,List.of(of(a,1),of(fuel,1)));
        var bc=new BenchPatternDetails(c,1,List.of(of(b,1)));
        var ca=new BenchPatternDetails(a,1,List.of(of(c,1)));
        var downstream=new BenchPatternDetails(target,1,List.of(of(a,10),of(b,5)));
        var result=plan(List.of(ab,bc,ca,downstream),Map.of(a,1L,fuel,100L),target,5);
        execute(result,true);
    }
    @Test void taggedVariantsUseAvailableAlternativeNotMissingPrimary() {
        var p=new BenchPatternDetails(target,1,List.of(fuzzy(a,1,b)));
        var result=plan(List.of(p),Map.of(b,4L),target,4);
        assertEquals(4,result.usedItems().get(b)); assertEquals(0,result.usedItems().get(a)); execute(result,false);
    }
    @Test void repeatedSlotsAreAggregatedAndExtractionRollsBack() {
        var p=new BenchPatternDetails(target,1,List.of(of(a,1),of(a,1)));
        var result=plan(List.of(p),Map.of(a,2L),target,1);
        var inventory=new ListCraftingInventory(k -> {}); inventory.insert(a,1,Actionable.MODULATE);
        var job=new ProductionJob(result);
        assertNull(job.extract(p,inventory,null,new KeyCounter(),new KeyCounter()));
        assertEquals(1,inventory.extract(a,10,Actionable.SIMULATE)); assertTrue(job.allows(p));
        assertFalse(job.done());
    }
    @Test void containerReturnsAreReusableAndNotCreditedBeforeArrival() {
        var p=new BenchPatternDetails(target,1,List.of(returned(tool,1),of(fuel,1)));
        var result=plan(List.of(p),Map.of(tool,1L,fuel,20L),target,20);
        assertEquals(1,result.usedItems().get(tool)); execute(result,true);
    }
    @Test void alternativesMayBeMixedInOneOrder() {
        var p=new BenchPatternDetails(target,1,List.of(of(a,1)));
        var q=new BenchPatternDetails(target,1,List.of(of(b,1)));
        var result=plan(List.of(p,q),Map.of(a,1L,b,1L),target,2);
        assertEquals(2,result.patternTimes().size()); execute(result,true);
    }
    @Test void noSeedAndPureConversionNeverProduceFalseFeasiblePlans() {
        var grow=new BenchPatternDetails(a,2,List.of(of(a,1),of(fuel,1)));
        var error=assertThrows(ProductionPlanningException.class,()->plan(List.of(grow),Map.of(fuel,100L),a,1));
        assertEquals(ProductionPlanningException.Reason.UNREACHABLE,error.reason());
        var p=new BenchPatternDetails(a,1,List.of(of(b,1)));
        var q=new BenchPatternDetails(b,1,List.of(of(a,1)));
        assertEquals(ProductionPlanningException.Reason.UNREACHABLE,
                assertThrows(ProductionPlanningException.class,()->plan(List.of(p,q),Map.of(a,99L),a,1)).reason());
    }
    @Test void mixedSlotAllocationUsesBothVariantsInOneFiring() {
        var p=new BenchPatternDetails(target,1,List.of(new BenchPatternDetails.InputSpec(a,1,2,List.of(b))));
        var result=plan(List.of(p),Map.of(a,1L,b,1L),target,1);
        assertEquals(1,result.usedItems().get(a)); assertEquals(1,result.usedItems().get(b)); execute(result,true);
    }
    @Test void outputStockIsRestoredBeforeNetDelivery() {
        var p=new BenchPatternDetails(a,3,List.of(of(a,1),of(fuel,1)));
        var result=plan(List.of(p),Map.of(a,100L,fuel,100L),a,50);
        assertTrue(result.usedItems().get(a)<=100); execute(result,true);
    }
    /** CPU protocol model drives the actual ProductionJob extraction/acceptance code,
     * delaying every packet and returning outputs in reverse order. */
    private void execute(CertifiedCraftingPlan plan,boolean delay) {
        var inventory=new ListCraftingInventory(k -> {});
        for(var e:plan.usedItems()) inventory.insert(e.getKey(),e.getLongValue(),Actionable.MODULATE);
        var job=new ProductionJob(plan); var cursor=new ProductionCursor<>(plan.program());
        var pending=new ArrayDeque<GenericStack>(); var count=new HashMap<IPatternDetails,Long>();
        for(int tick=0;tick<100000 && (!job.done() || !pending.isEmpty());tick++) {
            if(!pending.isEmpty() && (!delay || tick%3==0)) {
                var stack=pending.removeLast(); inventory.insert(stack.what(),stack.amount(),Actionable.MODULATE);
            }
            if(job.done()) continue;
            var variant=cursor.current(); var p=variant.pattern(); var outputs=new KeyCounter(); var containers=new KeyCounter();
            var inputs=job.extract(p,inventory,null,outputs,containers);
            if(inputs==null) continue;
            job.accepted(p); cursor.accepted(variant); count.merge(p,1L,Long::sum);
            for(var e:outputs) pending.add(new GenericStack(e.getKey(),e.getLongValue()));
            for(var e:containers) pending.add(new GenericStack(e.getKey(),e.getLongValue()));
        }
        assertTrue(job.done()); assertTrue(pending.isEmpty()); assertEquals(plan.patternTimes(),count);
        var key=plan.finalOutput().what();
        assertTrue(inventory.extract(key,Long.MAX_VALUE,Actionable.SIMULATE)>=plan.usedItems().get(key)+plan.finalOutput().amount());
    }
}
