package com.aecraftroute.addon.api;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import net.neoforged.fml.ModList;

import java.util.concurrent.CompletableFuture;

/**
 * Public facade for the AE-Craftroute crafting engine.
 * <p>
 * This mod is <b>optional</b> for third-party crafting mods (ECO, ExtendedAE,
 * etc.). They should <b>not</b> declare a hard dependency on {@code aecraftroute};
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
public final class CraftRouteCrafting {

    private CraftRouteCrafting() {
    }

    /**
     * @return {@code true} if the {@code aecraftroute} mod is present
     */
    public static boolean isLoaded() {
        return ModList.get().isLoaded("aecraftroute");
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
     *         {@link com.aecraftroute.addon.planner.ProductionPlanningException}: an
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
        var server=net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if(server!=null && server.isSameThread()) throw new IllegalStateException("calculateSync must not block the server thread; use calculate");
        return calculateAsync(grid, requester, what, amount, strategy).get();
    }

    /**
     * Synchronous plan against the canvas' current rules, for UI previews.
     *
     * <p>Runs on the caller's thread with a caller-supplied budget, so a preview can use a
     * short budget and degrade to "not decided" instead of stalling a server tick. Throws
     * {@link com.aecraftroute.addon.planner.ProductionPlanningException} with a reason that must be
     * shown as-is (LIMIT/UNSUPPORTED/UNREACHABLE are different answers, never "missing").</p>
     */
    public static com.aecraftroute.addon.planner.CertifiedCraftingPlan planNow(IGrid grid, com.aecraftroute.addon.route.RoutePolicy<AEKey> policy,
            net.minecraft.core.HolderLookup.Provider registries, AEKey what, long amount,
            com.aecraftroute.addon.planner.ProductionPlanner.Limits limits) {
        long deadline=System.nanoTime()+limits.nanos();
        var snapshot=com.aecraftroute.addon.route.RouteSnapshot.build(grid,policy,registries,what,deadline);
        long remaining=deadline-System.nanoTime();
        if(remaining<=0)throw new com.aecraftroute.addon.planner.ProductionPlanningException(
                com.aecraftroute.addon.planner.ProductionPlanningException.Reason.LIMIT,"快照用尽端到端预算；不是缺料");
        var plan=com.aecraftroute.addon.route.RoutePlanningService.planFrozen(snapshot,policy,what,amount,
                new com.aecraftroute.addon.planner.ProductionPlanner.Limits(limits.states(),remaining));
        if(System.nanoTime()>deadline)throw new com.aecraftroute.addon.planner.ProductionPlanningException(
                com.aecraftroute.addon.planner.ProductionPlanningException.Reason.LIMIT,"端到端预算已用尽；不是缺料");
        return plan;
    }

    /** Same frozen input and path execution contract for the real CPU and the canvas. */
    private static CompletableFuture<ICraftingPlan> calculateAsync(IGrid grid,
            ICraftingSimulationRequester requester, AEKey what, long amount, CalculationStrategy strategy) {
        var server=net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if(server==null)return CompletableFuture.failedFuture(new IllegalStateException("Server is stopped"));
        var result=new CompletableFuture<ICraftingPlan>();
        Runnable enqueue=()->{
            if(result.isCancelled())return;
            try {
                var policy=com.aecraftroute.addon.route.RouteSessions.get(grid,requester);
                var registries=policy.empty()?null:requester.getActionSource().player()
                        .map(player -> (net.minecraft.core.HolderLookup.Provider) player.registryAccess())
                        .orElse(server.registryAccess());
                var inner=com.aecraftroute.addon.route.RoutePlanningService.planAsync(grid,policy,registries,what,amount,
                        new com.aecraftroute.addon.planner.ProductionPlanner.Limits(150_000,30_000_000_000L),
                        ()->com.aecraftroute.addon.route.RouteSessions.get(grid,requester).equals(policy));
                result.whenComplete((p,e)->{if(result.isCancelled())inner.cancel(true);});
                inner.whenComplete((p,e)->{if(e==null)result.complete(p);else result.completeExceptionally(e);});
            } catch(Throwable failure) {result.completeExceptionally(failure);}
        };
        if(server.isSameThread())enqueue.run();else server.execute(enqueue);
        return result;
    }
}
