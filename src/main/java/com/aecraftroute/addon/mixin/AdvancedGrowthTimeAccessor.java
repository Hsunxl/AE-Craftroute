package com.aecraftroute.addon.mixin;

import appeng.api.stacks.AEKeyType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(targets="net.pedroksl.advanced_ae.common.logic.ElapsedTimeTracker", remap=false)
public interface AdvancedGrowthTimeAccessor {
    @Invoker("decrementItems") void aecraftroute$decrementItems(long amount, AEKeyType type);
}
