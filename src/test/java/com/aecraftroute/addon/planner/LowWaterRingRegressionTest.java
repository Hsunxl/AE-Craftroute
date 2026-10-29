package com.aecraftroute.addon.planner;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.aecraftroute.addon.planner.ProductionPlanner.*;
import static org.junit.jupiter.api.Assertions.*;

/** A bootstrap consumes target inventory before a long feedback loop restores it.
 * Capturing only a new all-time high misses the useful low-water excursion. */
class LowWaterRingRegressionTest {
    private static final long STARTUP=10_000L;
    private static final long AMOUNT=1_000_000L;

    private List<Recipe<String,String>> recipes() {
        var result=new ArrayList<Recipe<String,String>>();
        result.add(new Recipe<>("bootstrap",Map.of("target",STARTUP),Map.of("capital",1L)));
        result.add(new Recipe<>("grow",Map.of("capital",1L,"fuel",1L),Map.of("stage0",1L,"target",1L)));
        for(int i=0;i<24;i++) result.add(new Recipe<>("r"+i,Map.of("stage"+i,1L),
                Map.of(i==23?"capital":"stage"+(i+1),1L)));
        return result;
    }

    @Test void bootstrapDipDoesNotHideMillionScaleRepeatableFeedback() {
        var recipes=recipes();
        var stock=Map.of("target",STARTUP,"fuel",STARTUP+AMOUNT);
        var result=new ProductionPlanner<>(recipes,stock,Limits.defaults()).solve("target",AMOUNT);
        assertEquals(Status.FEASIBLE,result.status(),"explored="+result.explored());
        DagBatchPlannerTest.verify(recipes,stock,result,"target",AMOUNT);
        assertTrue(result.explored()<=64,"fell back to state search: "+result.explored());
        assertTrue(result.program().size()<=64,"blocks="+result.program().size());
        assertEquals(STARTUP,result.used().get("target"));
        assertEquals(STARTUP+AMOUNT,result.used().get("fuel"));
        assertEquals(STARTUP+AMOUNT,result.counts().get("grow"));
    }

    @Test void lowWaterCaptureDoesNotBorrowMissingStartupOrFuelFromFuture() {
        var recipes=recipes();
        for(var stock:List.of(Map.of("target",STARTUP-1,"fuel",STARTUP+AMOUNT),
                Map.of("target",STARTUP,"fuel",STARTUP+AMOUNT-1))) {
            var result=new ProductionPlanner<>(recipes,stock,new Limits(500,200_000_000L)).solve("target",AMOUNT);
            assertNotEquals(Status.FEASIBLE,result.status());
            assertTrue(result.program().isEmpty());
        }
    }
}
