package com.aecraftroute.addon.selftest;

import appeng.api.crafting.IPatternDetails;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.aecraftroute.addon.mixin.GrowthJobAccessor;
import com.aecraftroute.addon.mixin.GrowthTaskAccessor;
import java.lang.reflect.Field;
import java.util.*;

/** Development-only observation of native CPU task decrements. It never alters
 * a job, manufactures provider returns, or advances the certified cursor. */
final class CpuTaskObserver {
    private final CraftingCPUCluster cluster;
    private final Map<IPatternDetails,Long> initial,executed=new LinkedHashMap<>();
    CpuTaskObserver(CraftingCPUCluster cluster,Map<IPatternDetails,Long> initial) {
        this.cluster=cluster; this.initial=Map.copyOf(initial);
    }
    void sample() {
        var logic=find(cluster,CraftingCpuLogic.class);
        var job=find(logic,ExecutingCraftingJob.class);
        // A final delivery callback samples before finishJob clears the native
        // job, so a missing job is not treated as evidence of dispatch.
        if(job==null) return;
        var tasks=((GrowthJobAccessor)(Object)job).aecraftroute$getTasks();
        for(var entry:initial.entrySet()) {
            var progress=tasks.get(entry.getKey());
            long remaining=progress==null?0:((GrowthTaskAccessor)progress).aecraftroute$getValue();
            if(remaining<0 || remaining>entry.getValue()) throw new IllegalStateException("Native task count outside planned bounds");
            executed.merge(entry.getKey(),entry.getValue()-remaining,Math::max);
        }
    }
    long executed(IPatternDetails pattern) { return executed.getOrDefault(pattern,0L); }
    private static <T> T find(Object host,Class<T> type) {
        if(host==null) return null;
        for(Class<?> at=host.getClass();at!=null;at=at.getSuperclass()) for(Field field:at.getDeclaredFields()) {
            if(!type.isAssignableFrom(field.getType())) continue;
            try { field.setAccessible(true); return type.cast(field.get(host)); }
            catch(ReflectiveOperationException failure) { throw new IllegalStateException("Cannot observe native CPU task state",failure); }
        }
        throw new IllegalStateException("Native CPU observation field unavailable: "+type.getName());
    }
}
