package com.aecraftroute.addon.route;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import java.util.*;

/** Per-player, per-grid, login-session state. Never inherited by automation or other players. */
public final class RouteSessions {
    private static final Map<IGrid, Map<UUID, RoutePolicy<AEKey>>> SESSIONS = new WeakHashMap<>();
    private record Baseline(int menu, RoutePolicy<AEKey> policy) {}
    private static final Map<IGrid, Map<UUID,Baseline>> BASELINES=new WeakHashMap<>();
    public static synchronized void begin(IGrid grid,UUID player,int menu) {
        BASELINES.computeIfAbsent(grid,g->new HashMap<>()).put(player,new Baseline(menu,get(grid,player)));
    }
    public static synchronized RoutePolicy<AEKey> restore(IGrid grid,UUID player,int menu) {
        var baseline=BASELINES.getOrDefault(grid,Map.of()).get(player);
        if(baseline==null || baseline.menu()!=menu)throw new IllegalArgumentException("原始合成树会话已失效，请重新进入");
        return baseline.policy();
    }
    public static synchronized RoutePolicy<AEKey> get(IGrid grid, UUID player) {
        return SESSIONS.getOrDefault(grid, Map.of()).getOrDefault(player, new RoutePolicy<>(Map.of()));
    }
    public static RoutePolicy<AEKey> get(IGrid grid, ICraftingSimulationRequester requester) {
        if (requester == null || requester.getActionSource() == null) return new RoutePolicy<>(Map.of());
        return requester.getActionSource().player().map(p -> get(grid, p.getUUID())).orElseGet(() -> new RoutePolicy<>(Map.of()));
    }
    public static synchronized void put(IGrid grid, UUID player, RoutePolicy<AEKey> policy) {
        if (policy.empty()) {
            var players = SESSIONS.get(grid);
            if (players != null) { players.remove(player); if (players.isEmpty()) SESSIONS.remove(grid); }
        } else SESSIONS.computeIfAbsent(grid, g -> new HashMap<>()).put(player, policy);
    }
    public static synchronized void remove(UUID player) { RouteNetwork.cancelPlanning(player); SESSIONS.values().forEach(m -> m.remove(player)); BASELINES.values().forEach(m->m.remove(player)); }
    public static synchronized void clear() { RouteNetwork.clearPlanning(); SESSIONS.clear(); BASELINES.clear(); }
}

