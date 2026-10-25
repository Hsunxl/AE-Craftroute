package com.aecraftroute.addon.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.crafting.CraftingSubmitErrorCode;
import appeng.api.networking.security.IActionSource;
import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPUCluster;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** AdvancedAE's advertised remaining-capacity CPU and its selection cache can disagree.
 * Never change the offline verdict on a guess: report the actual cluster/grid/node identity
 * at the exact refusal point so the next action follows evidence, not another retry loop. */
@Pseudo
@Mixin(value = AdvCraftingCPUCluster.class, remap = false)
public abstract class AdvancedCraftingClusterMixin {
    @Inject(method = "submitJob", at = @At("RETURN"), cancellable = true)
    private void aecraftroute$diagnoseFalseOffline(IGrid grid, ICraftingPlan plan, IActionSource src,
            ICraftingRequester requestingMachine, CallbackInfoReturnable<ICraftingSubmitResult> cir) {
        ICraftingSubmitResult result = cir.getReturnValue();
        if (result == null || result.successful() || result.errorCode() != CraftingSubmitErrorCode.CPU_OFFLINE) return;
        AdvCraftingCPUCluster self = (AdvCraftingCPUCluster)(Object)this;
        IGrid clusterGrid = self.getGrid();
        com.aecraftroute.addon.CraftRouteAddon.LOGGER.warn(
                "[AE-Craftroute] AdvancedAE cluster rejected an active advertised CPU: cluster={} clusterGrid={} requestGrid={} sameGrid={} plan={}",
                com.aecraftroute.addon.telemetry.CpuProbe.describe(self),
                com.aecraftroute.addon.telemetry.CpuProbe.identity(clusterGrid),
                com.aecraftroute.addon.telemetry.CpuProbe.identity(grid),
                clusterGrid == grid,
                com.aecraftroute.addon.telemetry.CpuProbe.describePlan(plan));
    }
}
