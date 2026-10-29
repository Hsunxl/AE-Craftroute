package com.aecraftroute.addon.planner;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.aecraftroute.addon.planner.ProductionPlanner.*;

class SccCandidatePlannerTest {
    private Recipe<String,String> r(String name,Map<String,Long> in,String output,long amount) {
        return new Recipe<>(name,in,Map.of(output,amount));
    }
    private SccCandidatePlanner.Candidate candidate(List<Recipe<String,String>> recipes,Map<String,Long> stock,
            String target,long amount) {
        var keys=new LinkedHashSet<String>(stock.keySet());
        for(var recipe:recipes){keys.addAll(recipe.inputs().keySet());keys.addAll(recipe.outputs().keySet());}
        var names=new ArrayList<>(keys);var in=new int[recipes.size()][];var out=new int[recipes.size()][];
        var a=new long[recipes.size()][];var b=new long[recipes.size()][];
        for(int i=0;i<recipes.size();i++) {
            in[i]=recipes.get(i).inputs().keySet().stream().mapToInt(names::indexOf).toArray();
            a[i]=recipes.get(i).inputs().values().stream().mapToLong(Long::longValue).toArray();
            out[i]=recipes.get(i).outputs().keySet().stream().mapToInt(names::indexOf).toArray();
            b[i]=recipes.get(i).outputs().values().stream().mapToLong(Long::longValue).toArray();
        }
        var graph=CompiledProductionGraph.compile(names.size(),in,a,out,b,()->false);
        var available=names.stream().map(k->BigInteger.valueOf(stock.getOrDefault(k,0L))).toArray(BigInteger[]::new);
        var candidate=SccCandidatePlanner.candidate(graph,available,names.indexOf(target),amount,
                new Limits(30000,3_000_000_000L),()->false);
        if(candidate!=null) {
            var program=new ArrayList<Block<String>>();var counts=new HashMap<String,Long>();
            for(var block:candidate.program()) {
                var word=new ArrayList<Step<String>>();
                for(var step:block.word()) {
                    assertTrue(step.recipe()>=0,"synthetic consumer leaked");
                    String id=recipes.get(step.recipe()).id();word.add(new Step<>(id,step.count()));
                    counts.merge(id,Math.multiplyExact(step.count(),block.repetitions()),Math::addExact);
                }
                program.add(new Block<>(word,block.repetitions()));
            }
            DagBatchPlannerTest.verify(recipes,stock,new Result<>(Status.FEASIBLE,program,stock,counts,candidate.explored()),target,amount);
        }
        return candidate;
    }
    @Test void sharedCycleDemandIsAggregatedBeforeSpendingItsSeed() {
        var recipes=List.of(r("grow",Map.of("a",1L,"fuel",1L),"a",2),
                r("left",Map.of("a",1L),"b",1),r("right",Map.of("a",1L),"c",1),
                r("join",Map.of("b",1L,"c",1L),"target",1));
        var result=candidate(recipes,Map.of("a",1L,"fuel",200L),"target",100);
        assertNotNull(result);
        long growth=result.program().stream().flatMap(block->block.word().stream()
                .filter(step->step.recipe()==0).map(step->Math.multiplyExact(step.count(),block.repetitions())))
                .mapToLong(Long::longValue).sum();
        assertEquals(200,growth); // The constructive path retains one working seed.
    }
    @Test void multipleRegionsPassAggregatedExternalDemandUpstream() {
        var recipes=List.of(r("growA",Map.of("a",1L,"fuel",1L),"a",2),
                r("growB",Map.of("b",1L,"a",1L),"b",2),
                r("join",Map.of("a",1L,"b",1L),"target",1));
        assertNotNull(candidate(recipes,Map.of("a",1L,"b",1L,"fuel",20L),"target",10));
        assertNull(candidate(recipes,Map.of("a",1L,"b",1L,"fuel",19L),"target",10));
    }
    @Test void missingSeedDoesNotBecomeSyntheticStock() {
        var recipes=List.of(r("grow",Map.of("a",1L,"fuel",1L),"a",2));
        assertNull(candidate(recipes,Map.of("fuel",100L),"a",10));
    }
    @Test void twoDemandedKeysInsideOneCycleShareARegionalSolve() {
        var recipes=List.of(r("ab",Map.of("a",1L,"fuel",1L),"b",2),
                r("ba",Map.of("b",1L),"a",1),
                r("join",Map.of("a",1L,"b",1L),"target",1));
        assertNotNull(candidate(recipes,Map.of("a",1L,"fuel",20L),"target",3));
    }
    @Test void independentGrowthRegionsReplayAcrossDifferentPacketSizes() {
        for(int packet=2;packet<=5;packet++)for(int amount=1;amount<=8;amount++) {
            var recipes=List.of(r("growA",Map.of("a",1L,"fuel",1L),"a",packet),
                    r("growB",Map.of("b",1L,"fuel",1L),"b",packet),
                    r("join",Map.of("a",2L,"b",3L),"target",1));
            assertNotNull(candidate(recipes,Map.of("a",1L,"b",1L,"fuel",100L),"target",amount),
                    "packet="+packet+" amount="+amount);
        }
    }
    @Test void alternativesAndByproductsAreNotSilentlyDropped() {
        var recipes=List.of(r("grow",Map.of("a",1L,"fuel",1L),"a",2),
                r("alternative",Map.of("fuel",1L),"a",1));
        assertNull(candidate(recipes,Map.of("a",1L,"fuel",100L),"a",10));
        assertNull(candidate(List.of(new Recipe<>("multi",Map.of("a",1L),Map.of("a",2L,"b",1L))),
                Map.of("a",1L),"a",10));
    }
    @Test void expiredAttemptReturnsNoPartialProgram() {
        var graph=CompiledProductionGraph.compile(1,new int[][]{{0}},new long[][]{{1}},
                new int[][]{{0}},new long[][]{{2}},()->false);
        var limits=Limits.defaults();
        assertNull(SccCandidatePlanner.candidate(graph,new BigInteger[]{BigInteger.ONE},0,10,limits,()->true));
        assertThrows(CancellationException.class,()->SccCandidatePlanner.candidate(graph,new BigInteger[]{BigInteger.ONE},
                0,10,limits,()->{throw new CancellationException();}));
    }
}
