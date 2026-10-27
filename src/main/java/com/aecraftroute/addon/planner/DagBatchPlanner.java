package com.aecraftroute.addon.planner;

import com.aecraftroute.addon.vm.NumericCraftingVM;
import java.math.BigInteger;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Select an acyclic supply policy, then sum ALL parent demands before rounding a
 * shared producer's count. This is only a witness constructor: cycles, scarce
 * alternatives, unsupported seeds and rejected proofs retain the old fallback.
 * Quantities never control iteration count. No live grid or pattern is read here. */
final class DagBatchPlanner {
    private static final BigInteger ZERO=BigInteger.ZERO,ONE=BigInteger.ONE;
    record Candidate(List<NumericCraftingVM.Step> steps,int visited) {}
    private final CompiledProductionGraph graph;
    private final BigInteger[] stock;
    private final BooleanSupplier expired;
    private final int[] rank,choice,color;
    private int visited;
    private DagBatchPlanner(CompiledProductionGraph graph,BigInteger[] stock,BooleanSupplier expired) {
        this.graph=graph;this.stock=stock;this.expired=expired;
        rank=new int[graph.keys];choice=new int[graph.keys];color=new int[graph.keys];
        Arrays.fill(rank,Integer.MAX_VALUE);Arrays.fill(choice,-1);
        for(int k=0;k<graph.keys;k++)if(stock[k].signum()>0)rank[k]=0;
    }
    static Candidate candidate(CompiledProductionGraph graph,BigInteger[] stock,int target,long increment,BooleanSupplier expired) {
        var planner=new DagBatchPlanner(graph,stock,expired);
        try{return planner.build(target,increment);}catch(Abandon | ArithmeticException rejected){return null;}
    }
    private static final class Abandon extends RuntimeException {}
    private void check(){if(expired.getAsBoolean())throw new Abandon();}
    private boolean seeded(int r) {
        for(int i=0;i<graph.seedKeys[r].length;i++)if(stock[graph.seedKeys[r][i]].compareTo(BigInteger.valueOf(graph.seeds[r][i]))<0)return false;
        return true;
    }
    private int recipeRank(int r) {
        if(!seeded(r))return Integer.MAX_VALUE;
        int max=0;
        for(int k:graph.consumed[r]){if(rank[k]==Integer.MAX_VALUE)return Integer.MAX_VALUE;max=Math.max(max,rank[k]);}
        return max+1;
    }
    private boolean select(int key,boolean root) {
        check();if(color[key]==1)return false;if(color[key]==2)return true;
        if(++visited>8192)throw new Abandon();color[key]=1;
        var candidates=Arrays.stream(graph.positiveProducers[key]).boxed()
                .sorted(Comparator.comparingInt(this::recipeRank).thenComparingInt(Integer::intValue)).toList();
        for(int r:candidates) {
            check();if(!seeded(r))continue;
            boolean possible=true;
            for(int k:graph.consumed[r])if(!select(k,false)){possible=false;break;}
            if(possible){choice[key]=r;color[key]=2;return true;}
        }
        color[key]=0;
        if(!root && stock[key].signum()>0){color[key]=2;return true;}
        return false;
    }
    private Candidate build(int target,long increment) {
        for(int pass=0;pass<graph.keys;pass++) {
            check();boolean changed=false;
            for(int r=0;r<graph.recipes;r++) {
                check();int distance=recipeRank(r);
                for(int k:graph.produced[r])if(distance<rank[k]){rank[k]=distance;changed=true;}
            }
            if(!changed)break;
        }
        if(!select(target,true))return null;
        var active=new LinkedHashSet<Integer>();var pending=new ArrayDeque<Integer>();pending.add(choice[target]);
        var edges=new HashMap<Integer,Set<Integer>>();var incoming=new int[graph.recipes];
        var reserve=new BigInteger[graph.keys];Arrays.fill(reserve,ZERO);
        while(!pending.isEmpty()) {
            check();int r=pending.remove();if(!active.add(r))continue;
            var dependencies=new LinkedHashSet<Integer>();
            for(int k:graph.consumed[r])if(choice[k]>=0)dependencies.add(choice[k]);
            edges.put(r,dependencies);
            for(int dep:dependencies){incoming[dep]++;pending.add(dep);}
            for(int i=0;i<graph.seedKeys[r].length;i++){
                int k=graph.seedKeys[r][i];reserve[k]=reserve[k].max(BigInteger.valueOf(graph.seeds[r][i]));
            }
        }
        var ready=new ArrayDeque<Integer>();for(int r:active)if(incoming[r]==0)ready.add(r);
        var order=new ArrayList<Integer>();var count=new long[graph.recipes];
        var demand=new BigInteger[graph.keys];Arrays.fill(demand,ZERO);
        demand[target]=stock[target].add(BigInteger.valueOf(increment));
        while(!ready.isEmpty()) {
            check();int r=ready.remove();order.add(r);BigInteger times=ZERO;
            for(int i=0;i<graph.produced[r].length;i++) {
                int k=graph.produced[r][i];if(choice[k]!=r)continue;
                BigInteger need=demand[k].add(k==target?ZERO:reserve[k]).subtract(stock[k]);
                if(need.signum()>0){var packet=BigInteger.valueOf(graph.production[r][i]);times=times.max(need.add(packet).subtract(ONE).divide(packet));}
            }
            count[r]=times.longValueExact();
            for(int i=0;i<graph.consumed[r].length;i++){
                int k=graph.consumed[r][i];demand[k]=demand[k].add(times.multiply(BigInteger.valueOf(graph.consumption[r][i])));
            }
            for(int dep:edges.get(r))if(--incoming[dep]==0)ready.add(dep);
        }
        if(order.size()!=active.size())return null; // selected recipe graph still has feedback
        for(int k=0;k<graph.keys;k++) {
            check();if(choice[k]<0 && demand[k].add(reserve[k]).compareTo(stock[k])>0)return null;
        }
        var steps=new ArrayList<NumericCraftingVM.Step>();
        for(int i=order.size()-1;i>=0;i--){int r=order.get(i);if(count[r]>0)steps.add(new NumericCraftingVM.Step(r,count[r]));}
        return steps.isEmpty()?null:new Candidate(List.copyOf(steps),visited);
    }
}
