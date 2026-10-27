package com.aecraftroute.addon.planner;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.aecraftroute.addon.planner.ProductionPlanner.*;

class DagBatchPlannerTest {
    private static final Limits LIMITS=new Limits(30_000,3_000_000_000L);
    private Result<String,String> dag(List<Recipe<String,String>> recipes,Map<String,Long> stock,String target,long amount) {
        var planner=new ProductionPlanner<>(recipes,stock,LIMITS);var result=planner.solve(target,amount);
        assertEquals(Status.FEASIBLE,result.status());assertTrue(planner.dagFastPathUsed(),"fell back to state search");
        verify(recipes,stock,result,target,amount);return result;
    }
    private List<Recipe<String,String>> tiers() {
        var recipes=new ArrayList<Recipe<String,String>>();
        for(int i=1;i<=4;i++) {
            recipes.add(new Recipe<>("upgrade"+i,Map.of("t"+(i-1),4L,"tool",1L),Map.of("t"+i,1L,"tool",1L)));
            recipes.add(new Recipe<>("downgrade"+i,Map.of("t"+i,1L),Map.of("t"+(i-1),4L)));
        }
        return recipes;
    }
    @Test void essenceLikeUpgradeDowngradeCyclesUseStockCutDagForLargeOrders() {
        for(long amount:new long[]{300_000L,1_000_000_000L}) {
            var result=dag(tiers(),Map.of("t0",amount*256,"tool",1L),"t4",amount);
            assertEquals(Map.of("t0",amount*256,"tool",1L),result.used());
            assertEquals(Map.of("upgrade1",amount*64,"upgrade2",amount*16,"upgrade3",amount*4,"upgrade4",amount),result.counts());
            assertEquals(4,result.program().size());
        }
    }
    @Test void partialStocksAcrossSeveralTiersAreDeductedOnce() {
        long n=300_000;
        var result=dag(tiers(),Map.of("t0",n*256,"t1",10L,"t2",5L,"t3",2L,"tool",1L),"t4",n);
        assertEquals(n,result.counts().get("upgrade4"));
        assertEquals(n*4-2,result.counts().get("upgrade3"));
        assertEquals(n*16-13,result.counts().get("upgrade2"));
        assertEquals(n*256-248,result.used().get("t0"));
    }
    @Test void sharedChildDemandIsSummedBeforePacketRounding() {
        var recipes=List.of(new Recipe<>("make",Map.of("ore",1L),Map.of("shared",3L)),
                new Recipe<>("left",Map.of("shared",2L),Map.of("left",1L)),
                new Recipe<>("right",Map.of("shared",2L),Map.of("right",1L)),
                new Recipe<>("finish",Map.of("left",1L,"right",1L),Map.of("out",1L)));
        var result=dag(recipes,Map.of("ore",10L,"shared",1L),"out",1);
        assertEquals(1L,result.counts().get("make"));assertEquals(1L,result.used().get("ore"));
        assertEquals(1L,result.used().get("shared"));
    }
    @Test void oneProducerOfSeveralOutputsIsBatchedOnceNotOncePerOutput() {
        var recipes=List.of(new Recipe<>("split",Map.of("ore",1L),Map.of("a",1L,"b",1L)),
                new Recipe<>("finish",Map.of("a",2L,"b",1L),Map.of("out",1L)));
        var result=dag(recipes,Map.of("ore",2L),"out",1);
        assertEquals(2L,result.counts().get("split"));assertEquals(2L,result.used().get("ore"));
    }
    @Test void fibonacciSharingHasLinearProgramSizeDespiteBillionsOfPaths() {
        var recipes=new ArrayList<Recipe<String,String>>();
        recipes.add(new Recipe<>("p0",Map.of("ore",1L),Map.of("k0",1L)));
        recipes.add(new Recipe<>("p1",Map.of("ore",1L),Map.of("k1",1L)));
        for(int i=2;i<=46;i++)recipes.add(new Recipe<>("p"+i,Map.of("k"+(i-1),1L,"k"+(i-2),1L),Map.of("k"+i,1L)));
        long a=1,b=1;for(int i=2;i<=46;i++){long c=a+b;a=b;b=c;}
        var result=dag(recipes,Map.of("ore",b),"k46",1);
        assertEquals(b,result.used().get("ore"));assertEquals(47,result.program().size());
        assertTrue(result.explored()<100);
    }
    @Test void existingTargetStockCannotReplaceNewProduction() {
        var result=dag(List.of(new Recipe<>("make",Map.of("ore",1L),Map.of("out",1L))),
                Map.of("ore",1L,"out",1_000_000L),"out",1);
        assertEquals(Map.of("ore",1L),result.used());assertEquals(Map.of("make",1L),result.counts());
    }
    @Test void aMissingSeedCannotBecomeAFreeCatalyst() {
        var recipe=new Recipe<>("make",Map.of("ore",1L,"tool",1L),Map.of("out",1L,"tool",1L));
        var result=new ProductionPlanner<>(List.of(recipe),Map.of("ore",100L),LIMITS).solve("out",100);
        assertNotEquals(Status.FEASIBLE,result.status());assertTrue(result.program().isEmpty());
    }
    @Test void feedbackRingStillUsesCertifiedCycleFallback() {
        var recipes=List.of(new Recipe<>("grow",Map.of("a",1L,"feed",1L),Map.of("b",2L)),
                new Recipe<>("back",Map.of("b",1L),Map.of("a",1L)));
        var stock=Map.of("a",1L,"feed",1_000_000L);
        var result=new ProductionPlanner<>(recipes,stock,LIMITS).solve("a",100_000);
        assertEquals(Status.FEASIBLE,result.status());verify(recipes,stock,result,"a",100_000);
    }
    @Test void insufficientRawMaterialAndLongOverflowAreNeverCertified() {
        for(long amount:new long[]{300_000L,Long.MAX_VALUE/2}) {
            var result=new ProductionPlanner<>(tiers(),Map.of("t0",255L,"tool",1L),new Limits(128,100_000_000L)).solve("t4",amount);
            assertNotEquals(Status.FEASIBLE,result.status());assertTrue(result.program().isEmpty());
        }
    }
    @Test void randomSharedDagsReplayEveryPhysicalFiringWithReturnedCatalysts() {
        var random=new Random(430384);
        for(int trial=0;trial<80;trial++) {
            var recipes=new ArrayList<Recipe<String,String>>();
            var stock=new HashMap<String,Long>(Map.of("raw",1_000_000L,"tool",1L));
            for(int i=0;i<7;i++) {
                var inputs=new HashMap<String,Long>();inputs.put("tool",1L);
                for(int j=0;j<2;j++){
                    int parent=i==0?-1:random.nextInt(i+1)-1;
                    inputs.merge(parent<0?"raw":"k"+parent,1L+random.nextInt(2),Long::sum);
                }
                recipes.add(new Recipe<>("p"+i,inputs,Map.of("k"+i,1L+random.nextInt(3),"tool",1L)));
                stock.put("k"+i,(long)random.nextInt(3));
            }
            var result=dag(recipes,stock,"k6",5);
            var inv=new HashMap<>(result.used());var byId=new HashMap<String,Recipe<String,String>>();recipes.forEach(r->byId.put(r.id(),r));
            long seed=inv.getOrDefault("k6",0L);
            for(var block:result.program())for(long round=0;round<block.repetitions();round++)for(var step:block.word())for(long n=0;n<step.count();n++) {
                var recipe=byId.get(step.recipe());
                recipe.inputs().forEach((k,v)->assertTrue(inv.getOrDefault(k,0L)>=v,"physical prefix deficit: "+k));
                recipe.inputs().forEach((k,v)->inv.merge(k,-v,Long::sum));recipe.outputs().forEach((k,v)->inv.merge(k,v,Long::sum));
            }
            assertTrue(inv.getOrDefault("k6",0L)-seed>=5);assertEquals(1L,result.used().get("tool"));
        }
    }
    /** Independent bulk replay: each firing's prefix bound and the first/last
     * repetition are checked mathematically, without expanding huge orders. */
    static void verify(List<Recipe<String,String>> recipes,Map<String,Long> available,Result<String,String> result,String target,long increment) {
        var inv=new HashMap<String,BigInteger>();var byId=new HashMap<String,Recipe<String,String>>();
        recipes.forEach(r->byId.put(r.id(),r));result.used().forEach((k,v)->{assertTrue(v<=available.getOrDefault(k,0L));inv.put(k,BigInteger.valueOf(v));});
        var seed=inv.getOrDefault(target,BigInteger.ZERO);var counts=new HashMap<String,Long>();
        for(var block:result.program()) {
            var perRound=new HashMap<String,BigInteger>();var needed=new HashMap<String,BigInteger>();
            for(var step:block.word()) {
                var recipe=byId.get(step.recipe());var keys=new HashSet<>(recipe.inputs().keySet());keys.addAll(recipe.outputs().keySet());
                for(String key:keys) {
                    var a=BigInteger.valueOf(recipe.inputs().getOrDefault(key,0L));
                    var delta=BigInteger.valueOf(recipe.outputs().getOrDefault(key,0L)).subtract(a);
                    var prefix=a.add(delta.negate().max(BigInteger.ZERO).multiply(BigInteger.valueOf(step.count()-1)))
                            .subtract(perRound.getOrDefault(key,BigInteger.ZERO));
                    needed.merge(key,prefix.max(BigInteger.ZERO),BigInteger::max);
                    perRound.merge(key,delta.multiply(BigInteger.valueOf(step.count())),BigInteger::add);
                }
                counts.merge(step.recipe(),Math.multiplyExact(step.count(),block.repetitions()),Math::addExact);
            }
            for(var entry:perRound.entrySet()) {
                String key=entry.getKey();var change=entry.getValue();
                var required=needed.getOrDefault(key,BigInteger.ZERO).add(change.negate().max(BigInteger.ZERO).multiply(BigInteger.valueOf(block.repetitions()-1)));
                assertTrue(inv.getOrDefault(key,BigInteger.ZERO).compareTo(required)>=0,"prefix deficit: "+key);
                inv.merge(key,change.multiply(BigInteger.valueOf(block.repetitions())),BigInteger::add);
                assertTrue(inv.get(key).signum()>=0);assertTrue(inv.get(key).compareTo(BigInteger.valueOf(Long.MAX_VALUE))<=0);
            }
        }
        assertEquals(result.counts(),counts);
        assertTrue(inv.getOrDefault(target,BigInteger.ZERO).subtract(seed).compareTo(BigInteger.valueOf(increment))>=0);
    }
}
