package com.aecraftroute.addon.route;

import com.aecraftroute.addon.planner.ProductionPlanner;
import com.aecraftroute.addon.planner.ProductionPlanningException;
import java.math.BigInteger;
import java.util.*;
import static com.aecraftroute.addon.planner.ProductionPlanningException.Reason.*;

/** A finite canvas is a minimum physical execution contract, not a material filter.
 * Every branch has its own completion colour; physical inventory is deliberately shared.
 * Completion colours can only be produced by the selected physical recipe. Additional
 * executions remain possible, which is essential for growing cyclic production. */
public final class ForcedRoutePlanner<K,R> {
    public record Branch<R>(String path, Set<R> recipes, long minimum) {
        public Branch { recipes=Set.copyOf(recipes); if(path==null || recipes.isEmpty() || minimum<=0) throw new IllegalArgumentException("Invalid branch"); }
    }
    private record Marker(String path) {}
    private record Tagged<R>(R physical, String path) {}
    public record Proof<K,R>(Map<K,Long> used, Map<R,Long> counts,
                              List<ProductionPlanner.Block<R>> program, Map<String,Long> branchCounts) {}
    public Proof<K,R> solve(K target,long increment,List<ProductionPlanner.Recipe<K,R>> recipes,
                           Map<K,Long> stock,List<Branch<R>> branches,ProductionPlanner.Limits limits) {
        if(increment<=0 || branches.isEmpty()) throw new ProductionPlanningException(INVALID,"画布没有强制分支或目标数量无效");
        try {
            var byId=new LinkedHashMap<R,ProductionPlanner.Recipe<K,R>>();
            var net=new ArrayList<ProductionPlanner.Recipe<Object,Tagged<R>>>();
            for(var r:recipes) byId.put(r.id(),r);
            var goals=new LinkedHashMap<Object,Long>();
            goals.put(target,Math.addExact(Math.max(0,stock.getOrDefault(target,0L)),increment));
            var paths=new HashSet<String>();
            for(var branch:branches) {
                if(!paths.add(branch.path())) throw new ProductionPlanningException(INVALID,"重复的画布路径："+branch.path());
                var marker=new Marker(branch.path()); goals.put(marker,branch.minimum());
                for(var id:branch.recipes()) {
                    var r=byId.get(id);
                    if(r==null) throw new ProductionPlanningException(INVALID,"路径样板不在冻结网络中");
                    var outputs=objects(r.outputs()); outputs.put(marker,1L);
                    net.add(new ProductionPlanner.Recipe<>(new Tagged<>(id,branch.path()),objects(r.inputs()),outputs));
                }
            }
            // Prefer a marked firing when it can satisfy both a physical demand and
            // a branch obligation; ordinary firings remain available as fallback.
            for(var r:recipes) net.add(new ProductionPlanner.Recipe<>(new Tagged<>(r.id(),null),objects(r.inputs()),objects(r.outputs())));
            Object goal=new Marker("\u0000goal");
            net.add(new ProductionPlanner.Recipe<>(new Tagged<R>(null,null),goals,Map.of(goal,1L)));
            var result=new ProductionPlanner<>(net,objects(stock),limits).solve(goal,1);
            if(result.status()!=ProductionPlanner.Status.FEASIBLE) throw new ProductionPlanningException(
                    result.status()==ProductionPlanner.Status.LIMIT?LIMIT:UNREACHABLE,
                    result.status()==ProductionPlanner.Status.LIMIT?"强制画布程序在预算内未判定；不是缺料":"共享库存无法执行所有强制分支并达到目标净增量");
            var program=new ArrayList<ProductionPlanner.Block<R>>();
            var branchCounts=new LinkedHashMap<String,Long>();
            result.counts().forEach((id,n)-> { if(id.path()!=null) branchCounts.merge(id.path(),n,Math::addExact); });
            for(var b:branches) if(branchCounts.getOrDefault(b.path(),0L)<b.minimum())
                throw new ProductionPlanningException(UNSUPPORTED,"内部证明不一致：强制分支未完成 "+b.path());
            for(var b:result.program()) {
                var word=new ArrayList<ProductionPlanner.Step<R>>();
                for(var s:b.word()) if(s.recipe().physical()!=null) word.add(new ProductionPlanner.Step<>(s.recipe().physical(),s.count()));
                if(!word.isEmpty()) program.add(new ProductionPlanner.Block<>(word,b.repetitions()));
            }
            // Independently replay the PHYSICAL program after erasing every virtual marker.
            // BigInteger prefix summaries preserve loops without expanding large counts.
            var required=new LinkedHashMap<K,BigInteger>(); var delta=new LinkedHashMap<K,BigInteger>();
            var counts=new LinkedHashMap<R,Long>();
            for(var b:program) {
                var seed=new LinkedHashMap<K,BigInteger>(); var change=new LinkedHashMap<K,BigInteger>();
                for(var s:b.word()) {
                    var r=byId.get(s.recipe()); var keys=new LinkedHashSet<K>(r.inputs().keySet()); keys.addAll(r.outputs().keySet());
                    BigInteger times=BigInteger.valueOf(s.count());
                    for(var k:keys) {
                        BigInteger in=BigInteger.valueOf(r.inputs().getOrDefault(k,0L));
                        BigInteger d=BigInteger.valueOf(r.outputs().getOrDefault(k,0L)).subtract(in);
                        BigInteger need=in.add(d.negate().max(BigInteger.ZERO).multiply(times.subtract(BigInteger.ONE)));
                        seed.merge(k,need.subtract(change.getOrDefault(k,BigInteger.ZERO)).max(BigInteger.ZERO),BigInteger::max);
                        change.merge(k,d.multiply(times),BigInteger::add);
                    }
                    counts.merge(s.recipe(),Math.multiplyExact(s.count(),b.repetitions()),Math::addExact);
                }
                var keys=new LinkedHashSet<K>(seed.keySet()); keys.addAll(change.keySet());
                BigInteger repeats=BigInteger.valueOf(b.repetitions());
                for(var k:keys) {
                    var d=change.getOrDefault(k,BigInteger.ZERO);
                    var need=seed.getOrDefault(k,BigInteger.ZERO).add(d.negate().max(BigInteger.ZERO).multiply(repeats.subtract(BigInteger.ONE)));
                    required.merge(k,need.subtract(delta.getOrDefault(k,BigInteger.ZERO)).max(BigInteger.ZERO),BigInteger::max);
                    delta.merge(k,d.multiply(repeats),BigInteger::add);
                }
            }
            var used=new LinkedHashMap<K,Long>();
            for(var e:required.entrySet()) {
                long n=e.getValue().longValueExact(); if(n>stock.getOrDefault(e.getKey(),0L))
                    throw new ProductionPlanningException(UNSUPPORTED,"内部证明不一致：起手库存被重复使用");
                if(n>0) used.put(e.getKey(),n);
            }
            if(delta.getOrDefault(target,BigInteger.ZERO).compareTo(BigInteger.valueOf(increment))<0)
                throw new ProductionPlanningException(UNSUPPORTED,"内部证明不一致：目标未达到净增量");
            return new Proof<>(Map.copyOf(used),Map.copyOf(counts),List.copyOf(program),Map.copyOf(branchCounts));
        } catch(ArithmeticException overflow) { throw new ProductionPlanningException(LIMIT,"强制画布数量超过整数容量；未提交部分程序"); }
    }
    private static <K> LinkedHashMap<Object,Long> objects(Map<K,Long> map) { var copy=new LinkedHashMap<Object,Long>(); map.forEach(copy::put); return copy; }
}
