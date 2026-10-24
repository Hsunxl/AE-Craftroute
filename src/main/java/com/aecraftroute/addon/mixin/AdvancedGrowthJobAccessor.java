package com.aecraftroute.addon.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.GenericStack;
import appeng.crafting.inv.ListCraftingInventory;
import net.pedroksl.advanced_ae.common.logic.ElapsedTimeTracker;
import java.util.Map;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets="net.pedroksl.advanced_ae.common.logic.ExecutingCraftingJob", remap=false)
public interface AdvancedGrowthJobAccessor {
    @Accessor("tasks") Map<IPatternDetails, ?> aecraftroute$getTasks();
    @Mutable @Accessor("tasks") void aecraftroute$setTasks(Map<IPatternDetails, ?> tasks);
    @Accessor("waitingFor") ListCraftingInventory aecraftroute$getWaitingFor();
    @Accessor("timeTracker") ElapsedTimeTracker aecraftroute$getTimeTracker();
    @Accessor("finalOutput") GenericStack aecraftroute$getFinalOutput();
    @Accessor("link") appeng.crafting.CraftingLink aecraftroute$getLink();
    @Accessor("remainingAmount") long aecraftroute$getRemainingAmount();
    @Accessor("remainingAmount") void aecraftroute$setRemainingAmount(long amount);
    @Accessor("suspended") boolean aecraftroute$getSuspended();
}
