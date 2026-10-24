package com.aecraftroute.addon.planner;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.aecraftroute.addon.bench.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.aecraftroute.addon.bench.BenchPatternDetails.InputSpec.of;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in measurement, not timing assertions and not a server tick benchmark.
 * Includes graph traversal, variant allocation, proof search/cache lookup and
 * current-pattern binding. Excludes live RouteSnapshot grid/inventory collection.
 * Both measured modes run in a warmed JVM, alternating their order per sample.
 */
@Tag("benchmark")
class ProductionPlannerBenchmark {
    record Scenario(String name, AEKey target, long amount, List<IPatternDetails> patterns, Map<AEKey,Long> stock) {}
    record Sample(CertifiedCraftingPlan plan, long nanos) {}
    private static final int WARMUPS=Integer.getInteger("aecraftroute.benchmark.warmups",10);
    private static final int SAMPLES=Integer.getInteger("aecraftroute.benchmark.samples",30);

    @Test void representativeExactProductionNets() throws Exception {
        assertTrue(WARMUPS>=0 && SAMPLES>=3);
        var report=new StringBuilder("scenario,patterns,amount,samples,first_cached_ms,uncached_p50_ms,uncached_p95_ms,cached_p50_ms,cached_p95_ms,speedup_p50,blocks,batches,cache_hits\n");
        System.out.printf(Locale.ROOT,"[production-bench] java=%s vm=%s os=%s arch=%s processors=%d maxHeap=%d warmups=%d samples=%d%n",
                System.getProperty("java.version"),System.getProperty("java.vm.name"),System.getProperty("os.name"),
                System.getProperty("os.arch"),Runtime.getRuntime().availableProcessors(),Runtime.getRuntime().maxMemory(),WARMUPS,SAMPLES);
        for(var scenario:List.of(chain(16),chain(64),chain(256),alternatives(),ring(3,1000),ring(8,1000),ring(2,1_000_000_000L),dataDust(),mixed())) {
            var index=new LinkedHashMap<AEKey,List<IPatternDetails>>();
            for(var pattern:scenario.patterns()) for(var out:pattern.getOutputs())
                index.computeIfAbsent(out.what(),k->new ArrayList<>()).add(pattern);
            NetworkProductionPlanner.clearCache();
            var first=measure(scenario,index,true);
            for(int i=0;i<WARMUPS;i++) { measure(scenario,index,false); measure(scenario,index,true); }
            long[] uncached=new long[SAMPLES], cached=new long[SAMPLES];
            for(int i=0;i<SAMPLES;i++) {
                Sample plain,hit;
                if(i%2==0) { plain=measure(scenario,index,false); hit=measure(scenario,index,true); }
                else { hit=measure(scenario,index,true); plain=measure(scenario,index,false); }
                equivalent(first.plan(),plain.plan()); equivalent(first.plan(),hit.plan());
                uncached[i]=plain.nanos(); cached[i]=hit.nanos();
            }
            Arrays.sort(uncached); Arrays.sort(cached);
            long batches=first.plan().patternTimes().values().stream().mapToLong(Long::longValue).sum();
            String row=String.format(Locale.ROOT,"%s,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.2f,%d,%d,%d%n",
                    scenario.name(),scenario.patterns().size(),scenario.amount(),SAMPLES,first.nanos()/1e6,
                    percentile(uncached,.5),percentile(uncached,.95),percentile(cached,.5),percentile(cached,.95),
                    percentile(uncached,.5)/percentile(cached,.5),first.plan().program().size(),batches,
                    NetworkProductionPlanner.cacheStats().hits());
            report.append(row); System.out.print("[production-bench] "+row);
        }
        Path file=Path.of("build/reports/productionBenchmark/results.csv");
        Files.createDirectories(file.getParent()); Files.writeString(file,report,StandardCharsets.UTF_8);
        NetworkProductionPlanner.clearCache();
    }

    private Sample measure(Scenario s,Map<AEKey,List<IPatternDetails>> index,boolean cache) {
        long start=System.nanoTime();
        var plan=NetworkProductionPlanner.plan(s.target(),s.amount(),k->index.getOrDefault(k,List.of()),
                k->s.stock().getOrDefault(k,0L),ProductionPlanner.Limits.defaults(),cache);
        return new Sample(plan,System.nanoTime()-start);
    }
    private double percentile(long[] sorted,double p) { return sorted[(int)Math.ceil(p*sorted.length)-1]/1e6; }
    private void equivalent(CertifiedCraftingPlan expected,CertifiedCraftingPlan actual) {
        assertEquals(expected.finalOutput(),actual.finalOutput());
        assertEquals(expected.program(),actual.program());
        assertEquals(expected.patternTimes(),actual.patternTimes());
        assertEquals(expected.bytes(),actual.bytes());
        var a=new HashMap<AEKey,Long>(); var b=new HashMap<AEKey,Long>();
        expected.usedItems().forEach(e->a.put(e.getKey(),e.getLongValue()));
        actual.usedItems().forEach(e->b.put(e.getKey(),e.getLongValue()));
        assertEquals(a,b);
    }
    private Scenario chain(int depth) {
        var patterns=new ArrayList<IPatternDetails>();
        for(int i=1;i<=depth;i++) patterns.add(new BenchPatternDetails(key("chain"+i),1,List.of(of(key("chain"+(i-1)),1))));
        return new Scenario("chain-"+depth,key("chain"+depth),1000,patterns,Map.of(key("chain0"),1000L));
    }
    private Scenario alternatives() {
        var patterns=new ArrayList<IPatternDetails>(); var stock=new LinkedHashMap<AEKey,Long>();
        for(int i=0;i<16;i++) {
            var ore=key("alternative"+i); stock.put(ore, i==15?1000L:0L);
            patterns.add(new BenchPatternDetails(key("alternative-out"),1,List.of(of(ore,1))));
        }
        return new Scenario("alternatives-16",key("alternative-out"),1000,patterns,stock);
    }
    private Scenario ring(int size,long amount) {
        var patterns=new ArrayList<IPatternDetails>(); var fuel=key("ring-fuel");
        for(int i=0;i<size;i++) patterns.add(new BenchPatternDetails(key("ring"+((i+1)%size)),i==size-1?3:1,List.of(of(key("ring"+i),1),of(fuel,1))));
        return new Scenario("ring-"+size+"-"+amount,key("ring0"),amount,patterns,Map.of(key("ring0"),1L,fuel,10_000_000_000L));
    }
    private Scenario dataDust() {
        var dust=key("dust"); var seed=key("seed"); var pure=key("pure"); var charged=key("charged"); var sand=key("sand");
        return new Scenario("data-dust-1000",dust,1000,List.of(
                new BenchPatternDetails(seed,32,List.of(of(dust,8),of(charged,8),of(sand,32))),
                new BenchPatternDetails(pure,1,List.of(of(seed,1))),new BenchPatternDetails(dust,1,List.of(of(pure,1)))),
                Map.of(dust,8L,charged,1_000_000L,sand,1_000_000L));
    }
    private Scenario mixed() {
        var a=key("mix-a"); var b=key("mix-b"); var out=key("mix-out");
        return new Scenario("mixed-multiplier-8",out,1,List.of(new BenchPatternDetails(out,1,
                List.of(new BenchPatternDetails.InputSpec(a,1,8,List.of(b))))),Map.of(a,4L,b,4L));
    }
    private BenchAEKey key(String name) { return BenchAEKey.of("benchmark_"+name); }
}
