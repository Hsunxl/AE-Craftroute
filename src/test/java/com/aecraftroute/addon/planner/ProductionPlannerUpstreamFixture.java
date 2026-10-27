package com.aecraftroute.addon.planner;

import java.util.List;
import java.util.Map;
import static com.aecraftroute.addon.planner.ProductionPlanner.*;

/** Synthetic topology, not a reconstruction of an unexported live AE2 network. */
final class ProductionPlannerUpstreamFixture {
    static List<Recipe<String,String>> recipes() {
        return List.of(
                new Recipe<>("germinate", Map.of("dust",8L,"charged",8L,"sand",32L),Map.of("seed",32L)),
                new Recipe<>("purify",Map.of("seed",1L),Map.of("pure",1L)),
                new Recipe<>("grind",Map.of("pure",1L),Map.of("dust",1L)),
                new Recipe<>("sand-from-emc",Map.of("emc",128L),Map.of("sand",128L)),
                new Recipe<>("emc-from-feed",Map.of("feed",1L),Map.of("emc",128L)),
                new Recipe<>("charge-quartz",Map.of("quartz",8L),Map.of("charged",8L)));
    }

    static Map<String,Long> stock(long dust) {
        return Map.of("dust",dust,"charged",4L,"emc",16L,"feed",1_000_000L,"quartz",1_000_000L);
    }

    public static void main(String[] args) {
        long budgetMillis=args.length==0?500:Long.parseLong(args[0]);
        int repeats=args.length<2?1:Integer.parseInt(args[1]);
        if(budgetMillis<=0 || repeats<=0) throw new IllegalArgumentException("Budget and repeats must be positive");
        long budgetNanos=Math.multiplyExact(budgetMillis,1_000_000L);
        System.out.println("sample,dust,increment,status,explored,elapsed_ms,blocks");
        for(int sample=1;sample<=repeats;sample++) for(long dust:new long[]{40,84}) for(long amount:new long[]{100,1000,1360,2000}) {
            long start=System.nanoTime();
            var result=new ProductionPlanner<>(recipes(),stock(dust),new Limits(30_000,budgetNanos)).solve("dust",amount);
            System.out.printf(java.util.Locale.ROOT,"%d,%d,%d,%s,%d,%.3f,%d%n",sample,dust,amount,result.status(),result.explored(),(System.nanoTime()-start)/1e6,result.program().size());
        }
    }
}
