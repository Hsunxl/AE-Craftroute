package com.aecraftroute.addon.mixin;
import appeng.crafting.execution.ElapsedTimeTracker;
import appeng.api.stacks.AEKeyType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(value=ElapsedTimeTracker.class,remap=false)
public interface GrowthTimeAccessor {
 @Invoker("decrementItems") void aecraftroute$decrementItems(long amount,AEKeyType type);
}
