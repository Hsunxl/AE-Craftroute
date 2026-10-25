package com.aecraftroute.addon.route;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEKey;
import net.minecraft.core.HolderLookup;
import java.util.*;
import com.aecraftroute.addon.planner.ProductionPlanningException;
import static com.aecraftroute.addon.planner.ProductionPlanningException.Reason.*;

/** Server-thread capture, shared by previews and real CPU requests. No worker reads a grid. */
public final class RouteSnapshot {
    private static final int KEY_BUDGET=512, PATTERN_BUDGET=2048;
    public record Result(Map<AEKey,List<IPatternDetails>> patterns,Map<AEKey,Long> stock,
                         Map<IPatternDetails,String> identities) {
        public Result { var copy=new LinkedHashMap<AEKey,List<IPatternDetails>>(); patterns.forEach((k,v)->copy.put(k,List.copyOf(v))); patterns=Map.copyOf(copy); stock=Map.copyOf(stock); identities=Map.copyOf(identities); }
        public Result(Map<AEKey,List<IPatternDetails>> patterns,Map<AEKey,Long> stock) { this(patterns,stock,Map.of()); }
    }
    /** One advance operation handles at most one key/pattern or one inventory lookup.
     * External AE API calls cannot be preempted; elapsed time is charged, never hidden. */
    public static final class Capture {
        private final IGrid grid; private final RoutePolicy<AEKey> policy; private final HolderLookup.Provider registries;
        private final long deadline;
        private final ArrayDeque<AEKey> queue=new ArrayDeque<>();
        private final LinkedHashMap<AEKey,List<IPatternDetails>> patterns=new LinkedHashMap<>();
        private final LinkedHashMap<AEKey,List<IPatternDetails>> observed=new LinkedHashMap<>();
        private final LinkedHashMap<IPatternDetails,String> identities=new LinkedHashMap<>();
        private final Set<IPatternDetails> visited=new HashSet<>();
        private AEKey current; private Iterator<IPatternDetails> candidates; private List<IPatternDetails> allowed;
        private Iterator<AEKey> stockKeys; private appeng.api.stacks.KeyCounter inventory;
        private final Map<AEKey,Long> stock=new LinkedHashMap<>();
        private Result result;
        public Capture(IGrid grid,RoutePolicy<AEKey> policy,HolderLookup.Provider registries,AEKey target,long deadline) {
            this.grid=grid;this.policy=policy;this.registries=registries;this.deadline=deadline;queue.add(Objects.requireNonNull(target));
        }
        public boolean advance() {
            RouteCanvasPlanner.check(deadline);
            if(result!=null)return true;
            if(stockKeys!=null) {
                if(stockKeys.hasNext()) {var key=stockKeys.next();stock.put(key,Math.max(0,inventory.get(key)));return false;}
                result=new Result(patterns,stock,identities); RouteCanvasPlanner.check(deadline);return true;
            }
            if(candidates!=null) {
                if(candidates.hasNext()) {
                    var pattern=candidates.next();
                    try {com.aecraftroute.addon.compat.ScaledPatternCompat.validate(pattern);}
                    catch(IllegalArgumentException invalid) {throw new ProductionPlanningException(UNSUPPORTED,"样板包装无法安全解析："+invalid.getMessage());}
                    boolean permitted=true;
                    if(!policy.rules().isEmpty()) for(var out:pattern.getOutputs()) {
                        if(!policy.filter(out.what(),List.of(pattern),p->identity(p,true)).contains(pattern)) permitted=false;
                    }
                    if(permitted) {
                        allowed.add(pattern);
                        if(policy.canvas()!=null) identity(pattern,false);
                        if(visited.add(pattern)) {
                            if(visited.size()>PATTERN_BUDGET)throw new ProductionPlanningException(LIMIT,"候选样板超过快照预算；不是缺料");
                            for(var input:pattern.getInputs()) for(var stack:input.getPossibleInputs()) {
                                if(stack==null)continue;queue.add(stack.what());var remaining=input.getRemainingKey(stack.what());if(remaining!=null)queue.add(remaining);
                            }
                            for(var out:pattern.getOutputs()) queue.add(out.what());
                        }
                    }
                    RouteCanvasPlanner.check(deadline);return false;
                }
                patterns.put(current,List.copyOf(allowed));candidates=null;return false;
            }
            while(!queue.isEmpty()) {
                var key=queue.removeFirst();if(patterns.containsKey(key))continue;
                if(patterns.size()>=KEY_BUDGET)throw new ProductionPlanningException(LIMIT,"配方图超过快照预算；不是缺料");
                current=key;allowed=new ArrayList<>();var raw=List.copyOf(grid.getCraftingService().getCraftingFor(key));observed.put(key,raw);candidates=raw.iterator();return false;
            }
            // Always take authoritative live stock once. Native provider traversal cannot be
            // preempted; charge its elapsed time to the same end-to-end deadline.
            inventory=grid.getStorageService().getInventory().getAvailableStacks(); stockKeys=patterns.keySet().iterator();
            RouteCanvasPlanner.check(deadline);return false;
        }
        private String identity(IPatternDetails p,boolean strict) {
            var existing=identities.get(p);if(existing!=null)return existing;
            try {var id=RouteIdentity.pattern(p,registries);identities.put(p,id);return id;}
            catch(RoutePlanningException unsupported) {
                if(strict)throw unsupported;
                return "";
            }
        }
        public Result result() { if(result==null)throw new IllegalStateException("Capture incomplete");return result; }
        /** Revalidation runs on server ticks before an apply commit. */
        public Iterator<Map.Entry<AEKey,List<IPatternDetails>>> validation() {return observed.entrySet().iterator();}
        public appeng.api.stacks.KeyCounter liveInventory() {return grid.getStorageService().getInventory().getAvailableStacks();}
        public boolean unchanged(Map.Entry<AEKey,List<IPatternDetails>> entry) {
            var current=List.copyOf(grid.getCraftingService().getCraftingFor(entry.getKey()));
            for(var pattern:current) {
                try {com.aecraftroute.addon.compat.ScaledPatternCompat.validate(pattern);}
                catch(IllegalArgumentException invalid) {throw new ProductionPlanningException(UNSUPPORTED,"网络变更后的样板包装无法安全解析");}
            }
            return entry.getValue().equals(current);
        }
    }
    public static Result build(IGrid grid,RoutePolicy<AEKey> policy,HolderLookup.Provider registries,AEKey target) {
        return build(grid,policy,registries,target,Long.MAX_VALUE);
    }
    public static Result build(IGrid grid,RoutePolicy<AEKey> policy,HolderLookup.Provider registries,AEKey target,long deadline) {
        var capture=new Capture(grid,policy,registries,target,deadline);while(!capture.advance()){}return capture.result();
    }
    private RouteSnapshot() {}
}
