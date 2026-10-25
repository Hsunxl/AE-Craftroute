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
     * A certified plan is CPU-agnostic: it names patterns, not a machine. When the CPU the
     * player happened to select reports {@code CPU_OFFLINE} - a stale entry in the confirm
     * screen, a core that is not loaded, or a grid still booting - submit the same plan to
     * AE2's own selection instead of failing the order. Nothing about the plan changes; only
     * which executor runs it. A plan that is not certified is never touched.
     */
    @WrapMethod(method = "submitJob")
    private ICraftingSubmitResult aecraftroute$fallbackWhenSelectedCpuIsOffline(ICraftingPlan plan,
            ICraftingRequester requestingMachine, ICraftingCPU target, boolean prioritizePower, IActionSource src,
            Operation<ICraftingSubmitResult> original) {
        ICraftingSubmitResult result = original.call(plan, requestingMachine, target, prioritizePower, src);
        if (!(plan instanceof CertifiedCraftingPlan)) return result;
        if (result != null && result.successful()) {
            com.aecraftroute.addon.CraftRouteAddon.LOGGER.info(
                    "[AE-Craftroute] certified plan attached: cpu={} plan={}", CpuProbe.describe(target), CpuProbe.describePlan(plan));
            return result;
        }
        if (target == null || result == null) return result;
        if (result.errorCode() != CraftingSubmitErrorCode.CPU_OFFLINE) {
            try {
                com.aecraftroute.addon.CraftRouteAddon.LOGGER.warn(
                        "[AE-Craftroute] certified plan refused: error={} detail={} target={} cpus={}",
                        result.errorCode(), result.errorDetail(), CpuProbe.describe(target), CpuProbe.describeGrid(grid));
            } catch (RuntimeException ignored) { }
            return result;
        }
        ICraftingSubmitResult retry;
        try {
            retry = original.call(plan, requestingMachine, null, prioritizePower, src);
        } catch (RuntimeException failed) {
            com.aecraftroute.addon.CraftRouteAddon.LOGGER.warn(
                    "[AE-Craftroute] certified plan could not be re-submitted after CPU_OFFLINE: {}", failed.toString());
            return result;
        }
        if (retry != null && retry.successful()) {
            com.aecraftroute.addon.CraftRouteAddon.LOGGER.warn(
                    "[AE-Craftroute] selected CPU reported CPU_OFFLINE; the certified plan was attached to another CPU. selected={} cpus={}",
                    CpuProbe.describe(target), CpuProbe.describeGrid(grid));
            return retry;
        }
        try {
            com.aecraftroute.addon.CraftRouteAddon.LOGGER.warn(
                    "[AE-Craftroute] no CPU accepted the certified plan: first={} selected={} retry={} detail={} cpus={}",
                    result.errorCode(), CpuProbe.describe(target),
                    retry == null ? "<null>" : retry.errorCode(), retry == null ? null : retry.errorDetail(),
                    CpuProbe.describeGrid(grid));
        } catch (RuntimeException ignored) { }
        return retry != null ? retry : result;
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
