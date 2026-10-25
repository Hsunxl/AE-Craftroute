package com.aecraftroute.addon.planner;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;
import static com.aecraftroute.addon.planner.ProductionPlanner.*;

class ConservedResourceBoundTest {
    private static List<Recipe<String,String>> tiers() {
        var rules=new ArrayList<Recipe<String,String>>();
        for(int i=1;i<=5;i++)rules.add(new Recipe<>("up"+i,Map.of("e"+(i-1),4L,"tool",1L),Map.of("e"+i,1L,"tool",1L)));
        rules.add(new Recipe<>("tool",Map.of("e4",4L,"shard",4L,"gem",1L),Map.of("tool",1L)));
        rules.add(new Recipe<>("gem",Map.of("e4",2L,"diamond",1L),Map.of("gem",1L)));
        return rules;
    }
    private static Map<String,Long> mixedStock() {
        return Map.of("e0",501410L,"e1",136039L,"e2",875L,"e3",968L,"tool",25L,
                "shard",317616L,"diamond",1000000L);
    }
    @Test void mixedStocksWithACraftableReturnedToolRejectOversizedOrdersByProofNotSearchTimeout() {
        for(long n:new long[]{16556,30000,300000,Long.MAX_VALUE}) {
            var planner=new ProductionPlanner<>(tiers(),mixedStock(),new Limits(30000,3_000_000_000L));
            var result=planner.solve("e3",n);
            assertEquals(Status.UNREACHABLE,result.status());assertTrue(planner.resourceBoundUsed());
            assertEquals(1,result.explored());assertTrue(result.program().isEmpty());
            assertEquals(BigInteger.valueOf(16555),result.maximumAdditionalTarget());
        }
    }
    @Test void sufficientOrdersStillUseCertifiedBatchPlansIncludingTheLastAvailableUnit() {
        for(long n:new long[]{10000,16555}) {
            var planner=new ProductionPlanner<>(tiers(),mixedStock(),Limits.defaults());
            var result=planner.solve("e3",n);assertEquals(Status.FEASIBLE,result.status());
            assertTrue(planner.dagFastPathUsed());assertFalse(planner.resourceBoundUsed());
            DagBatchPlannerTest.verify(tiers(),mixedStock(),result,"e3",n);
        }
    }
    private static CompiledProductionGraph graph(int keys,int[][] in,long[][] a,int[][] out,long[][] b) {
        return CompiledProductionGraph.compile(keys,in,a,out,b,()->false);
    }
    private static BigInteger[] stock(long... amounts) {
        return Arrays.stream(amounts).mapToObj(BigInteger::valueOf).toArray(BigInteger[]::new);
    }
    @Test void certificateChecksEveryAlternativeProducerRatherThanOnlyTheSelectedDag() {
        var g=graph(3,new int[][]{{0},{2}},new long[][]{{4},{1}},new int[][]{{1},{1}},new long[][]{{1},{1}});
        assertNull(ConservedResourceBound.prove(g,stock(4,0,1000000),1,30000,()->false));
        var proof=ConservedResourceBound.prove(g,stock(4,0,0),1,30000,()->false);
        assertNotNull(proof);verify(g,stock(4,0,0),1,30000,proof);
    }
    @Test void sharedInputsMultiOutputsAndRemaindersUseExactWeightedNetFlow() {
        var g=graph(4,new int[][]{{0,3},{1}},new long[][]{{4,1},{2}},new int[][]{{1,2,3},{2}},new long[][]{{1,1,1},{1}});
        var s=stock(40,0,0,1);var proof=ConservedResourceBound.prove(g,s,2,100,()->false);
        assertNotNull(proof);verify(g,s,2,100,proof);assertEquals(BigInteger.ZERO,proof.weights().get(3));
    }
    @Test void aFreeSourceOrPositiveGrowthCycleCannotBeCalledMissingMaterial() {
        var free=graph(1,new int[][]{{}},new long[][]{{}},new int[][]{{0}},new long[][]{{1}});
        assertNull(ConservedResourceBound.prove(free,stock(0),0,30000,()->false));
        var ring=graph(2,new int[][]{{0},{1}},new long[][]{{1},{1}},new int[][]{{1},{0}},new long[][]{{2},{1}});
        assertNull(ConservedResourceBound.prove(ring,stock(1,0),0,30000,()->false));
    }
    @Test void reversibleConversionsMayConserveResourcesAndCannotInventThem() {
        var g=graph(2,new int[][]{{0},{1}},new long[][]{{4},{1}},new int[][]{{1},{0}},new long[][]{{1},{4}});
        var s=stock(401,20);var proof=ConservedResourceBound.prove(g,s,1,101,()->false);
        assertNotNull(proof);verify(g,s,1,101,proof);assertEquals(BigInteger.valueOf(100),proof.maximumAdditionalTarget());
        assertNull(ConservedResourceBound.prove(g,s,1,100,()->false));
    }
    @Test void hugeCountsAndExistingTargetUseBigIntegersWithoutSaturationOrSpendingTheGoalSeed() {
        var g=graph(2,new int[][]{{0}},new long[][]{{Long.MAX_VALUE}},new int[][]{{1}},new long[][]{{1}});
        var s=stock(Long.MAX_VALUE,Long.MAX_VALUE-1);var proof=ConservedResourceBound.prove(g,s,1,2,()->false);
        assertNotNull(proof);verify(g,s,1,2,proof);assertEquals(BigInteger.ONE,proof.maximumAdditionalTarget());
    }
    @Test void timeoutIsUnknownAndCancellationPropagatesWithoutCachingAnInfeasibilityClaim() {
        var g=graph(2,new int[][]{{0}},new long[][]{{4}},new int[][]{{1}},new long[][]{{1}});
        assertNull(ConservedResourceBound.prove(g,stock(0,0),1,1,()->true));
        assertThrows(CancellationException.class,()->ConservedResourceBound.prove(g,stock(0,0),1,1,()->{throw new CancellationException();}));
        assertNotNull(ConservedResourceBound.prove(g,stock(0,0),1,1,()->false));
    }
    @Test void networkBoundaryReportsVerifiedCapacityInsteadOfBudgetExhaustion() {
        var raw=com.aecraftroute.addon.bench.BenchAEKey.of("capacity_raw");
        var target=com.aecraftroute.addon.bench.BenchAEKey.of("capacity_target");
        var pattern=new com.aecraftroute.addon.bench.BenchPatternDetails(target,1,
                List.of(com.aecraftroute.addon.bench.BenchPatternDetails.InputSpec.of(raw,4)));
        var error=assertThrows(ProductionPlanningException.class,()->NetworkProductionPlanner.plan(target,30000,
                k->k.equals(target)?List.of(pattern):List.of(),k->k.equals(raw)?40000L:0L));
        assertEquals(ProductionPlanningException.Reason.UNREACHABLE,error.reason());
        assertTrue(error.getMessage().contains("10000"));assertTrue(error.getMessage().contains("30000"));
    }
    @Test void randomizedCertificatesNeverExcludeAnyConcreteReachableInventory() {
        var random=new Random(450391);int certificates=0;
        for(int trial=0;trial<150;trial++) {
            int[][] in=new int[8][1],out=new int[8][1];long[][] a=new long[8][1],b=new long[8][1];
            for(int r=0;r<8;r++){in[r][0]=random.nextInt(5);out[r][0]=random.nextInt(5);a[r][0]=1+random.nextInt(4);b[r][0]=1+random.nextInt(4);}
            var g=graph(5,in,a,out,b);var s=stock(20,20,20,20,20);var proof=ConservedResourceBound.prove(g,s,4,10000,()->false);
            if(proof==null)continue;certificates++;verify(g,s,4,10000,proof);
            var actual=s.clone();
            for(int step=0;step<200;step++) {
                int r=random.nextInt(8),from=in[r][0],to=out[r][0];
                if(actual[from].compareTo(BigInteger.valueOf(a[r][0]))>=0) {
                    actual[from]=actual[from].subtract(BigInteger.valueOf(a[r][0]));actual[to]=actual[to].add(BigInteger.valueOf(b[r][0]));
                }
                BigInteger value=BigInteger.ZERO;for(int k=0;k<5;k++)value=value.add(actual[k].multiply(proof.weights().get(k)));
                assertTrue(value.compareTo(proof.available())<=0);
            }
        }
        assertTrue(certificates>0,"random replay must exercise actual certificates");
    }
    /** Independent verification using RAW inputs/outputs, not the constructor's net arrays. */
    private static void verify(CompiledProductionGraph graph,BigInteger[] stock,int target,long amount,ConservedResourceBound.Certificate proof) {
        var w=proof.weights();for(var weight:w)assertTrue(weight.signum()>=0);
        for(int r=0;r<graph.recipes;r++) {
            BigInteger delta=BigInteger.ZERO;
            for(int i=0;i<graph.inputKeys[r].length;i++)delta=delta.subtract(w.get(graph.inputKeys[r][i]).multiply(BigInteger.valueOf(graph.inputCounts[r][i])));
            for(int i=0;i<graph.outputKeys[r].length;i++)delta=delta.add(w.get(graph.outputKeys[r][i]).multiply(BigInteger.valueOf(graph.outputCounts[r][i])));
            assertTrue(delta.signum()<=0,"unverified alternative transition");
        }
        BigInteger total=BigInteger.ZERO;for(int k=0;k<stock.length;k++)total=total.add(w.get(k).multiply(stock[k]));
        assertEquals(total,proof.available());assertEquals(w.get(target).multiply(stock[target].add(BigInteger.valueOf(amount))),proof.required());
        assertTrue(proof.required().compareTo(total)>0);
    }
}
