package com.aecraftroute.addon.compat;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Direct submission for the certified execution protocol. Thunderbolt 2.0.1's service HEAD
 * rejects every non-native ICraftingPlan, so routing through CraftingService.submitJob can
 * never reach these executors. Call the public cluster contract instead of fighting that
 * HEAD with another mixin. Only the two executor families with ProductionCpu bridges qualify. */
public final class CertifiedCpuDispatcher {
    private CertifiedCpuDispatcher() {}

    public static ICraftingSubmitResult submit(IGrid grid, ICraftingPlan plan, ICraftingCPU target,
            ICraftingRequester requester, IActionSource source) {
        if (target instanceof CraftingCPUCluster nativeCpu) {
            if (!nativeCpu.isActive()) return CraftingSubmitResult.CPU_OFFLINE;
            if (nativeCpu.isBusy()) return CraftingSubmitResult.CPU_BUSY;
            if (nativeCpu.getAvailableStorage() < plan.bytes()) return CraftingSubmitResult.CPU_TOO_SMALL;
            return nativeCpu.submitJob(grid, plan, source, requester);
        }
        Object cluster = advancedCluster(target);
        if (cluster != null) return submitAdvanced(cluster, grid, plan, requester, source);
        return CraftingSubmitResult.NO_CPU_FOUND;
    }

    public static ICraftingSubmitResult selectAndSubmit(IGrid grid, ICraftingPlan plan,
            ICraftingRequester requester, IActionSource source, boolean prioritizePower) {
        ICraftingSubmitResult last = CraftingSubmitResult.NO_CPU_FOUND;
        for (ICraftingCPU candidate : grid.getCraftingService().getCpus()) {
            if (candidate instanceof CraftingCPUCluster nativeCpu) {
                if (!nativeCpu.isActive() || nativeCpu.isBusy()
                        || nativeCpu.getAvailableStorage() < plan.bytes() || !nativeCpu.canBeAutoSelectedFor(source)) continue;
            } else {
                Object cluster = advancedCluster(candidate);
                if (cluster == null) continue; // Never hand a certified program to Trinity/ECO/other protocols.
                if (!canAutoSelect(cluster, source)) continue;
            }
            ICraftingSubmitResult result = submit(grid, plan, candidate, requester, source);
            if (result.successful()) return result;
            last = result;
            // Offline/busy/too-small may differ between candidates. A concrete plan/input
            // refusal must remain visible and must not be hidden by trying more executors.
            if (result.errorCode() == appeng.api.networking.crafting.CraftingSubmitErrorCode.MISSING_INGREDIENT
                    || result.errorCode() == appeng.api.networking.crafting.CraftingSubmitErrorCode.INCOMPLETE_PLAN) return result;
        }
        return last;
    }

    /** Mirrors AE2's canBeAutoSelectedFor for a foreign cluster without a compile dependency. */
    private static boolean canAutoSelect(Object cluster, IActionSource source) {
        try {
            return (boolean)cluster.getClass().getMethod("canBeAutoSelectedFor", IActionSource.class)
                    .invoke(cluster, source);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return true; // Unknown selector: do not silently drop a compatible executor.
        }
    }

    private static Object advancedCluster(ICraftingCPU candidate) {
        if (candidate == null || !candidate.getClass().getName().equals(
                "net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU")) return null;
        try {
            Field field = candidate.getClass().getDeclaredField("cluster");
            field.setAccessible(true);
            return field.get(candidate);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }

    private static ICraftingSubmitResult submitAdvanced(Object cluster, IGrid grid, ICraftingPlan plan,
            ICraftingRequester requester, IActionSource source) {
        try {
            Method submit = cluster.getClass().getMethod("submitJob", IGrid.class, ICraftingPlan.class,
                    IActionSource.class, ICraftingRequester.class);
            return (ICraftingSubmitResult)submit.invoke(cluster, grid, plan, source, requester);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException("AdvancedAE certified cluster submission failed", failure);
        }
    }
}
