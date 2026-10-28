package com.aecraftroute.addon.planner;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.math.BigInteger;
import static com.aecraftroute.addon.planner.ProductionPlanner.*;
import static org.junit.jupiter.api.Assertions.*;

class LargeBranchCycleRegressionTest {
    @Test void wideLongCycleAtLargeOrders() {
        var recipes=new ArrayList<Recipe<String,String>>();
        var inputs=new LinkedHashMap<String,Long>(); inputs.put("capital",8L);
        for(int i=0;i<32;i++) { inputs.put("side"+i,1L); recipes.add(new Recipe<>("feed"+i,Map.of("raw"+i,1L),Map.of("side"+i,1L))); }
        recipes.add(new Recipe<>("start",inputs,Map.of("stage0",9L)));
        for(int i=0;i<12;i++) recipes.add(new Recipe<>("step"+i,Map.of("stage"+i,1L),Map.of(i==11?"capital":"stage"+(i+1),1L)));
        var stock=new HashMap<String,Long>(); stock.put("capital",8L);
        for(int i=0;i<32;i++)stock.put("raw"+i,100_000_000_000_000_000L);
        for(long amount:new long[]{1000L,1_000_000L,1_000_000_000_000L,1_000_000_000_000_000L,10_000_000_000_000_000L}) {
            var result=new ProductionPlanner<>(recipes,stock,Limits.defaults()).solve("capital",amount);
            assertEquals(Status.FEASIBLE,result.status(),"amount="+amount+", explored="+result.explored());
            assertTrue(result.program().size()<512);
            replay(recipes, stock, result, amount);
        }
    }
    // Independent arithmetic replay: check the first and last repetition of each
    // straight-line word (all intermediate balances vary linearly).
    private static void replay(List<Recipe<String,String>> recipes, Map<String,Long> stock,
                               Result<String,String> result, long amount) {
        var byId=new HashMap<String,Recipe<String,String>>(); recipes.forEach(r->byId.put(r.id(),r));
        var held=new HashMap<String,BigInteger>();
        result.used().forEach((k,v)->{ assertTrue(v<=stock.getOrDefault(k,0L)); held.put(k,BigInteger.valueOf(v)); });
        var initial=held.getOrDefault("capital",BigInteger.ZERO);
        var counts=new HashMap<String,BigInteger>();
        for(var block:result.program()) {
            var delta=new HashMap<String,BigInteger>(); var required=new HashMap<String,BigInteger>();
            for(var step:block.word()) {
                var recipe=byId.get(step.recipe()); assertNotNull(recipe);
                var n=BigInteger.valueOf(step.count());
                var keys=new HashSet<String>(recipe.inputs().keySet()); keys.addAll(recipe.outputs().keySet());
                for(var key:keys) {
                    var in=BigInteger.valueOf(recipe.inputs().getOrDefault(key,0L));
                    var out=BigInteger.valueOf(recipe.outputs().getOrDefault(key,0L));
                    var d=out.subtract(in); var before=delta.getOrDefault(key,BigInteger.ZERO);
                    var prefix=in.add(d.negate().max(BigInteger.ZERO).multiply(n.subtract(BigInteger.ONE))).subtract(before);
                    required.merge(key,prefix,BigInteger::max);
                    delta.put(key,before.add(d.multiply(n)));
                }
                counts.merge(step.recipe(),n.multiply(BigInteger.valueOf(block.repetitions())),BigInteger::add);
            }
            var repeats=BigInteger.valueOf(block.repetitions());
            for(var key:delta.keySet()) {
                var before=held.getOrDefault(key,BigInteger.ZERO); var d=delta.get(key);
                assertTrue(before.add(d.min(BigInteger.ZERO).multiply(repeats.subtract(BigInteger.ONE)))
                        .compareTo(required.getOrDefault(key,BigInteger.ZERO))>=0,"prefix deficit: "+key);
                held.put(key,before.add(d.multiply(repeats)));
                assertTrue(held.get(key).signum()>=0 && held.get(key).compareTo(BigInteger.valueOf(Long.MAX_VALUE))<=0);
            }
        }
        result.counts().forEach((k,v)->assertEquals(BigInteger.valueOf(v),counts.get(k)));
        assertTrue(held.getOrDefault("capital",BigInteger.ZERO).subtract(initial).compareTo(BigInteger.valueOf(amount))>=0);
    }}
