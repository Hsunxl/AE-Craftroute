package com.aecraftroute.addon.planner;

import java.math.BigInteger;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Restricted single-output/unique-producer SCC composition, never a verdict.
 * External supply is provisional. Only the caller's full-network replay may
 * accept the assembled witness. Local planners cannot recursively enter here. */
final class SccCandidatePlanner {
    record Candidate(List<ProductionPlanner.Block<Integer>> program, int explored) {}

    static Candidate candidate(CompiledProductionGraph graph, BigInteger[] stock,
            int target, long increment, ProductionPlanner.Limits limits, BooleanSupplier expired) {
        long started=System.nanoTime();
        BooleanSupplier stopped=()->expired.getAsBoolean() || System.nanoTime()-started>=limits.nanos();
        try {return build(graph,stock,target,increment,limits,stopped,started);}
        catch(ArithmeticException rejected) {return null;}
    }

    private static Candidate build(CompiledProductionGraph graph, BigInteger[] stock,
            int target,long increment,ProductionPlanner.Limits limits,BooleanSupplier stopped,long started) {
        var relevant=new boolean[graph.keys];var edges=new int[graph.keys][];
        Arrays.fill(edges,new int[0]);var pending=new ArrayDeque<Integer>();pending.add(target);
        while(!pending.isEmpty()) {
            if(stopped.getAsBoolean())return null;
            int key=pending.remove();if(relevant[key])continue;relevant[key]=true;
            var producers=graph.producers[key];
            if(producers.length>1)return null; // Alternatives stay with the general solver.
            if(producers.length==0)continue;
            int r=producers[0];if(graph.outputKeys[r].length!=1)return null;
            edges[key]=graph.inputKeys[r];for(int input:edges[key])pending.add(input);
        }
        var topology=ProductionGraphTopology.analyze(edges,relevant,stopped);
        if(topology==null || !topology.cyclic())return null;
        var demand=new BigInteger[graph.keys];Arrays.fill(demand,BigInteger.ZERO);
        demand[target]=stock[target].add(BigInteger.valueOf(increment));
        var regions=new ArrayList<List<ProductionPlanner.Block<Integer>>>();int explored=0;
        for(int component=0;component<topology.demandOrder().size();component++) {
            if(stopped.getAsBoolean())return null;
            int[] members=topology.demandOrder().get(component);
            var required=new LinkedHashMap<Integer,Long>();
            for(int k:members)if(demand[k].signum()>0)required.put(k,demand[k].longValueExact());
            if(required.isEmpty())continue;
            var localRecipes=new ArrayList<ProductionPlanner.Recipe<Integer,Integer>>();
            var available=new LinkedHashMap<Integer,Long>();
            for(int k:members) {
                if(stopped.getAsBoolean())return null;
                available.put(k,stock[k].longValueExact());
                if(graph.producers[k].length==0)continue;
                int r=graph.producers[k][0];var inputs=new LinkedHashMap<Integer,Long>();
                for(int i=0;i<graph.inputKeys[r].length;i++) {
                    int input=graph.inputKeys[r][i];inputs.put(input,graph.inputCounts[r][i]);
                    if(topology.componentOf()[input]!=component)available.put(input,Long.MAX_VALUE);
                }
                localRecipes.add(new ProductionPlanner.Recipe<>(r,inputs,Map.of(k,graph.outputCounts[r][0])));
            }
            // A synthetic consumer aggregates every incoming demand before solving,
            // so two downstream branches cannot independently spend the same seed.
            int sink=graph.keys;
            localRecipes.add(new ProductionPlanner.Recipe<>(-1,required,Map.of(sink,1L)));
            long remaining=limits.nanos()-(System.nanoTime()-started);
            if(remaining<=0 || explored>=limits.states())return null;
            var result=new ProductionPlanner<>(localRecipes,available,
                    new ProductionPlanner.Limits(limits.states()-explored,remaining),false).solve(sink,1);
            if(stopped.getAsBoolean() || result.status()!=ProductionPlanner.Status.FEASIBLE)return null;
            explored=Math.addExact(explored,result.explored());
            for(var entry:result.used().entrySet()) {
                int k=entry.getKey();
                if(k<graph.keys && topology.componentOf()[k]!=component)
                    demand[k]=demand[k].add(BigInteger.valueOf(entry.getValue()));
            }
            var program=new ArrayList<ProductionPlanner.Block<Integer>>();
            for(var block:result.program()) {
                if(stopped.getAsBoolean())return null;
                var word=block.word().stream().filter(step->step.recipe()!=-1).toList();
                if(!word.isEmpty())program.add(new ProductionPlanner.Block<>(word,block.repetitions()));
            }
            regions.add(List.copyOf(program));
        }
        var program=new ArrayList<ProductionPlanner.Block<Integer>>();
        for(int i=regions.size()-1;i>=0;i--) {
            if(stopped.getAsBoolean())return null;
            program.addAll(regions.get(i));
        }
        return program.isEmpty()?null:new Candidate(List.copyOf(program),explored);
    }
    private SccCandidatePlanner() {}
}
