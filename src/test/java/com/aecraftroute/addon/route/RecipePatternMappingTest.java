package com.aecraftroute.addon.route;

import appeng.api.stacks.AEKey;
import com.aecraftroute.addon.bench.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.aecraftroute.addon.bench.BenchPatternDetails.InputSpec.*;
import static org.junit.jupiter.api.Assertions.*;

class RecipePatternMappingTest {
    final BenchAEKey ore=BenchAEKey.of("map_ore"),alt=BenchAEKey.of("map_alt"),out=BenchAEKey.of("map_out");
    @Test void traversesPathsAndRetainsEveryCandidateWithoutChoosingForTheTree() {
        var one=new BenchPatternDetails(out,1,List.of(of(ore,1)));
        var fuzzy=new BenchPatternDetails(out,1,List.of(fuzzy(ore,1,alt)));
        var wrong=new BenchPatternDetails(out,2,List.of(of(ore,1)));
        var snapshot=new RouteSnapshot.Result(Map.of(out,List.of(one,fuzzy,wrong)),Map.of());
        var recipes=List.of(new RecipePatternMapping.Recipe("root",out,Map.of(ore,1L),Map.of(out,1L)),
                new RecipePatternMapping.Recipe("root/0",out,Map.of(alt,1L),Map.of(out,1L)));
        var mapped=RecipePatternMapping.discover(recipes,snapshot,k->0L,System.nanoTime()+1_000_000_000L);
        assertEquals(2,mapped.get("root").variants().size());
        assertEquals(1,mapped.get("root/0").variants().size());
        assertTrue(mapped.get("root").complete());
    }
    @Test void returnedCatalystDoesNotMatchARecipeThatOmitsTheReturn() {
        var pattern=new BenchPatternDetails(out,1,List.of(returned(ore,1)));
        var snapshot=new RouteSnapshot.Result(Map.of(out,List.of(pattern)),Map.of());
        var recipe=new RecipePatternMapping.Recipe("root",out,Map.of(ore,1L),Map.of(out,1L));
        assertTrue(RecipePatternMapping.discover(List.of(recipe),snapshot,k->0L,System.nanoTime()+1_000_000_000L)
                .get("root").variants().isEmpty());
    }
    @Test void duplicatePathsAreNotSilentlyOverwritten() {
        var recipe=new RecipePatternMapping.Recipe("root",out,Map.of(ore,1L),Map.of(out,1L));
        var snapshot=new RouteSnapshot.Result(Map.of(),Map.of());
        assertThrows(IllegalArgumentException.class,()->RecipePatternMapping.discover(List.of(recipe,recipe),snapshot,k->0L,System.nanoTime()+1_000_000_000L));
    }
    @Test void omittedOrUnsupportedVariantsAreNotReportedAsExhaustive() {
        var pattern=new BenchPatternDetails(out,1,List.of(finiteUse(ore,1,2)));
        var snapshot=new RouteSnapshot.Result(Map.of(out,List.of(pattern)),Map.of());
        var recipe=new RecipePatternMapping.Recipe("root",out,Map.of(ore,1L),Map.of(out,1L));
        var found=RecipePatternMapping.discover(List.of(recipe),snapshot,k->0L,System.nanoTime()+1_000_000_000L).get("root");
        assertFalse(found.complete());assertTrue(found.variants().isEmpty());
    }
    @Test void mappedCandidatesBecomeARealCertifiedPhysicalWitness() {
        var pattern=new BenchPatternDetails(out,1,List.of(of(ore,1)));
        var snapshot=new RouteSnapshot.Result(Map.of(out,List.of(pattern)),Map.of(ore,5L));
        var recipe=new RecipePatternMapping.Recipe("root",out,Map.of(ore,1L),Map.of(out,1L));
        var proof=RecipePatternMapping.prove(out,5,List.of(recipe),Map.of("root",5L),snapshot,
                new com.aecraftroute.addon.planner.ProductionPlanner.Limits(30_000,3_000_000_000L));
        assertEquals(5L,proof.used().get(ore));
        assertEquals(5L,proof.branchCounts().get("root"));
        assertEquals(5L,proof.counts().values().stream().mapToLong(Long::longValue).sum());
        assertSame(pattern,proof.program().getFirst().word().getFirst().recipe().pattern());
    }
    @Test void unknownOrMissingCandidatesCannotBecomeAnExecutableProof() {
        var pattern=new BenchPatternDetails(out,1,List.of(finiteUse(ore,1,2)));
        var snapshot=new RouteSnapshot.Result(Map.of(out,List.of(pattern)),Map.of(ore,5L));
        var recipe=new RecipePatternMapping.Recipe("root",out,Map.of(ore,1L),Map.of(out,1L));
        var error=assertThrows(com.aecraftroute.addon.planner.ProductionPlanningException.class,()->
                RecipePatternMapping.prove(out,1,List.of(recipe),Map.of("root",1L),snapshot,
                        new com.aecraftroute.addon.planner.ProductionPlanner.Limits(1000,1_000_000_000L)));
        assertEquals(com.aecraftroute.addon.planner.ProductionPlanningException.Reason.UNSUPPORTED,error.reason());
    }
    @Test void mappedRecipeProducesTheCertifiedPlanUsedByCpuSubmission() {
        var pattern=new BenchPatternDetails(out,1,List.of(of(ore,1)));
        var snapshot=new RouteSnapshot.Result(Map.of(out,List.of(pattern)),Map.of(ore,5L));
        var recipe=new RecipePatternMapping.Recipe("root",out,Map.of(ore,1L),Map.of(out,1L));
        var plan=RouteCanvasPlanner.planMapped(snapshot,out,5,List.of(recipe),Map.of("root",5L),
                new com.aecraftroute.addon.planner.ProductionPlanner.Limits(30_000,3_000_000_000L));
        assertEquals(5,plan.usedItems().get(ore));assertEquals(5,plan.patternTimes().get(pattern));
        var job=new com.aecraftroute.addon.planner.ProductionJob(plan);
        for(int i=0;i<5;i++){assertTrue(job.allows(pattern));job.accepted(pattern);}
        assertTrue(job.done());
    }
    @Test void mappedStraightLinePlanRemainsCompactAtTenQuadrillion() {
        long amount=10_000_000_000_000_000L;
        var pattern=new BenchPatternDetails(out,1,List.of(of(ore,1)));
        var snapshot=new RouteSnapshot.Result(Map.of(out,List.of(pattern)),Map.of(ore,amount));
        var recipe=new RecipePatternMapping.Recipe("root",out,Map.of(ore,1L),Map.of(out,1L));
        var plan=RouteCanvasPlanner.planMapped(snapshot,out,amount,List.of(recipe),Map.of("root",amount),
                new com.aecraftroute.addon.planner.ProductionPlanner.Limits(150_000,30_000_000_000L));
        assertEquals(amount,plan.usedItems().get(ore));assertEquals(amount,plan.patternTimes().get(pattern));
        assertTrue(plan.program().size()<10);
    }
}
