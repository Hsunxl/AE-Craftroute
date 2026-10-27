package com.aecraftroute.addon.planner;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static com.aecraftroute.addon.planner.ProductionPlanner.*;
import static org.junit.jupiter.api.Assertions.*;

/** Regression for growth cycles whose consumable side inputs have producers. */
class ProductionPlannerUpstreamTest {
    @Test void upstreamSuppliedGrowthAtBothObservedCapitalSizes() {
        var recipes=ProductionPlannerUpstreamFixture.recipes();
        for(long capital:new long[]{40,84}) for(long amount:new long[]{100,1000,1360,2000}) {
            var stock=ProductionPlannerUpstreamFixture.stock(capital);
            var result=new ProductionPlanner<>(recipes,stock,Limits.defaults()).solve("dust",amount);
            assertEquals(Status.FEASIBLE,result.status(),"capital="+capital+", increment="+amount+", explored="+result.explored());
            replay(recipes,stock,result,"dust",amount);
        }
    }

    @Test void veryLargeQuantitiesStayCompactAndFeasible() {
        var recipes=ProductionPlannerUpstreamFixture.recipes();
        for(long amount:new long[]{47256L,1_000_000L}) {
            var stock=ProductionPlannerUpstreamFixture.stock(84);
            var result=new ProductionPlanner<>(recipes,stock,Limits.defaults()).solve("dust",amount);
            assertEquals(Status.FEASIBLE,result.status(),"amount="+amount+", explored="+result.explored());
            assertTrue(result.program().size()<100,"program bloated: "+result.program().size());
            replay(recipes,stock,result,"dust",amount);
        }
    }
    /** Synthetic tiered essence-like graph, not an export of the user's network.
     * This is deliberately distinct from the dust/seed growth regression. */
    @Test void largeCatalystTierChainStaysWithinDefaultBudget() {
        var recipes=new ArrayList<Recipe<String,String>>();
        for(int tier=1;tier<=4;tier++)recipes.add(new Recipe<>("tier-"+tier,
                Map.of("tier-"+(tier-1),4L,"catalyst",1L),
                Map.of("tier-"+tier,1L,"catalyst",1L)));
        long amount=47256L;
        var result=new ProductionPlanner<>(recipes,Map.of("tier-0",amount*256,"catalyst",1L),Limits.defaults()).solve("tier-4",amount);
        assertEquals(Status.FEASIBLE,result.status(),"explored="+result.explored());
        assertEquals(Map.of("tier-0",amount*256,"catalyst",1L),result.used());
        assertEquals(Map.of("tier-1",amount*64,"tier-2",amount*16,"tier-3",amount*4,"tier-4",amount),result.counts());
        assertTrue(result.program().size()<20,"tier graph expanded per item");
    }
    @Test void producingSideInputsDoesNotInventGrowthCapital() {
        var result=new ProductionPlanner<>(ProductionPlannerUpstreamFixture.recipes(),
                ProductionPlannerUpstreamFixture.stock(7),new Limits(10_000,250_000_000L)).solve("dust",100);
        assertNotEquals(Status.FEASIBLE,result.status());
        assertTrue(result.program().isEmpty());
        assertTrue(result.used().isEmpty());
    }

    @Test void zeroAndNegativeGainCannotBeAcceleratedIntoProduction() {
        for(long seedOutput:new long[]{7,8}) {
            var recipes=List.of(
                    new Recipe<>("convert",Map.of("capital",8L,"feed",1L),Map.of("intermediate",seedOutput)),
                    new Recipe<>("return",Map.of("intermediate",1L),Map.of("capital",1L)));
            var result=new ProductionPlanner<>(recipes,Map.of("capital",84L,"feed",1_000_000L),
                    new Limits(10_000,250_000_000L)).solve("capital",1000);
            assertNotEquals(Status.FEASIBLE,result.status());
        }
    }
    @Test void repeatedCycleMustRetainLastRoundsWorkingCapital() {
        var recipes=List.of(
                new Recipe<>("spend",Map.of("capital",2L,"feed",1L),Map.of("intermediate",1L)),
                new Recipe<>("return",Map.of("intermediate",1L),Map.of("capital",1L,"target",1L)));
        var stock=Map.of("capital",5L,"feed",10L);
        var feasible=new ProductionPlanner<>(recipes,stock,Limits.defaults()).solve("target",4);
        assertEquals(Status.FEASIBLE,feasible.status());replay(recipes,stock,feasible,"target",4);
        var impossible=assertDoesNotThrow(()->new ProductionPlanner<>(recipes,stock,new Limits(1000,100_000_000L)).solve("target",5));
        assertNotEquals(Status.FEASIBLE,impossible.status());assertTrue(impossible.program().isEmpty());
    }

    @Test void overflowCannotBecomeAnExecutableCertificate() {
        var recipes=List.of(new Recipe<>("double",Map.of("token",1L),Map.of("token",2L)));
        var result=new ProductionPlanner<>(recipes,Map.of("token",1L),Limits.defaults()).solve("token",Long.MAX_VALUE);
        assertNotEquals(Status.FEASIBLE,result.status());
        assertTrue(result.program().isEmpty());
    }

    @Test void failedPreferredBranchMustNotConsumeFallbackInventory() {
        var recipes=List.of(
                new Recipe<>("assemble",Map.of("a",1L,"b",1L),Map.of("out",1L)),
                new Recipe<>("bad-first",Map.of("x",2L),Map.of("a",1L)),
                new Recipe<>("good-second",Map.of("y",1L),Map.of("a",1L)),
                new Recipe<>("make-b",Map.of("x",2L),Map.of("b",1L)));
        var stock=Map.of("x",2L,"y",1L);
        var result=new ProductionPlanner<>(recipes,stock,Limits.defaults()).solve("out",1);
        assertEquals(Status.FEASIBLE,result.status());
        replay(recipes,stock,result,"out",1);
    }

    @Test void catalystCapitalIsRequiredOnceNotOncePerBatch() {
        var recipes=List.of(new Recipe<>("catalyzed",Map.of("tool",1L,"ore",1L),Map.of("tool",1L,"out",1L)));
        var stock=Map.of("tool",1L,"ore",1000L);
        var result=new ProductionPlanner<>(recipes,stock,Limits.defaults()).solve("out",1000);
        assertEquals(Status.FEASIBLE,result.status());
        assertEquals(1L,result.used().getOrDefault("tool",0L));
        assertEquals(1000L,result.used().getOrDefault("ore",0L));
        replay(recipes,stock,result,"out",1000);
    }

    @Test void existingTargetStockDoesNotInflateUpstreamNetProduction() {
        var recipes=List.of(
                new Recipe<>("refine",Map.of("ore",1L),Map.of("intermediate",1L)),
                new Recipe<>("finish",Map.of("intermediate",1L),Map.of("target",1L)));
        var stock=Map.of("target",1_000_000L,"ore",1_000_001L);
        var result=new ProductionPlanner<>(recipes,stock,Limits.defaults()).solve("target",1);
        assertEquals(Status.FEASIBLE,result.status());
        assertEquals(Map.of("refine",1L,"finish",1L),result.counts());
        assertEquals(Map.of("ore",1L),result.used());
        replay(recipes,stock,result,"target",1);
    }

    /** Deliberately independent scalar firing replay, not planner prefix-summary code. */
    private void replay(List<Recipe<String,String>> recipes,Map<String,Long> available,
                        Result<String,String> result,String target,long increment) {
        var inventory=new HashMap<String,BigInteger>();
        result.used().forEach((key,value)->{
            assertTrue(value>=0 && value<=available.getOrDefault(key,0L),"invented capital: "+key);
            inventory.put(key,BigInteger.valueOf(value));
        });
        BigInteger targetSeed=inventory.getOrDefault(target,BigInteger.ZERO);
        var byId=new HashMap<String,Recipe<String,String>>();
        recipes.forEach(recipe->byId.put(recipe.id(),recipe));
        var counts=new HashMap<String,Long>();
        for(var block:result.program()) for(long repetition=0;repetition<block.repetitions();repetition++)
            for(var step:block.word()) for(long firing=0;firing<step.count();firing++) {
                var recipe=byId.get(step.recipe());
                assertNotNull(recipe,"unknown recipe");
                recipe.inputs().forEach((key,value)->assertTrue(
                        inventory.getOrDefault(key,BigInteger.ZERO).compareTo(BigInteger.valueOf(value))>=0,
                        "prefix deficit in "+recipe.id()+": "+key));
                recipe.inputs().forEach((key,value)->inventory.merge(key,BigInteger.valueOf(value).negate(),BigInteger::add));
                recipe.outputs().forEach((key,value)->inventory.merge(key,BigInteger.valueOf(value),BigInteger::add));
                inventory.values().forEach(value->assertTrue(value.signum()>=0 && value.compareTo(BigInteger.valueOf(Long.MAX_VALUE))<=0));
                counts.merge(recipe.id(),1L,Math::addExact);
            }
        assertEquals(result.counts(),counts);
        assertTrue(inventory.getOrDefault(target,BigInteger.ZERO).subtract(targetSeed).compareTo(BigInteger.valueOf(increment))>=0,
                "stock delivery must not be counted as new production");
    }
}
