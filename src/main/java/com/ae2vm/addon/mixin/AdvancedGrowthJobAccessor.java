package com.ae2vm.addon.mixin;

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
    @Accessor("tasks") Map<IPatternDetails, ?> ae2vm$getTasks();
    @Mutable @Accessor("tasks") void ae2vm$setTasks(Map<IPatternDetails, ?> tasks);
    @Accessor("waitingFor") ListCraftingInventory ae2vm$getWaitingFor();
    @Accessor("timeTracker") ElapsedTimeTracker ae2vm$getTimeTracker();
    @Accessor("finalOutput") GenericStack ae2vm$getFinalOutput();
    @Accessor("link") appeng.crafting.CraftingLink ae2vm$getLink();
    @Accessor("remainingAmount") long ae2vm$getRemainingAmount();
    @Accessor("remainingAmount") void ae2vm$setRemainingAmount(long amount);
    @Accessor("suspended") boolean ae2vm$getSuspended();
}
