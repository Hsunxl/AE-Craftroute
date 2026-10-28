package com.aecraftroute.addon.route;

import appeng.api.stacks.AEKey;
import com.aecraftroute.addon.bench.*;
import com.aecraftroute.addon.planner.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.aecraftroute.addon.bench.BenchPatternDetails.InputSpec.of;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated repeated-path lookup comparison. Not representative of live grid/GUI costs. */
@Tag("benchmark")
class RecipePatternMappingBenchmark {
    @Test void indexedLookupVersusPreviousLinearScan() {
        int count=2000, alternatives=64, warmups=3, samples=10;
        var output=BenchAEKey.of("mapping_bench_out");
        var patterns=new ArrayList<appeng.api.crafting.IPatternDetails>();
        var keys=new ArrayList<BenchAEKey>();
        for(int i=0;i<alternatives;i++) {
            var key=BenchAEKey.of("mapping_bench_in_"+i);keys.add(key);
            patterns.add(new BenchPatternDetails(output,1,List.of(of(key,1))));
        }
        var snapshot=new RouteSnapshot.Result(Map.of(output,patterns),Map.of());
        var recipes=new ArrayList<RecipePatternMapping.Recipe>();
        for(int i=0;i<count;i++)recipes.add(new RecipePatternMapping.Recipe("p-"+i,output,Map.of(keys.get(i%alternatives),1L),Map.of(output,1L)));
        var actual=RecipePatternMapping.discover(recipes,snapshot,k->0L,System.nanoTime()+30_000_000_000L);
        assertEquals(oldScan(recipes,patterns),actual);
        for(int i=0;i<warmups;i++){oldScan(recipes,patterns);RecipePatternMapping.discover(recipes,snapshot,k->0L,System.nanoTime()+30_000_000_000L);}
        var old=new long[samples];var indexed=new long[samples];
        for(int i=0;i<samples;i++) {
            long start=System.nanoTime();oldScan(recipes,patterns);old[i]=System.nanoTime()-start;
            start=System.nanoTime();RecipePatternMapping.discover(recipes,snapshot,k->0L,System.nanoTime()+30_000_000_000L);indexed[i]=System.nanoTime()-start;
        }
        Arrays.sort(old);Arrays.sort(indexed);
        System.out.printf(Locale.ROOT,"[mapping-index-bench] paths=%d alternatives=%d samples=%d old_p50_ms=%.3f indexed_p50_ms=%.3f%n",
                count,alternatives,samples,old[samples/2]/1e6,indexed[samples/2]/1e6);
    }
    private Map<String,RecipePatternMapping.Candidates> oldScan(List<RecipePatternMapping.Recipe> recipes,
            List<appeng.api.crafting.IPatternDetails> patterns) {
        long deadline=System.nanoTime()+30_000_000_000L;
        var cached=new IdentityHashMap<appeng.api.crafting.IPatternDetails,PatternVariants.Enumeration>();
        var result=new LinkedHashMap<String,RecipePatternMapping.Candidates>();
        for(var recipe:recipes) {
            RouteCanvasPlanner.check(deadline);boolean complete=true;var found=new ArrayList<PatternVariant>();
            for(var pattern:patterns) {
                RouteCanvasPlanner.check(deadline);
                var variants=cached.get(pattern);
                if(variants==null){variants=PatternVariants.enumerate(pattern,k->0L,deadline);cached.put(pattern,variants);}
                complete &= variants.complete();
                for(var variant:variants.recipes()) {
                    RouteCanvasPlanner.check(deadline);
                    if(variant.inputs().equals(recipe.inputs()) && variant.outputs().equals(recipe.outputs()))found.add(variant.id());
                }
            }
            result.put(recipe.path(),new RecipePatternMapping.Candidates(found,complete));
        }
        return result;
    }
}
