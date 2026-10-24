package com.aecraftroute.addon.mixin;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.execution.ElapsedTimeTracker;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.GenericStack;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=ExecutingCraftingJob.class,remap=false)
public interface GrowthJobAccessor {
 @Accessor("tasks") Map<IPatternDetails,?> aecraftroute$getTasks();
 @Mutable @Accessor("tasks") void aecraftroute$setTasks(Map<IPatternDetails,?> tasks);
 @Accessor("waitingFor") ListCraftingInventory aecraftroute$getWaitingFor();
 @Accessor("timeTracker") ElapsedTimeTracker aecraftroute$getTimeTracker();
 @Accessor("finalOutput") GenericStack aecraftroute$getFinalOutput();
    @Accessor("link") appeng.crafting.CraftingLink aecraftroute$getLink();
    @Accessor("remainingAmount") long aecraftroute$getRemainingAmount();
    @Accessor("remainingAmount") void aecraftroute$setRemainingAmount(long amount);
    @Accessor("suspended") boolean aecraftroute$getSuspended();
}
