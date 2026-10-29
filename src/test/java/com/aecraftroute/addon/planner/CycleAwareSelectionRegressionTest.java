package com.aecraftroute.addon.planner;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import static com.aecraftroute.addon.planner.ProductionPlanner.*;
import static org.junit.jupiter.api.Assertions.*;

class CycleAwareSelectionRegressionTest {
    @Test void unseededDeadAlternativeDoesNotConsumeGrowthFuelInConstructiveProbe() {
        var recipes = new ArrayList<Recipe<String,String>>();
        recipes.add(new Recipe<>("grow", Map.of("seed",1L,"fuel",1L), Map.of("s0",2L)));
        for (int i=0;i<140;i++) recipes.add(new Recipe<>("r"+i,Map.of("s"+i,1L),
                Map.of(i==139?"seed":"s"+(i+1),1L)));
        recipes.add(new Recipe<>("dead-alternative",Map.of("x",1L,"dead",1L),Map.of("seed",1L)));
        recipes.add(new Recipe<>("dead-a",Map.of("dead",1L),Map.of("dead2",1L)));
        recipes.add(new Recipe<>("dead-b",Map.of("dead2",1L),Map.of("dead",1L)));
        recipes.add(new Recipe<>("wasted-fuel",Map.of("fuel",1L),Map.of("x",1L)));
        var stock=Map.of("seed",1L,"fuel",1_000_000L);
        var result=new ProductionPlanner<>(recipes,stock,Limits.defaults()).solve("seed",1_000_000L);
        assertEquals(Status.FEASIBLE,result.status(),"explored="+result.explored());
        assertTrue(result.explored()<=142,"dead branch forced fallback search");
        assertFalse(result.counts().containsKey("wasted-fuel"));
        DagBatchPlannerTest.verify(recipes,stock,result,"seed",1_000_000L);
    }

    private CompiledProductionGraph graph() {
        // 0: seed; 1: other ring member; 2: ore; 3: fuel; 4: output.
        return CompiledProductionGraph.compile(5,
                new int[][]{{0},{1},{2},{0,3}},new long[][]{{1},{1},{1},{1,1}},
                new int[][]{{1},{0},{0},{0,4}},new long[][]{{1},{1},{1},{1,1}},()->false);
    }
    private BigInteger[] stock(long seed,long ore,long fuel) {
        return new BigInteger[]{BigInteger.valueOf(seed),BigInteger.ZERO,BigInteger.valueOf(ore),BigInteger.valueOf(fuel),BigInteger.ZERO};
    }
    @Test void deadRingIsExcludedButSeededAndExternallySuppliedRingsRemain() {
        var g=graph();
        assertArrayEquals(new boolean[]{false,false,false,false},SeedReachability.reachableRecipes(g,stock(0,0,100),()->false));
        assertArrayEquals(new boolean[]{true,true,false,true},SeedReachability.reachableRecipes(g,stock(1,0,100),()->false));
        assertArrayEquals(new boolean[]{true,true,true,true},SeedReachability.reachableRecipes(g,stock(0,1,100),()->false));
        assertArrayEquals(new boolean[]{true,true,true,false},SeedReachability.reachableRecipes(g,stock(0,1,0),()->false));
    }
    @Test void outputOnlyAndMultiInputRulesUsePresenceNotNetOrQuantities() {
        var g=CompiledProductionGraph.compile(3,new int[][]{{},{0,1},{2}},new long[][]{{},{100,1},{1}},
                new int[][]{{0},{2},{2}},new long[][]{{1},{1},{1}},()->false);
        assertArrayEquals(new boolean[]{true,true,true},SeedReachability.reachableRecipes(g,
                new BigInteger[]{BigInteger.ZERO,BigInteger.ONE,BigInteger.ZERO},()->false));
        assertArrayEquals(new boolean[]{true,false,false},SeedReachability.reachableRecipes(g,
                new BigInteger[]{BigInteger.ZERO,BigInteger.ZERO,BigInteger.ZERO},()->false));
    }
    @Test void deadlineNeverReturnsPartialMaskAndCancellationPropagates() {
        var g=graph();
        assertNull(SeedReachability.reachableRecipes(g,stock(1,0,100),()->true));
        var calls=new AtomicInteger();
        assertNull(SeedReachability.reachableRecipes(g,stock(1,0,100),()->calls.incrementAndGet()>12));
        assertThrows(CancellationException.class,()->SeedReachability.reachableRecipes(g,stock(1,0,100),()->{throw new CancellationException();}));
    }
}
