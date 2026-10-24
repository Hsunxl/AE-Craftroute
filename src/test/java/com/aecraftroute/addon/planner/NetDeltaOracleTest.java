package com.aecraftroute.addon.planner;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.aecraftroute.addon.planner.ProductionPlanner.*;

/**
 * Cross-checks {@link NetDeltaOracle} against the planner. The oracle is a
 * stoichiometric net-gain LOWER-BOUND check only: it ignores stock, prefix
 * capital and execution order. A positive net does not prove executability, and
 * a non-sufficient net does not prove infeasibility. Final feasibility is decided
 * by ProductionPlanner.certify(); the oracle never replaces it.
 */
class NetDeltaOracleTest {
    private Recipe<String,String> r(String id, Map<String,Long> in, Map<String,Long> out) { return new Recipe<>(id,in,out); }
    private Result<String,String> solve(List<Recipe<String,String>> recipes, Map<String,Long> stock, String target, long n) {
        return new ProductionPlanner<>(recipes,stock,new Limits(100_000,10_000_000_000L)).solve(target,n);
    }
    @Test void netGainSingleOutputAndAbsentKeys() {
        var make=r("make",Map.of("x",2L),Map.of("t",5L));
        assertEquals(5L,NetDeltaOracle.netGain(make,"t"));
        assertEquals(-2L,NetDeltaOracle.netGain(make,"x"));
        assertEquals(0L,NetDeltaOracle.netGain(make,"y"));
    }
    @Test void netGainTargetOnBothSidesIsSignedDifference() {
        var grow=r("grow",Map.of("t",1L,"f",1L),Map.of("t",3L,"u",1L));
        assertEquals(2L,NetDeltaOracle.netGain(grow,"t"));
    }
    @Test void netGainTargetOnlyAsInput() {
        var consume=r("consume",Map.of("t",4L),Map.of("u",1L));
        assertEquals(-4L,NetDeltaOracle.netGain(consume,"t"));
    }
    @Test void netSumsRunCountsAndOmittedRecipesContributeZero() {
        var recipes=List.of(r("grow",Map.of("a",1L,"fuel",1L),Map.of("b",2L)),r("return",Map.of("b",1L),Map.of("a",1L)));
        // a: grow -1 per run, return +1 per run.  3*(-1) + 5*(+1) + 10 = 12
        assertEquals(java.math.BigInteger.valueOf(12),NetDeltaOracle.net(recipes,Map.of("grow",3L,"return",5L),"a",10));
        // b: grow +2 per run, return -1 per run.  2*3 - 5 = 1
        assertEquals(java.math.BigInteger.valueOf(1),NetDeltaOracle.net(recipes,Map.of("grow",3L,"return",5L),"b",0));
        // no times: only emitted counts
        assertEquals(java.math.BigInteger.valueOf(7),NetDeltaOracle.net(recipes,Map.of(),"a",7));
    }
    @Test void rejectsInvalidArguments() {
        var recipes=List.of(r("grow",Map.of("a",1L),Map.of("b",1L)));
        assertThrows(IllegalArgumentException.class,()->NetDeltaOracle.net(recipes,Map.of(),"a",-1));
        assertThrows(IllegalArgumentException.class,()->NetDeltaOracle.net(recipes,Map.of("nope",1L),"a",0));
        assertThrows(IllegalArgumentException.class,()->NetDeltaOracle.net(recipes,Map.of("grow",-1L),"a",0));
        assertThrows(IllegalArgumentException.class,()->NetDeltaOracle.net(List.of(recipes.get(0),recipes.get(0)),Map.of(),"a",0));
        assertThrows(IllegalArgumentException.class,()->NetDeltaOracle.sufficient(recipes,Map.of(),"a",0,-1));
    }
    @Test void bulkNegativeControlZeroNetIsNotSufficient() {
        // grow(a+fuel->2b), return(b->a). Equal run counts 1e9 each give net a = 0.
        // The oracle is only a lower-bound net check, so it must not claim sufficiency here.
        var recipes=List.of(r("grow",Map.of("a",1L,"fuel",1L),Map.of("b",2L)),r("return",Map.of("b",1L),Map.of("a",1L)));
        var counts=Map.of("grow",1_000_000_000L,"return",1_000_000_000L);
        assertEquals(java.math.BigInteger.ZERO,NetDeltaOracle.net(recipes,counts,"a",0));
        assertFalse(NetDeltaOracle.sufficient(recipes,counts,"a",0,1_000_000_000L));
    }
    @Test void bulkWitnessFromPlannerIsSufficientByOracle() {
        var recipes=List.of(r("grow",Map.of("a",1L,"fuel",1L),Map.of("b",2L)),r("return",Map.of("b",1L),Map.of("a",1L)));
        var stock=Map.of("a",1L,"fuel",1_000_000_000L);
        var result=solve(recipes,stock,"a",1_000_000_000L);
        assertEquals(Status.FEASIBLE,result.status());
        assertTrue(NetDeltaOracle.sufficient(recipes,result.counts(),"a",0,1_000_000_000L));
    }
    @Test void ringWitnessesAreSufficientByOracle() {
        for(int length=1;length<=9;length++) {
            var recipes=new ArrayList<Recipe<String,String>>();
            for(int i=0;i<length;i++) recipes.add(r("p"+i,Map.of("k"+i,1L,"fuel",1L),Map.of("k"+((i+1)%length),i==length-1?3L:1L)));
            var result=solve(recipes,Map.of("k0",1L,"fuel",100_000L),"k0",1000);
            assertEquals(Status.FEASIBLE,result.status(),"ring length "+length);
            assertTrue(NetDeltaOracle.sufficient(recipes,result.counts(),"k0",0,1000),"ring length "+length);
        }
    }
    @Test void randomFiniteNetsFeasibleImpliesOracleSufficient() {
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
            var result=solve(recipes,stock,"k0",1);
            // Must never throw for any status; only FEASIBLE is checked for sufficiency.
            assertDoesNotThrow(()->NetDeltaOracle.net(recipes,result.counts(),"k0",0),"trial "+trial);
            if(result.status()==Status.FEASIBLE) assertTrue(NetDeltaOracle.sufficient(recipes,result.counts(),"k0",0,1),"trial "+trial);
        }
    }
}
