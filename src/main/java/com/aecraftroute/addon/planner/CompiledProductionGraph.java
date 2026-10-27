package com.aecraftroute.addon.planner;

import com.aecraftroute.addon.vm.CompiledNumericProgram;
import com.aecraftroute.addon.vm.NumericProgramCompiler;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Frozen numeric compilation, never a live pattern, slot allocation, stock or proof.
 * Recipe numbers are request-local positions and are rebound by the existing bridge.
 * Full flow equality (including remainders) is the cache version, not an object hash. */
final class CompiledProductionGraph {
    private record Flow(Map<Integer,Long> in,Map<Integer,Long> out) {}
    private record Signature(int keys,List<Flow> flows) {}
    private record Entry(CompiledProductionGraph graph,long at,long weight) {}
    record Stats(long hits,long misses,int entries,long weight) {}
    private static final LinkedHashMap<Signature,Entry> CACHE=new LinkedHashMap<>(16,.75f,true);
    private static final long TTL=60_000_000_000L,MAX_WEIGHT=262_144;
    private static long hits,misses,weight;
    final int keys,recipes;
    final int[][] inputKeys,outputKeys,consumed,produced,seedKeys,producers,positiveProducers;
    final long[][] inputCounts,outputCounts,consumption,production,seeds;
    final CompiledNumericProgram bytecode;

    private CompiledProductionGraph(Signature signature,CompiledNumericProgram bytecode,BooleanSupplier expired) {
        this.keys=signature.keys;this.recipes=signature.flows.size();this.bytecode=bytecode;
        inputKeys=new int[recipes][];outputKeys=new int[recipes][];consumed=new int[recipes][];
        produced=new int[recipes][];seedKeys=new int[recipes][];
        inputCounts=new long[recipes][];outputCounts=new long[recipes][];consumption=new long[recipes][];
        production=new long[recipes][];seeds=new long[recipes][];
        var raw=new ArrayList<List<Integer>>();var positive=new ArrayList<List<Integer>>();
        for(int k=0;k<keys;k++){raw.add(new ArrayList<>());positive.add(new ArrayList<>());}
        for(int r=0;r<recipes;r++) {
            if(expired.getAsBoolean())throw new Incomplete();
            var flow=signature.flows.get(r);
            var in=new TreeMap<>(flow.in);var out=new TreeMap<>(flow.out);
            inputKeys[r]=indices(in);inputCounts[r]=values(in);outputKeys[r]=indices(out);outputCounts[r]=values(out);
            for(int k:outputKeys[r])raw.get(k).add(r);
            var burn=new TreeMap<Integer,Long>();var gain=new TreeMap<Integer,Long>();var seed=new TreeMap<Integer,Long>();
            var all=new TreeSet<>(in.keySet());all.addAll(out.keySet());
            for(int k:all) {
                long a=in.getOrDefault(k,0L),b=out.getOrDefault(k,0L);
                if(a>b)burn.put(k,a-b);if(b>a){gain.put(k,b-a);positive.get(k).add(r);}
                if(a>0 && b>0)seed.put(k,Math.min(a,b));
            }
            consumed[r]=indices(burn);consumption[r]=values(burn);produced[r]=indices(gain);production[r]=values(gain);
            seedKeys[r]=indices(seed);seeds[r]=values(seed);
        }
        producers=raw.stream().map(v->v.stream().mapToInt(Integer::intValue).toArray()).toArray(int[][]::new);
        positiveProducers=positive.stream().map(v->v.stream().mapToInt(Integer::intValue).toArray()).toArray(int[][]::new);
    }
    private static int[] indices(SortedMap<Integer,Long> map){return map.keySet().stream().mapToInt(Integer::intValue).toArray();}
    private static long[] values(SortedMap<Integer,Long> map){return map.values().stream().mapToLong(Long::longValue).toArray();}
    private static final class Incomplete extends RuntimeException {}

    static CompiledProductionGraph compile(int keys,int[][] inputKeys,long[][] inputs,int[][] outputKeys,long[][] outputs,BooleanSupplier expired) {
        if(expired.getAsBoolean())return null;
        if(keys<0 || inputKeys.length!=inputs.length || inputKeys.length!=outputKeys.length || inputKeys.length!=outputs.length)
            throw new IllegalArgumentException("Numeric recipe dimensions");
        var flows=new ArrayList<Flow>();long size=keys+1L;
        for(int r=0;r<inputKeys.length;r++) {
            if(expired.getAsBoolean())return null;
            if(inputKeys[r].length!=inputs[r].length || outputKeys[r].length!=outputs[r].length || outputKeys[r].length==0)
                throw new IllegalArgumentException("Numeric recipe operands");
            var in=new TreeMap<Integer,Long>();var out=new TreeMap<Integer,Long>();
            for(int i=0;i<inputKeys[r].length;i++)operand(in,inputKeys[r][i],inputs[r][i],keys);
            for(int i=0;i<outputKeys[r].length;i++)operand(out,outputKeys[r][i],outputs[r][i],keys);
            size+=1L+4L*(in.size()+out.size());flows.add(new Flow(Map.copyOf(in),Map.copyOf(out)));
        }
        var signature=new Signature(keys,List.copyOf(flows));
        if(expired.getAsBoolean())return null;
        var cached=get(signature);if(cached!=null)return expired.getAsBoolean()?null:cached;
        var program=NumericProgramCompiler.compile(keys,inputKeys,inputs,outputKeys,outputs,expired);
        if(program==null)return null;
        CompiledProductionGraph graph;
        try {graph=new CompiledProductionGraph(signature,program,expired);}catch(Incomplete timeout){return null;}
        if(expired.getAsBoolean())return null;
        put(signature,graph,size);return graph;
    }
    private static void operand(Map<Integer,Long> values,int key,long amount,int keys) {
        if(key<0 || key>=keys || amount<=0 || values.putIfAbsent(key,amount)!=null)
            throw new IllegalArgumentException("Invalid or duplicate numeric operand");
    }
    private static synchronized CompiledProductionGraph get(Signature signature) {
        expire();var found=CACHE.get(signature);if(found==null){misses++;return null;}hits++;return found.graph;
    }
    private static synchronized void put(Signature signature,CompiledProductionGraph graph,long size) {
        if(size>MAX_WEIGHT)return;expire();var old=CACHE.put(signature,new Entry(graph,System.nanoTime(),size));
        if(old!=null)weight-=old.weight;weight+=size;
        var iterator=CACHE.values().iterator();
        while(CACHE.size()>32 || weight>MAX_WEIGHT){weight-=iterator.next().weight;iterator.remove();}
    }
    private static void expire() {
        long now=System.nanoTime();var iterator=CACHE.values().iterator();
        while(iterator.hasNext()){var e=iterator.next();if(now-e.at>=TTL){weight-=e.weight;iterator.remove();}}
    }
    static synchronized Stats stats(){expire();return new Stats(hits,misses,CACHE.size(),weight);}
    static synchronized void clear(){CACHE.clear();hits=misses=weight=0;}
}
