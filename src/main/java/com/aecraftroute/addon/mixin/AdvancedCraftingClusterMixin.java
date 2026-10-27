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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Diagnose an AdvancedAE offline refusal without changing its executor or flooding logs. */
@Pseudo
@Mixin(value = AdvCraftingCPUCluster.class, remap = false)
public abstract class AdvancedCraftingClusterMixin {
    @Unique private static final com.aecraftroute.addon.telemetry.CpuOfflineLogThrottle AECR_OFFLINE_LOG_THROTTLE =
            new com.aecraftroute.addon.telemetry.CpuOfflineLogThrottle(30_000_000_000L);

    @Inject(method = "submitJob", at = @At("RETURN"))
    private void aecraftroute$diagnoseFalseOffline(IGrid grid, ICraftingPlan plan, IActionSource src,
            ICraftingRequester requestingMachine, CallbackInfoReturnable<ICraftingSubmitResult> cir) {
        ICraftingSubmitResult result = cir.getReturnValue();
        if (result == null || result.successful() || result.errorCode() != CraftingSubmitErrorCode.CPU_OFFLINE) return;
        // submitJob can be retried against several CPUs per request. Keep one
        // diagnostic per interval; the original AdvancedAE verdict is untouched.
        if (!AECR_OFFLINE_LOG_THROTTLE.shouldLog(System.nanoTime())) return;
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
