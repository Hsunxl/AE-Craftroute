package com.ae2vm.addon.api;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import net.neoforged.fml.ModList;

import java.util.concurrent.CompletableFuture;

/**
 * Public facade for the AE2 VM crafting engine.
 * <p>
 * This mod is <b>optional</b> for third-party crafting mods (ECO, ExtendedAE,
 * etc.). They should <b>not</b> declare a hard dependency on {@code ae2vm};
 * instead use {@link #isLoaded()} to detect the engine at runtime and call
 * {@link #calculate(IGrid, ICraftingSimulationRequester, AEKey, long,
 * CalculationStrategy)} from their own {@code beginCraftingCalculation}
 * implementation to compute a crafting plan, then submit it through their own
 * job pipeline.
 * <p>
 * Plans come from a single certified planner: one backward-coverability search
 * over the network's patterns and stock that accounts for inventory, so cyclic
 * recipes, incremental yields and reuse of already-produced items are all part
 * of the same proof. See {@link #planNow} for the exact same computation used
 * by the crafting canvas preview.
 */
public final class AE2VMCrafting {

    private AE2VMCrafting() {
    }

    /**
     * (v1.13.9 PERF) Dedicated calculation executor: isolates planning from the
     * common pool, which at world-load is saturated by other mods' async tasks (the very
     * first request paid a 10ms+ queue wait at boot before the worker even started).
     * Two daemon threads keep headroom for simultaneous requests on different
     * grids/requester types.
     */
    private static final java.util.concurrent.ExecutorService VM_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "ae2vm-calc");
                t.setDaemon(true);
                return t;
            });

    /**
     * @return {@code true} if the {@code ae2vm} mod is present
     */
    public static boolean isLoaded() {
        return ModList.get().isLoaded("ae2vm");
    }

    /**
     * Compute a crafting plan for the given item.
     *
     * @param grid      the grid to compute against
     * @param requester the simulation requester (crafting link or terminal)
     * @param what      the item to craft
     * @param amount    how many are requested
     * @param strategy  calculation strategy
     * @return a future resolving to the crafting plan, or completing
     *         exceptionally if planning cannot decide (see
     *         {@link com.ae2vm.addon.planner.ProductionPlanningException}: an
     *         undecided budget is never reported as missing material)
     */
    public static CompletableFuture<ICraftingPlan> calculate(
            IGrid grid,
            ICraftingSimulationRequester requester,
            AEKey what,
            long amount,
            CalculationStrategy strategy) {
        return calculateAsync(grid, requester, what, amount, strategy);
    }

    /**
     * Synchronous variant of {@link #calculate(IGrid, ICraftingSimulationRequester, AEKey, long, CalculationStrategy)}.
     * Blocks until the plan is ready. Use the async variant on the server thread.
     */
    public static ICraftingPlan calculateSync(
            IGrid grid,
            ICraftingSimulationRequester requester,
            AEKey what,
            long amount,
            CalculationStrategy strategy) throws Exception {
        return calculateAsync(grid, requester, what, amount, strategy).get();
    }

    /**
     * Synchronous plan against the canvas' current rules, for UI previews.
     *
     * <p>Runs on the caller's thread with a caller-supplied budget, so a preview can use a
     * short budget and degrade to "not decided" instead of stalling a server tick. Throws
     * {@link com.ae2vm.addon.planner.ProductionPlanningException} with a reason that must be
     * shown as-is (LIMIT/UNSUPPORTED/UNREACHABLE are different answers, never "missing").</p>
     */
    public static com.ae2vm.addon.planner.CertifiedCraftingPlan planNow(IGrid grid, com.ae2vm.addon.route.RoutePolicy<AEKey> policy,
            net.minecraft.core.HolderLookup.Provider registries, AEKey what, long amount,
            com.ae2vm.addon.planner.ProductionPlanner.Limits limits) {
        var snapshot = com.ae2vm.addon.route.RouteSnapshot.build(grid, policy, registries, what);
        return com.ae2vm.addon.planner.NetworkProductionPlanner.plan(what, amount,
                k -> snapshot.patterns().getOrDefault(k, java.util.List.of()),
                k -> snapshot.stock().getOrDefault(k, 0L), limits);
    }

    /** One certified production planner; the canvas preview plans against the same snapshot. */
    private static CompletableFuture<ICraftingPlan> calculateAsync(IGrid grid,
            ICraftingSimulationRequester requester, AEKey what, long amount, CalculationStrategy strategy) {
        try {
            var policy = com.ae2vm.addon.route.RouteSessions.get(grid, requester);
            var registries = policy.rules().isEmpty() ? null
                    : requester.getActionSource().player().orElseThrow().registryAccess();
            // One shared builder: the canvas preview plans against exactly this snapshot.
            var snapshot = com.ae2vm.addon.route.RouteSnapshot.build(grid, policy, registries, what);
            var frozen = snapshot.patterns();
            var stock = snapshot.stock();
            var task = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
            var result = new CompletableFuture<ICraftingPlan>() {
                @Override
                public boolean cancel(boolean interrupt) {
                    boolean changed = super.cancel(interrupt);
                    var running = task.get();
                    if (running != null) running.cancel(interrupt);
                    return changed;
                }
            };
            task.set(VM_EXECUTOR.submit(() -> {
                try {
                    result.complete(com.ae2vm.addon.planner.NetworkProductionPlanner.plan(what, amount,
                            k -> frozen.getOrDefault(k, java.util.List.of()),
                            k -> stock.getOrDefault(k, 0L)));
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                }
            }));
            if (result.isCancelled()) task.get().cancel(true);
            return result;
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
    }
}
