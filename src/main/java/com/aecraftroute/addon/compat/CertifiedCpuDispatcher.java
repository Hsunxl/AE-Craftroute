package com.aecraftroute.addon.compat;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.crafting.CraftingSubmitErrorCode;
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
            ICraftingRequester requester, IActionSource source, boolean prioritizePower) {
        ICraftingSubmitResult initial=submitDirect(grid,plan,target,requester,source);
        if(initial.successful() || !canReroute(initial.errorCode()))return initial;
        // A selected CPU can be offline, busy, undersized or belong to another
        // execution protocol. Try only compatible auto-selectable CPUs, excluding
        // the one that already failed; never wrap a foreign plan in our protocol.
        com.aecraftroute.addon.CraftRouteAddon.LOGGER.warn(
                "[AE-Craftroute] selected CPU cannot currently run certified program: {} ({}); trying compatible CPU",
                com.aecraftroute.addon.telemetry.CpuProbe.describe(target),initial.errorCode());
        ICraftingSubmitResult alternative=selectAndSubmitExcept(grid,plan,requester,source,prioritizePower,target);
        if(alternative.successful() || isPlanFailure(alternative.errorCode()))return alternative;
        return initial.errorCode()==CraftingSubmitErrorCode.NO_CPU_FOUND ? alternative : initial;
    }

    static boolean canReroute(CraftingSubmitErrorCode code) {
        return code==CraftingSubmitErrorCode.CPU_OFFLINE || code==CraftingSubmitErrorCode.CPU_BUSY
                || code==CraftingSubmitErrorCode.CPU_TOO_SMALL || code==CraftingSubmitErrorCode.NO_CPU_FOUND
                || code==CraftingSubmitErrorCode.NO_SUITABLE_CPU_FOUND;
    }

    private static boolean isPlanFailure(CraftingSubmitErrorCode code) {
        return code==CraftingSubmitErrorCode.MISSING_INGREDIENT || code==CraftingSubmitErrorCode.INCOMPLETE_PLAN;
    }

    private static ICraftingSubmitResult submitDirect(IGrid grid, ICraftingPlan plan, ICraftingCPU target,
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
        return selectAndSubmitExcept(grid,plan,requester,source,prioritizePower,null);
    }

    private static ICraftingSubmitResult selectAndSubmitExcept(IGrid grid, ICraftingPlan plan,
            ICraftingRequester requester, IActionSource source, boolean prioritizePower, ICraftingCPU excluded) {
        ICraftingSubmitResult last = CraftingSubmitResult.NO_CPU_FOUND;
        for (ICraftingCPU candidate : grid.getCraftingService().getCpus()) {
            if(candidate==excluded)continue;
            if (candidate instanceof CraftingCPUCluster nativeCpu) {
                if (!nativeCpu.isActive() || nativeCpu.isBusy()
                        || nativeCpu.getAvailableStorage() < plan.bytes() || !nativeCpu.canBeAutoSelectedFor(source)) continue;
            } else {
                Object cluster = advancedCluster(candidate);
                if (cluster == null) continue; // Never hand a certified program to Trinity/ECO/other protocols.
                if (!canAutoSelect(cluster, source)) continue;
            }
            ICraftingSubmitResult result = submitDirect(grid, plan, candidate, requester, source);
            if (result.successful()) return result;
            last = result;
            // Offline/busy/too-small may differ between candidates. A concrete plan/input
            // refusal must remain visible and must not be hidden by trying more executors.
            if (isPlanFailure(result.errorCode())) return result;
        }
        return last;
    }

    /** Mirrors AE2's canBeAutoSelectedFor for a foreign cluster without a compile dependency. */
    static boolean canAutoSelect(Object cluster, IActionSource source) {
        try {
            return (boolean)cluster.getClass().getMethod("canBeAutoSelectedFor", IActionSource.class)
                    .invoke(cluster, source);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            // A missing/failed selector is not evidence that this CPU permits automatic
            // use. Respect its exclusion rather than silently redirecting onto it.
            if (com.aecraftroute.addon.config.CraftRouteConfig.isDebugLogging())
                com.aecraftroute.addon.CraftRouteAddon.LOGGER.warn(
                        "[AE-Craftroute] Cannot verify AdvancedAE CPU auto-selection; skipping {}",
                        cluster.getClass().getName(), absent);
            return false;
        }
    }

    private static Object advancedCluster(ICraftingCPU candidate) {
        if (candidate == null) return null;
        Class<?> type = candidate.getClass();
        while (type != null && !type.getName().equals(
                "net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU")) type = type.getSuperclass();
        if (type == null) return null;
        try {
            Field field = type.getDeclaredField("cluster");
            field.setAccessible(true);
            return field.get(candidate);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }

    static ICraftingSubmitResult submitAdvanced(Object cluster, IGrid grid, ICraftingPlan plan,
            ICraftingRequester requester, IActionSource source) {
        try {
            Method submit = cluster.getClass().getMethod("submitJob", IGrid.class, ICraftingPlan.class,
                    IActionSource.class, ICraftingRequester.class);
            ICraftingSubmitResult result=(ICraftingSubmitResult)submit.invoke(cluster, grid, plan, source, requester);
            // Null is a broken contract, but invocation may already have queued work.
            // Fail explicitly instead of treating it as a reroutable availability error.
            if(result==null)throw new IllegalStateException("AdvancedAE certified cluster returned no submission result");
            return result;
        } catch (NoSuchMethodException | IllegalAccessException | SecurityException unavailable) {
            // No invocation occurred: a different compatible CPU may safely run it.
            com.aecraftroute.addon.CraftRouteAddon.LOGGER.warn(
                    "[AE-Craftroute] AdvancedAE certified submit API unavailable on {}",
                    cluster.getClass().getName(),unavailable);
            return CraftingSubmitResult.simpleError(CraftingSubmitErrorCode.NO_SUITABLE_CPU_FOUND);
        } catch (java.lang.reflect.InvocationTargetException invoked) {
            // The method may already have started a job. Never retry on another CPU.
            throw new IllegalStateException("AdvancedAE certified cluster submission failed after invocation", invoked.getCause());
        }
    }
}
