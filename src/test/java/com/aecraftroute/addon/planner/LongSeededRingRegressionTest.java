package com.aecraftroute.addon.planner;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.aecraftroute.addon.planner.ProductionPlanner.*;
import static org.junit.jupiter.api.Assertions.*;

class LongSeededRingRegressionTest {
    private List<Recipe<String,String>> ring(long fuelIn,long fuelReturn,boolean catalyst) {
        var recipes=new ArrayList<Recipe<String,String>>();
        var inputs=new LinkedHashMap<String,Long>();inputs.put("seed",1L);inputs.put("fuel",fuelIn);
        if(catalyst)inputs.put("tool",1L);
        recipes.add(new Recipe<>("grow",inputs,Map.of("s0",2L)));
        for(int i=0;i<140;i++) {
            var out=new LinkedHashMap<String,Long>();out.put(i==139?"seed":"s"+(i+1),1L);
            if(i==139) {
                if(fuelReturn>0)out.put("fuel",fuelReturn);
                if(catalyst)out.put("tool",1L);
            }
            recipes.add(new Recipe<>("r"+i,Map.of("s"+i,1L),out));
        }
        return recipes;
    }
    @Test void seededRingLongerThan128StepsProducesCompactCertifiedLargeOrders() {
        var recipes=ring(1,0,false);
        for(long amount:new long[]{1_000_000L,1_000_000_000L,10_000_000_000_000_000L}) {
            var stock=Map.of("seed",1L,"fuel",amount);
            var result=new ProductionPlanner<>(recipes,stock,Limits.defaults()).solve("seed",amount);
            assertEquals(Status.FEASIBLE,result.status(),"amount="+amount+", explored="+result.explored());
            DagBatchPlannerTest.verify(recipes,stock,result,"seed",amount);
            assertTrue(result.program().size()<=142,"blocks="+result.program().size());
            assertTrue(result.explored()<=142,"fell back to state search: "+result.explored());
            assertEquals(Map.of("seed",1L,"fuel",amount),result.used());
            assertEquals(amount,result.counts().get("grow"));
            assertEquals(2*amount,result.counts().get("r139"));
            System.out.println("[long-ring] amount="+amount+" blocks="+result.program().size()+" explored="+result.explored());
        }
    }
    @Test void missingSeedOrReturnedCatalystCannotBeBorrowedFromFuture() {
        var recipes=ring(1,0,true);
        for(var stock:List.of(Map.of("fuel",100L,"tool",1L),Map.of("fuel",100L,"seed",1L))) {
            var result=new ProductionPlanner<>(recipes,stock,new Limits(1000,500_000_000L)).solve("seed",100);
            assertNotEquals(Status.FEASIBLE,result.status());assertTrue(result.program().isEmpty());
        }
    }
    @Test void returnedSideInputStillNeedsPrefixWorkingCapital() {
        // Each lap runs its final step twice and returns two fuel: net cost is one,
        // but three fuel must exist BEFORE grow. Net-only accounting is unsound.
        var recipes=ring(3,1,true);long amount=1_000_000L;
        var stock=Map.of("seed",1L,"fuel",amount+2,"tool",1L);
        var result=new ProductionPlanner<>(recipes,stock,Limits.defaults()).solve("seed",amount);
        assertEquals(Status.FEASIBLE,result.status());
        DagBatchPlannerTest.verify(recipes,stock,result,"seed",amount);
        assertEquals(amount+2,result.used().get("fuel"));assertEquals(1L,result.used().get("tool"));
        var shortResult=new ProductionPlanner<>(recipes,Map.of("seed",1L,"fuel",amount+1,"tool",1L),
                new Limits(1000,500_000_000L)).solve("seed",amount);
        assertNotEquals(Status.FEASIBLE,shortResult.status());
    }
    @Test void zeroNetCatalystRemainsRealStartupCapital() {
        var recipes=List.of(new Recipe<>("grow",Map.of("seed",1L,"fuel",1L,"tool",1L),Map.of("mid",2L,"tool",1L)),
                new Recipe<>("back",Map.of("mid",1L),Map.of("seed",1L)));
        var stock=Map.of("seed",1L,"fuel",1_000_000L,"tool",1L);
        var result=new ProductionPlanner<>(recipes,stock,Limits.defaults()).solve("seed",1_000_000L);
        assertEquals(Status.FEASIBLE,result.status());assertEquals(1L,result.used().get("tool"));
        DagBatchPlannerTest.verify(recipes,stock,result,"seed",1_000_000L);
    }
    @Test void accumulatedRecipeCountCannotOverflowSignedLong() {
        var recipes=ring(1,0,false);long amount=Long.MAX_VALUE/2+1;
        var result=new ProductionPlanner<>(recipes,Map.of("seed",1L,"fuel",amount),
                new Limits(1000,500_000_000L)).solve("seed",amount);
        assertNotEquals(Status.FEASIBLE,result.status());assertTrue(result.program().isEmpty());
    }
    @Test void cancellationAndTinyDeadlineDoNotBecomeFeasible() {
        var recipes=ring(1,0,false);var stock=Map.of("seed",1L,"fuel",1_000_000L);
        assertEquals(Status.LIMIT,new ProductionPlanner<>(recipes,stock,new Limits(1000,1)).solve("seed",1_000_000L).status());
        Thread.currentThread().interrupt();
        try {assertThrows(java.util.concurrent.CancellationException.class,()->
                new ProductionPlanner<>(recipes,stock,Limits.defaults()).solve("seed",1_000_000L));}
        finally {Thread.interrupted();}
    }
}
