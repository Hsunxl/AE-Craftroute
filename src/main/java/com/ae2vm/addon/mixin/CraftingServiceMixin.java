package com.ae2vm.addon.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.me.service.CraftingService;
import com.ae2vm.addon.AE2VMAddon;
import com.ae2vm.addon.api.AE2VMCraftingRegistry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.concurrent.Future;

/**
 * Mixin into CraftingService to replace recursive calculation with VM execution.
 */
@Mixin(value = CraftingService.class, remap = false)
public abstract class CraftingServiceMixin {
    @Shadow
    @Final
    private IGrid grid;
    
    @Shadow
    public abstract Collection<IPatternDetails> getCraftingFor(AEKey what);
    
    private static long requestCounter = 0;
    
    /** Set while a failed VM request is retried through the original (native) crafting path. */
    private static final ThreadLocal<Boolean> VM_FALLBACK = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** True if the requester belongs to a third-party mod that has NOT opted in to AE2 VM. */
    private boolean isUnregisteredThirdPartyRequester(ICraftingSimulationRequester simRequester) {
        if (simRequester == null) return false;
        return AE2VMCraftingRegistry.isUnregisteredThirdParty(simRequester.getClass().getName());
    }
    
    /**
     * 顶层 mixin：希望先于 ECO（order=500）等第三方 mixin 执行。
     * 注意：Forge 47（1.20.1）运行时捆绑 Mixin 0.8.5，其 @Inject 不支持 order 属性，
     * 因此这里不能写 order=100（编译期会报错，运行期也会被忽略）。
     * 若需确保本注入先于第三方 mixin，应通过 mixin 配置的 priority 来控制（见 ae2vm.mixins.json）。
     */
    @Inject(method = "beginCraftingCalculation", at = @At("HEAD"), cancellable = true)
    private void vmBeginCraftingCalculation(
            net.minecraft.world.level.Level level,
            ICraftingSimulationRequester simRequester,
            AEKey what,
            long amount,
            CalculationStrategy strategy,
            CallbackInfoReturnable<Future<ICraftingPlan>> cir) {
        
        // 配置开关：proxy.enabled=false 时完全禁用 VM 代理，交给原生 AE2 递归计算
        if (!com.ae2vm.addon.config.AE2VMConfig.isProxyEnabled()) {
            if (!com.ae2vm.addon.route.RouteSessions.get(grid, simRequester).rules().isEmpty()) {
                cir.setReturnValue(java.util.concurrent.CompletableFuture.failedFuture(
                        new com.ae2vm.addon.route.RoutePlanningException("AE2-VM 已关闭；请清除手动约束或重新开启，未绕过约束")));
            }
            return;
        }
        
        // Native fallback in progress (VM failed) → let the original method run untouched.
        if (VM_FALLBACK.get()) {
            return;
        }

        // Manual requests have an isolated failure path: NEVER fall back to unrestricted AE2.
        if (!com.ae2vm.addon.route.RouteSessions.get(grid, simRequester).rules().isEmpty()) {
            var future = com.ae2vm.addon.api.AE2VMCrafting.calculate(grid, simRequester, what, amount, strategy);
            future.whenComplete((plan, error) -> {
                if (error != null) simRequester.getActionSource().player().ifPresent(player -> {
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
        
        long reqId = ++requestCounter;
        long startTime = System.nanoTime();
        
        // Request/Target logging disabled (v1.8.20) — keep only total calc time.
        // AE2VMAddon.LOGGER.info("[AE2-VM] ====== VM Request #{} ======", reqId);
        // AE2VMAddon.LOGGER.info("[AE2-VM] Target: {} x {}, strategy={}", amount, what, strategy);
        
        // If already cancelled, don't override
        if (cir.isCancelled()) {
            // AE2VMAddon.LOGGER.info("[AE2-VM] Already cancelled, VM skipping");
            return;
        }
        
        try {
            // Third-party requester that has NOT registered → do not take over.
            // Directly set the original return value: call the original crafting
            // path (VM_FALLBACK prevents recursion) and return its future.
            if (isUnregisteredThirdPartyRequester(simRequester)) {
                // AE2VMAddon.LOGGER.info("[AE2-VM] Third-party requester {} (not registered) → native crafting",
                //     simRequester.getClass().getName());
                VM_FALLBACK.set(Boolean.TRUE);
                try {
                    cir.setReturnValue(((CraftingService) (Object) this).beginCraftingCalculation(
                        level, simRequester, what, amount, strategy));
                    cir.cancel();
                } finally {
                    VM_FALLBACK.remove();
                }
                return;
            }
            
            Collection<IPatternDetails> patterns = getCraftingFor(what);
            // Even absent producers use the same explicit net-production failure contract.
            
            // long setupTime = (System.nanoTime() - startTime) / 1_000_000;
            // AE2VMAddon.LOGGER.info("[AE2-VM] VM try: {}x{} setup={}ms", amount, what, setupTime);
            
            // Delegate core computation to the public API.
            // Third-party mods can also call AE2VMCrafting.calculate directly.
            var calculation=com.ae2vm.addon.api.AE2VMCrafting.calculate(grid,simRequester,what,amount,strategy);
            var vmFuture=com.ae2vm.addon.planner.PlanningFutures.observe(calculation,(plan,error)-> {
                if(error!=null && simRequester!=null) simRequester.getActionSource().player().ifPresent(player->{
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
            cir.setReturnValue(java.util.concurrent.CompletableFuture.failedFuture(e));
        }
    }

    /**
     * (v1.12.x GTL) Only REAL VM failures may fall back to the ORIGINAL AE2/GTL
     * crafting path. Cancellations (direct {@link java.util.concurrent.CancellationException}
     * or wrapped in {@link java.util.concurrent.CompletionException}) are NOT failures:
     * the requester/CPU gave up on this particular future, and re-running the native
     * (GTL MAX_FAST) calculation would only burn server time.
     */
    @Unique
    private static boolean vmShouldFallback(Throwable ex) {
        // Unwrap nested CompletionException chains (bounded) so a cancellation is
        // recognised no matter how many layers of async wrapping it passed through.
        Throwable t = ex;
        for (int i = 0; i < 4 && t instanceof java.util.concurrent.CompletionException; i++) {
            t = t.getCause();
        }
        if (t == null) return false;
        return !(t instanceof java.util.concurrent.CancellationException);
    }
}







