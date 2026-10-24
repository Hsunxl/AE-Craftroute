package com.aecraftroute.addon.planner;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.aecraftroute.addon.planner.ProductionPlanner.*;

class ProductionPlannerTest {
    private Recipe<String,String> r(String id,Map<String,Long> in,Map<String,Long> out) { return new Recipe<>(id,in,out); }
    private Result<String,String> solve(List<Recipe<String,String>> recipes,Map<String,Long> stock,String target,long n) {
        return new ProductionPlanner<>(recipes,stock,new Limits(100_000,10_000_000_000L)).solve(target,n);
    }
    @Test void arbitraryRingLengthsAndPacketRatios() {
        for(int length=1;length<=9;length++) {
            var recipes=new ArrayList<Recipe<String,String>>();
            for(int i=0;i<length;i++) recipes.add(r("p"+i,Map.of("k"+i,1L,"fuel",1L),Map.of("k"+((i+1)%length),i==length-1?3L:1L)));
            var result=solve(recipes,Map.of("k0",1L,"fuel",100_000L),"k0",1000);
            assertEquals(Status.FEASIBLE,result.status(),"ring length "+length);
            replay(recipes,result,Map.of("k0",1L,"fuel",100_000L),"k0",1000);
        }
    }
    @Test void noFreeSeedsOrStockDelivery() {
        var recipes=List.of(r("grow",Map.of("a",1L,"fuel",1L),Map.of("a",2L)));
        assertEquals(Status.UNREACHABLE,solve(recipes,Map.of("fuel",100L),"a",1).status());
        assertEquals(Status.UNREACHABLE,solve(recipes,Map.of("a",1000L),"a",1).status());
    }
    @Test void zeroAndNegativeCyclesTerminate() {
        for(long consumed:new long[]{1,2,10}) {
            var recipes=List.of(r("ab",Map.of("a",consumed),Map.of("b",1L)),r("ba",Map.of("b",1L),Map.of("a",1L)));
            assertEquals(Status.UNREACHABLE,solve(recipes,Map.of("a",100L),"a",1).status());
        }
    }
    @Test void mixedAlternativesAndSharedByproducts() {
        var recipes=List.of(r("one",Map.of("ore1",1L),Map.of("a",1L,"b",1L)),
                r("two",Map.of("ore2",1L),Map.of("a",1L)),r("final",Map.of("a",2L,"b",1L),Map.of("target",1L)));
        var stock=Map.of("ore1",1L,"ore2",1L);
        var result=solve(recipes,stock,"target",1);
        assertEquals(Status.FEASIBLE,result.status()); replay(recipes,result,stock,"target",1);
    }
    @Test void catalystAndBootstrapFromAlternative() {
        var recipes=List.of(r("seed",Map.of("ore",1L),Map.of("a",1L)),
                r("grow",Map.of("a",1L,"fuel",1L,"tool",1L),Map.of("a",3L,"tool",1L)));
        var stock=Map.of("ore",1L,"fuel",50L,"tool",1L);
        var result=solve(recipes,stock,"a",100); assertEquals(Status.FEASIBLE,result.status());
        replay(recipes,result,stock,"a",100);
    }
    @Test void bulkAmountsAreNotExpandedIntoIndividualObjects() {
        var recipes=List.of(r("grow",Map.of("a",1L,"fuel",1L),Map.of("b",2L)),r("return",Map.of("b",1L),Map.of("a",1L)));
        var result=solve(recipes,Map.of("a",1L,"fuel",1_000_000_000L),"a",1_000_000_000L);
        assertEquals(Status.FEASIBLE,result.status()); assertTrue(result.program().size()<100);
    }
    @Test void limitsNeverBecomeMissing() {
        var recipes=List.of(r("a",Map.of("b",1L),Map.of("a",1L)),r("b",Map.of("c",1L),Map.of("b",1L)));
        assertEquals(Status.LIMIT,new ProductionPlanner<>(recipes,Map.of("c",1L),new Limits(1,1_000_000_000)).solve("a",1).status());
    }
    @Test void compareWithExhaustiveForwardReachabilityOnRandomFiniteNets() {
        Random random=new Random(8473021);
        for(int trial=0;trial<250;trial++) {
            var recipes=new ArrayList<Recipe<String,String>>();
            for(int p=0;p<5;p++) {
                var in=new HashMap<String,Long>(); var out=new HashMap<String,Long>();
                int tokens=1+random.nextInt(3);
                for(int i=0;i<tokens;i++) { in.merge("k"+random.nextInt(3),1L,Long::sum); out.merge("k"+random.nextInt(3),1L,Long::sum); }
                recipes.add(r("p"+p,in,out));
            }
            Map<String,Long> stock=Map.of("k0",(long)random.nextInt(4),"k1",(long)random.nextInt(4),"k2",(long)random.nextInt(4));
            boolean expected=reachable(recipes,stock,stock.get("k0")+1);
            var result=solve(recipes,stock,"k0",1);
            assertEquals(expected?Status.FEASIBLE:Status.UNREACHABLE,result.status(),"trial "+trial);
            if(expected) replay(recipes,result,stock,"k0",1);
        }
    }
    private boolean reachable(List<Recipe<String,String>> recipes,Map<String,Long> stock,long target) {
        var queue=new ArrayDeque<Map<String,Long>>(); var seen=new HashSet<Map<String,Long>>(); queue.add(stock); seen.add(stock);
        while(!queue.isEmpty()) {
            var state=queue.remove(); if(state.getOrDefault("k0",0L)>=target) return true;
            for(var r:recipes) if(r.inputs().entrySet().stream().allMatch(e->state.getOrDefault(e.getKey(),0L)>=e.getValue())) {
                var next=new HashMap<>(state); r.inputs().forEach((k,v)->next.merge(k,-v,Long::sum)); r.outputs().forEach((k,v)->next.merge(k,v,Long::sum));
                if(seen.add(next)) queue.add(next);
            }
        }
        return false;
    }
    private void replay(List<Recipe<String,String>> recipes,Result<String,String> result,Map<String,Long> initial,String target,long increment) {
        var inventory=new HashMap<>(result.used()); long targetSeed=inventory.getOrDefault(target,0L);
        result.used().forEach((k,v)->assertTrue(v<=initial.getOrDefault(k,0L)));
        var byId=new HashMap<String,Recipe<String,String>>(); recipes.forEach(r->byId.put(r.id(),r));
        var counts=new HashMap<String,Long>();
        for(var block:result.program()) for(long round=0;round<block.repetitions();round++) for(var step:block.word())
            for(long n=0;n<step.count();n++) {
                var r=byId.get(step.recipe());
                r.inputs().forEach((k,v)->assertTrue(inventory.getOrDefault(k,0L)>=v,"prefix deficit "+k));
                r.inputs().forEach((k,v)->inventory.merge(k,-v,Long::sum)); r.outputs().forEach((k,v)->inventory.merge(k,v,Long::sum));
                counts.merge(r.id(),1L,Long::sum);
            }
        assertEquals(result.counts(),counts); assertTrue(inventory.getOrDefault(target,0L)-targetSeed>=increment);
    }
}
