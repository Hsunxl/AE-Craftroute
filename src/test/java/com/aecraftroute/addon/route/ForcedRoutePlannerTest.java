package com.aecraftroute.addon.route;

import com.aecraftroute.addon.planner.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ForcedRoutePlannerTest {
    private static final ProductionPlanner.Limits LIMITS=new ProductionPlanner.Limits(12000,1_000_000_000L);
    private ProductionPlanner.Recipe<String,String> r(String id,Map<String,Long> in,Map<String,Long> out) {return new ProductionPlanner.Recipe<>(id,in,out);}
    private ForcedRoutePlanner.Branch<String> b(String path,String recipe,long minimum) {return new ForcedRoutePlanner.Branch<>(path,Set.of(recipe),minimum);}
    private ForcedRoutePlanner.Proof<String,String> solve(String target,long n,List<ProductionPlanner.Recipe<String,String>> recipes,Map<String,Long> stock,List<ForcedRoutePlanner.Branch<String>> branches) {
        return new ForcedRoutePlanner<String,String>().solve(target,n,recipes,stock,branches,LIMITS);
    }
    @Test void sameMaterialTwoPathsActuallyExecuteBothRecipes() {
        var recipes=List.of(r("red",Map.of("redOre",1L),Map.of("metal",1L)),r("blue",Map.of("blueOre",1L),Map.of("metal",1L)),r("goal",Map.of("metal",2L),Map.of("goal",1L)));
        var proof=solve("goal",1,recipes,Map.of("redOre",1L,"blueOre",1L),List.of(b("root","goal",1),b("root/0/0","red",1),b("root/1/0","blue",1)));
        assertEquals(1L,proof.counts().get("red"));assertEquals(1L,proof.counts().get("blue"));
        assertEquals(Map.of("redOre",1L,"blueOre",1L),proof.used());
        assertEquals(Set.of("root","root/0/0","root/1/0"),proof.branchCounts().keySet());
    }
    @Test void initialSharedStockCannotBeSpentTwice() {
        var recipes=List.of(r("a",Map.of("ore",1L),Map.of("x",1L)),r("b",Map.of("ore",1L),Map.of("x",1L)));
        var e=assertThrows(ProductionPlanningException.class,()->solve("x",2,recipes,Map.of("ore",1L),List.of(b("left","a",1),b("right","b",1))));
        assertEquals(ProductionPlanningException.Reason.UNREACHABLE,e.reason());
    }
    @Test void samePatternInTwoOccurrencesRequiresTwoPhysicalExecutions() {
        var proof=solve("x",1,List.of(r("a",Map.of("ore",1L),Map.of("x",1L))),Map.of("ore",2L),List.of(b("left","a",1),b("right","a",1)));
        assertEquals(2L,proof.counts().get("a"));assertEquals(2L,proof.used().get("ore"));
    }
    @Test void branchMinimumDoesNotCapGrowingCycles() {
        var proof=solve("dust",1000,List.of(r("aggregate",Map.of("dust",8L),Map.of("seed",1L)),r("grow",Map.of("seed",1L),Map.of("dust",12L))),
                Map.of("dust",8L),List.of(b("root","grow",84),b("root/0","aggregate",84)));
        assertEquals(Map.of("dust",8L),proof.used());
        assertTrue(proof.counts().get("grow")>=250);assertTrue(proof.counts().get("aggregate")>=250);
        assertTrue(proof.program().size()<100,"loops must remain compressed");
    }
    @Test void ExistingTargetStockDoesNotReplaceRequestedNetIncrement() {
        var proof=solve("x",3,List.of(r("a",Map.of("ore",1L),Map.of("x",1L))),Map.of("x",50L,"ore",3L),List.of(b("root","a",1)));
        assertEquals(3L,proof.counts().get("a"));assertFalse(proof.used().containsKey("x"));
    }
    @Test void byproductAndReturnedContainerAreSharedAcrossBranches() {
        var recipes=List.of(r("a",Map.of("bucket",1L,"ore",1L),Map.of("bucket",1L,"x",1L,"slag",1L)),r("b",Map.of("slag",1L),Map.of("y",1L)),r("goal",Map.of("x",1L,"y",1L),Map.of("goal",1L)));
        var proof=solve("goal",1,recipes,Map.of("bucket",1L,"ore",1L),List.of(b("root","goal",1),b("left","a",1),b("right","b",1)));
        assertEquals(Map.of("bucket",1L,"ore",1L),proof.used());assertEquals(1L,proof.counts().get("b"));
    }
    @Test void removedOrBannedSelectedRecipeCannotBecomeAVirtualSuccess() {
        var e=assertThrows(ProductionPlanningException.class,()->solve("x",1,List.of(r("other",Map.of("ore",1L),Map.of("x",1L))),Map.of("ore",2L),List.of(b("root","banned",1))));
        assertEquals(ProductionPlanningException.Reason.INVALID,e.reason());
    }
    @Test void cancellationIsNotUnreachable() {
        Thread.currentThread().interrupt();
        try {assertThrows(java.util.concurrent.CancellationException.class,()->solve("x",1,List.of(r("a",Map.of("ore",1L),Map.of("x",1L))),Map.of("ore",1L),List.of(b("root","a",1))));}
        finally {Thread.interrupted();}
    }
}
