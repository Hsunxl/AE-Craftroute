package com.aecraftroute.addon.planner;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import com.aecraftroute.addon.compiler.IFiniteUseInput;
import java.util.*;
import java.util.function.Function;

/** Capacitated bipartite matching of unit consumed-input families against real
 * key stock. Equal candidate-key sets share one demand node; after solving, all
 * flow is distributed back into the original physical provider slots. No
 * synthetic family key is ever extracted, persisted, or sent to a provider. */
final class FuzzyFamilyAllocator {
    private static final class Family {
        final Set<AEKey> keys;
        final List<Integer> slots=new ArrayList<>();
        final Map<AEKey,Edge> incoming=new LinkedHashMap<>();
        long demand;
        Family(Set<AEKey> keys) { this.keys=keys; }
    }
    private static final class Edge {
        final int to,reverse;
        final long initial;
        long capacity;
        Edge(int to,int reverse,long capacity) { this.to=to; this.reverse=reverse; this.capacity=capacity; initial=capacity; }
    }
    static Optional<PatternVariant> allocate(IPatternDetails pattern,Function<AEKey,Long> stock,long deadlineNanos) {
        var families=new LinkedHashMap<Set<AEKey>,Family>();
        var allKeys=new LinkedHashSet<AEKey>(); long total=0;
        var inputs=pattern.getInputs();
        for(int slot=0;slot<inputs.length;slot++) {
            check(deadlineNanos); var input=inputs[slot];
            if(input.getMultiplier()<=0 || input instanceof IFiniteUseInput finite && finite.durabilityUses()!=Long.MAX_VALUE)
                return Optional.empty();
            var keys=new LinkedHashSet<AEKey>(); var options=input.getPossibleInputs();
            if(options==null || options.length==0) return Optional.empty();
            for(var option:options) {
                check(deadlineNanos);
                if(option==null || option.what()==null || option.amount()!=1 || input.getRemainingKey(option.what())!=null)
                    return Optional.empty();
                keys.add(option.what());
            }
            var family=families.computeIfAbsent(keys,Family::new);
            family.slots.add(slot); family.demand=Math.addExact(family.demand,input.getMultiplier());
            total=Math.addExact(total,input.getMultiplier()); allKeys.addAll(keys);
        }
        if(inputs.length==0) return Optional.empty();
        int source=0,firstKey=1,firstFamily=firstKey+allKeys.size(),sink=firstFamily+families.size();
        var graph=new ArrayList<List<Edge>>(sink+1); for(int i=0;i<=sink;i++) graph.add(new ArrayList<>());
        var keyIds=new LinkedHashMap<AEKey,Integer>();
        int at=firstKey;
        for(var key:allKeys) {
            check(deadlineNanos); keyIds.put(key,at);
            add(graph,source,at++,Math.min(total,Math.max(0,stock.apply(key))));
        }
        at=firstFamily;
        for(var family:families.values()) {
            check(deadlineNanos);
            for(var key:family.keys) family.incoming.put(key,add(graph,keyIds.get(key),at,family.demand));
            add(graph,at++,sink,family.demand);
        }
        long sent=0; var level=new int[sink+1];
        while(sent<total && levels(graph,source,sink,level,deadlineNanos)) {
            var next=new int[sink+1]; long pushed;
            while(sent<total && (pushed=push(graph,source,sink,total-sent,level,next,deadlineNanos))>0) sent=Math.addExact(sent,pushed);
        }
        if(sent!=total) return Optional.empty();
        var slots=new PatternVariant.Slot[inputs.length];
        for(var family:families.values()) {
            var flow=new LinkedHashMap<AEKey,Long>();
            family.incoming.forEach((key,edge)->flow.put(key,edge.initial-edge.capacity));
            for(int slot:family.slots) {
                check(deadlineNanos); long left=inputs[slot].getMultiplier(); var portions=new ArrayList<PatternVariant.Portion>();
                for(var entry:flow.entrySet()) {
                    long take=Math.min(left,entry.getValue());
                    if(take>0) {
                        portions.add(new PatternVariant.Portion(new GenericStack(entry.getKey(),1),take));
                        entry.setValue(entry.getValue()-take); left-=take;
                    }
                    if(left==0) break;
                }
                if(left!=0) throw new IllegalStateException("Incomplete fuzzy family flow distribution");
                slots[slot]=new PatternVariant.Slot(portions);
            }
        }
        return Optional.of(new PatternVariant(pattern,List.of(slots)));
    }
    private static Edge add(List<List<Edge>> graph,int from,int to,long capacity) {
        var forward=new Edge(to,graph.get(to).size(),capacity); var reverse=new Edge(from,graph.get(from).size(),0);
        graph.get(from).add(forward); graph.get(to).add(reverse); return forward;
    }
    private static boolean levels(List<List<Edge>> graph,int source,int sink,int[] level,long deadline) {
        Arrays.fill(level,-1); level[source]=0; var queue=new ArrayDeque<Integer>(); queue.add(source);
        while(!queue.isEmpty()) {
            check(deadline); int at=queue.remove();
            for(var edge:graph.get(at)) if(edge.capacity>0 && level[edge.to]<0) { level[edge.to]=level[at]+1; queue.add(edge.to); }
        }
        return level[sink]>=0;
    }
    private static long push(List<List<Edge>> graph,int at,int sink,long limit,int[] level,int[] next,long deadline) {
        check(deadline); if(at==sink) return limit;
        for(;next[at]<graph.get(at).size();next[at]++) {
            var edge=graph.get(at).get(next[at]);
            if(edge.capacity<=0 || level[edge.to]!=level[at]+1) continue;
            long flow=push(graph,edge.to,sink,Math.min(limit,edge.capacity),level,next,deadline);
            if(flow>0) { edge.capacity-=flow; graph.get(edge.to).get(edge.reverse).capacity+=flow; return flow; }
        }
        return 0;
    }
    private static void check(long deadline) {
        if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Fuzzy family allocation cancelled");
        if(deadline!=Long.MAX_VALUE && System.nanoTime()-deadline>=0)
            throw new ProductionPlanningException(ProductionPlanningException.Reason.LIMIT,"Fuzzy族库存分配超过计算预算；尚未判定可行性，不是缺料");
    }
    private FuzzyFamilyAllocator() {}
}
