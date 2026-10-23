package com.ae2vm.addon.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets="net.pedroksl.advanced_ae.common.logic.ExecutingCraftingJob$TaskProgress", remap=false)
public interface AdvancedGrowthTaskAccessor {
    @Accessor("value") long ae2vm$getValue();
}
