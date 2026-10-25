package com.aecraftroute.addon.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.CraftingSubmitErrorCode;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import com.aecraftroute.addon.api.CraftRouteCraftingRegistry;
import com.aecraftroute.addon.planner.CertifiedCraftingPlan;
import com.aecraftroute.addon.telemetry.CpuProbe;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.Future;

/**
 * Mixin into CraftingService to replace recursive calculation with VM execution.
 */
@Mixin(value = CraftingService.class, remap = false, priority = 500)
public abstract class CraftingServiceMixin {
    @Shadow
    @Final
    private IGrid grid;
    /** True if the requester belongs to a third-party mod that has NOT opted in to AE-Craftroute. */
    @Unique
    private boolean isUnregisteredThirdPartyRequester(ICraftingSimulationRequester simRequester) {
        if (simRequester == null) return false;
        return CraftRouteCraftingRegistry.isUnregisteredThirdParty(simRequester.getClass().getName());
    }

    /**
     * A third-party CPU bridge may return CPU_OFFLINE even for automatic selection. The
     * previous target != null guard therefore skipped both recovery and diagnostics for
     * exactly the real-world failures. Retry every advertised CPU, including the selected
     * one: an explicit target is the only way to avoid an immediately repeating automatic
     * selection. Never retry a refusal that consumed or could consume inputs (for example
     * MISSING_INGREDIENT), and never submit twice after a success.
     */
    @WrapMethod(method = "submitJob")
    private ICraftingSubmitResult aecraftroute$retryOfflineCpu(ICraftingPlan plan,
            ICraftingRequester requestingMachine, ICraftingCPU target, boolean prioritizePower, IActionSource src,
            Operation<ICraftingSubmitResult> original) {
        ICraftingSubmitResult result = original.call(plan, requestingMachine, target, prioritizePower, src);
        if (result == null || result.successful() || result.errorCode() != CraftingSubmitErrorCode.CPU_OFFLINE) return result;
        var attempts = new java.util.ArrayList<String>();
        attempts.add("initial=" + result.errorCode() + " target=" + CpuProbe.describe(target));
        ICraftingSubmitResult last = result;
        try {
            for (ICraftingCPU candidate : grid.getCraftingService().getCpus()) {
                if (candidate == target) continue;
                // The certified executor is installed only on native and AdvancedAE CPUs.
                // Do not feed its program to Trinity/ECO executors with different contracts.
                boolean compatible = candidate instanceof CraftingCPUCluster
                        || candidate.getClass().getName().startsWith("net.pedroksl.advanced_ae.");
                if (plan instanceof CertifiedCraftingPlan && !compatible) {
                    attempts.add(CpuProbe.describe(candidate) + " -> skipped incompatible certified executor");
                    continue;
                }
                ICraftingSubmitResult retry = original.call(plan, requestingMachine, candidate, prioritizePower, src);
                if (retry != null && retry.successful()) {
                    com.aecraftroute.addon.CraftRouteAddon.LOGGER.warn(
                            "[AE-Craftroute] false CPU_OFFLINE recovered on another CPU: plan={} attempts={} attached={}",
                            CpuProbe.describePlan(plan), attempts, CpuProbe.describe(candidate));
                    return retry;
                }
                attempts.add(CpuProbe.describe(candidate) + " -> "
                        + (retry == null ? "<null>" : retry.errorCode() + " detail=" + retry.errorDetail()));
                if (retry != null) last = retry;
            }
        } catch (RuntimeException failed) {
            attempts.add("retry aborted=" + failed);
        }
        com.aecraftroute.addon.CraftRouteAddon.LOGGER.warn(
                "[AE-Craftroute] CPU_OFFLINE submission exhausted all advertised CPUs: plan={} attempts={} cpus={}",
                CpuProbe.describePlan(plan), attempts, CpuProbe.describeGrid(grid));
        return last;
    }
    
    /** Intercept only registered requesters; manual constraints never fall back. */
    @Inject(method = "beginCraftingCalculation", at = @At("HEAD"), cancellable = true)
    private void vmBeginCraftingCalculation(
            net.minecraft.world.level.Level level,
            ICraftingSimulationRequester simRequester,
            AEKey what,
            long amount,
            CalculationStrategy strategy,
            CallbackInfoReturnable<Future<ICraftingPlan>> cir) {
        
        long startTime = System.nanoTime();
        // 配置开关：proxy.enabled=false 时完全禁用 VM 代理，交给原生 AE2 递归计算
        if (!com.aecraftroute.addon.config.CraftRouteConfig.isProxyEnabled()) {
            if (!com.aecraftroute.addon.route.RouteSessions.get(grid, simRequester).rules().isEmpty()) {
                cir.setReturnValue(java.util.concurrent.CompletableFuture.failedFuture(
                        new com.aecraftroute.addon.route.RoutePlanningException("AE-Craftroute 已关闭；请清除手动约束或重新开启，未绕过约束")));
            }
            return;
        }
        
        // Manual requests have an isolated failure path: NEVER fall back to unrestricted AE2.
        if (!com.aecraftroute.addon.route.RouteSessions.get(grid, simRequester).rules().isEmpty()) {
            var future = com.aecraftroute.addon.telemetry.PlanningTelemetry.observe(com.aecraftroute.addon.api.CraftRouteCrafting.calculate(grid, simRequester, what, amount, strategy), startTime, what, amount, true);
            future.whenComplete((plan, error) -> {
                if (error != null && simRequester != null && !com.aecraftroute.addon.telemetry.PlanningTelemetry.outcome(error).equals("cancelled")) simRequester.getActionSource().player().ifPresent(player -> {
                    Throwable cause = error;
                    while (cause.getCause() != null) cause = cause.getCause();
                    String message = "[AE2 路线] " + cause.getMessage();
                    if (player instanceof net.minecraft.server.level.ServerPlayer sp)
                        sp.server.execute(() -> sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(message)));
                });
            });
            cir.setReturnValue(future);
            return;
        }
        
        try {
            // Do not cancel the injection: AE2 executes its original body exactly once.
            if (isUnregisteredThirdPartyRequester(simRequester)) return;
            
            // Delegate core computation to the public API.
            // Third-party mods can also call CraftRouteCrafting.calculate directly.
            var calculation=com.aecraftroute.addon.telemetry.PlanningTelemetry.observe(com.aecraftroute.addon.api.CraftRouteCrafting.calculate(grid,simRequester,what,amount,strategy),startTime,what,amount,false);
            var vmFuture=com.aecraftroute.addon.planner.PlanningFutures.observe(calculation,(plan,error)-> {
                if(error!=null && simRequester!=null && !com.aecraftroute.addon.telemetry.PlanningTelemetry.outcome(error).equals("cancelled")) simRequester.getActionSource().player().ifPresent(player->{
                    Throwable cause=error; while(cause.getCause()!=null) cause=cause.getCause();
                    String message="[AE2 合成] "+cause.getMessage();
                    if(player instanceof net.minecraft.server.level.ServerPlayer sp)
                        sp.server.execute(()->sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(message)));
                });
            });

            // Return future immediately — don't block server thread
            cir.cancel();
            cir.setReturnValue(vmFuture);
            
        } catch (Exception e) {
            cir.setReturnValue(com.aecraftroute.addon.telemetry.PlanningTelemetry.observe(
                    java.util.concurrent.CompletableFuture.<ICraftingPlan>failedFuture(e),startTime,what,amount,false));
        }
    }

}
