package com.aecraftroute.addon.planner;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import java.util.*;
import java.util.function.Function;

/** Exact physical transitions shared by unrestricted and path-locked searches.
 * Incomplete means candidate generation was bounded, never a proof of missing stock. */
public final class PatternVariants {
    public record Enumeration(List<ProductionPlanner.Recipe<AEKey,PatternVariant>> recipes,boolean complete) {
        public Enumeration { recipes=List.copyOf(recipes); }
    }
    public static Enumeration enumerate(IPatternDetails pattern,Function<AEKey,Long> stock) {
        return enumerate(pattern,stock,Long.MAX_VALUE);
    }
    /** Absolute System.nanoTime deadline, shared with snapshot and search work. */
    public static Enumeration enumerate(IPatternDetails pattern,Function<AEKey,Long> stock,long deadlineNanos) {
        var slots=new ArrayList<List<PatternVariant.Slot>>(); boolean complete=true;
        try {
            checkBudget(deadlineNanos);
            com.aecraftroute.addon.compat.ScaledPatternCompat.validate(pattern);
            for(var input:pattern.getInputs()) {
                checkBudget(deadlineNanos);
                try { FiniteToolTransitions.validate(input); }
                catch(IllegalArgumentException invalidTool) { return new Enumeration(List.of(),false); }
                var options=input.getPossibleInputs();
                if(options==null || options.length==0 || input.getMultiplier()<=0) { return new Enumeration(List.of(),false); }
                var distinct=new LinkedHashSet<GenericStack>();
                for(var option:options) if(option!=null && option.what()!=null && option.amount()>0) {
                    checkBudget(deadlineNanos);
                    distinct.add(option);
                }
                if(distinct.isEmpty()) { return new Enumeration(List.of(),false); }
                // Whole-slot variants are exact executable transitions. A mixed
                // multiplier allocation is not exhaustive: failure stays UNKNOWN.
                var allocations=new ArrayList<PatternVariant.Slot>();
                for(var option:distinct) allocations.add(new PatternVariant.Slot(List.of(new PatternVariant.Portion(option,input.getMultiplier()))));
                if(distinct.size()>1 && input.getMultiplier()>1) {
                    if(input.getMultiplier()>64 || distinct.size()>16) {
                        complete=false;
                        addStockAllocations(List.copyOf(distinct),input.getMultiplier(),stock,allocations,deadlineNanos);
                    }
                    else {
                        var mixed=new ArrayList<PatternVariant.Slot>();
                        allocate(List.copyOf(distinct),0,input.getMultiplier(),new ArrayList<>(),mixed,deadlineNanos);
                        if(mixed.size()>4096) {
                            complete=false;
                            addStockAllocations(List.copyOf(distinct),input.getMultiplier(),stock,allocations,deadlineNanos);
                        }
                        else allocations=mixed;
                    }
                }
                slots.add(List.copyOf(allocations));
            }

            var recipes=new ArrayList<ProductionPlanner.Recipe<AEKey,PatternVariant>>();
            long combinations=1;
            for(var slot:slots) { combinations=Math.multiplyExact(combinations,slot.size()); if(combinations>4096) break; }
            if(combinations>4096) { complete=false; addPatternCandidates(pattern,slots,stock,recipes,deadlineNanos); }
            else enumerate(pattern,slots,0,new ArrayList<>(),recipes,deadlineNanos);
            if(!complete) {
                var aggregate=FuzzyFamilyAllocator.allocate(pattern,stock,deadlineNanos);
                if(aggregate.isPresent()) {
                    var exact=aggregate.get().recipe();
                    if(!recipes.contains(exact)) recipes.addFirst(exact);
                }
            }
            return new Enumeration(recipes,complete);
        } catch(ArithmeticException | IllegalArgumentException invalid) { return new Enumeration(List.of(),false); }
    }
    private static void addStockAllocations(List<GenericStack> options,long multiplier,
            Function<AEKey,Long> stock,List<PatternVariant.Slot> allocations,long deadlineNanos) {
        var ordered=new ArrayList<>(options);
        ordered.sort(Comparator.comparingLong((GenericStack s)->Math.max(0,stock.apply(s.what()))/s.amount()).reversed());
        // Bounded rotations include several stocked families without enumerating
        // the stars-and-bars allocation space. Every candidate remains exact.
        for(int rotation=0;rotation<Math.min(16,ordered.size());rotation++) {
            checkBudget(deadlineNanos);
            var portions=new ArrayList<PatternVariant.Portion>(); long left=multiplier;
            for(int j=0;j<ordered.size() && left>0;j++) {
                var option=ordered.get((j+rotation)%ordered.size());
                long count=Math.min(left,Math.max(0,stock.apply(option.what()))/option.amount());
                if(count>0) { portions.add(new PatternVariant.Portion(option,count)); left-=count; }
            }
            if(left==0) { var slot=new PatternVariant.Slot(portions); if(!allocations.contains(slot)) allocations.add(slot); }
            // Inventory alone is not the complete supply: upstream producers
            // may furnish several variants. Balanced candidates retain that
            // route without materialising every integer composition.
            portions=new ArrayList<>(); left=multiplier;
            for(int j=0;j<ordered.size() && left>0;j++) {
                long count=left/(ordered.size()-j)+(left%(ordered.size()-j)==0?0:1);
                portions.add(new PatternVariant.Portion(ordered.get((j+rotation)%ordered.size()),count));
                left-=count;
            }
            var balanced=new PatternVariant.Slot(portions); if(!allocations.contains(balanced)) allocations.add(balanced);
        }
    }
    private static void addPatternCandidates(IPatternDetails pattern,List<List<PatternVariant.Slot>> slots,
            Function<AEKey,Long> stock,List<ProductionPlanner.Recipe<AEKey,PatternVariant>> recipes,long deadlineNanos) {
        for(int rotation=0;rotation<32 && recipes.size()<8192;rotation++) {
            checkBudget(deadlineNanos);
            var remaining=new HashMap<AEKey,Long>(); var chosen=new ArrayList<PatternVariant.Slot>();
            for(int at=0;at<slots.size();at++) {
                var input=pattern.getInputs()[at];
                var options=new ArrayList<>(Arrays.asList(input.getPossibleInputs()));
                for(var option:options) remaining.computeIfAbsent(option.what(),k->Math.max(0,stock.apply(k)));
                options.sort(Comparator.comparingLong((GenericStack s)->remaining.get(s.what())/s.amount()).reversed());
                var allocation=new ArrayList<PatternVariant.Portion>(); long left=input.getMultiplier();
                for(int j=0;j<options.size() && left>0;j++) {
                    var option=options.get((j+rotation)%options.size());
                    long count=Math.min(left,remaining.get(option.what())/option.amount());
                    if(count>0) {
                        allocation.add(new PatternVariant.Portion(option,count)); left-=count;
                        remaining.merge(option.what(),-Math.multiplyExact(count,option.amount()),Math::addExact);
                    }
                }
                if(left>0) {
                    // A producer may supply this variant later. The certified
                    // search, not this candidate heuristic, proves reachability.
                    var fallback=options.get((rotation+at)%options.size());
                    allocation.add(new PatternVariant.Portion(fallback,left));
                }
                chosen.add(new PatternVariant.Slot(allocation));
            }
            var candidate=new PatternVariant(pattern,chosen).recipe();
            if(!recipes.contains(candidate)) recipes.add(candidate);
        }
    }
    private static void enumerate(IPatternDetails p,List<List<PatternVariant.Slot>> slots,int at,List<PatternVariant.Slot> chosen,
            List<ProductionPlanner.Recipe<AEKey,PatternVariant>> result,long deadlineNanos) {
        checkBudget(deadlineNanos);
        if(at==slots.size()) { result.add(new PatternVariant(p,chosen).recipe()); return; }
        for(var stack:slots.get(at)) { chosen.add(stack); enumerate(p,slots,at+1,chosen,result,deadlineNanos); chosen.removeLast(); }
    }
    private static void allocate(List<GenericStack> options,int at,long remaining,List<PatternVariant.Portion> chosen,
            List<PatternVariant.Slot> result,long deadlineNanos) {
        checkBudget(deadlineNanos);
        if(result.size()>4096) return;
        if(at==options.size()) { if(remaining==0) result.add(new PatternVariant.Slot(chosen)); return; }
        if(at==options.size()-1) {
            if(remaining>0) chosen.add(new PatternVariant.Portion(options.get(at),remaining));
            result.add(new PatternVariant.Slot(chosen));
            if(remaining>0) chosen.removeLast();
            return;
        }
        for(long count=0;count<=remaining && result.size()<=4096;count++) {
            if(count>0) chosen.add(new PatternVariant.Portion(options.get(at),count));
            allocate(options,at+1,remaining-count,chosen,result,deadlineNanos);
            if(count>0) chosen.removeLast();
        }
    }
    private static void checkBudget(long deadlineNanos) {
        if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Pattern enumeration cancelled");
        if(deadlineNanos!=Long.MAX_VALUE && System.nanoTime()-deadlineNanos>=0)
            throw new ProductionPlanningException(ProductionPlanningException.Reason.LIMIT,"替代输入枚举超过计算预算；尚未判定可行性，不是缺料");
    }
    private PatternVariants() {}
}
