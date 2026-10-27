package com.aecraftroute.addon.route;

import java.util.Set;

public final class RouteRequestRules {
    private static final Set<String> BACKGROUND=Set.of("query","workbench","begin","preview");
    /** Frames between two attempts to read the confirmation target from the server. */
    public static final int BEGIN_RETRY_FRAMES=20;
    /** Gives up after about twenty seconds; a plan that is still not ready then is not pending, it is absent. */
    public static final int BEGIN_MAX_ATTEMPTS=20;
    private RouteRequestRules() {}
    public static boolean mutation(String action) {return !BACKGROUND.contains(action);}
    /**
     * The server refuses a request amount it cannot plan: below one, or above the canvas
     * demand range. The client clamps its own field, but a packet is not a client, and
     * {@link RouteGraphMath#MAX_DEMAND} exists precisely to keep per-input demand arithmetic
     * away from overflow. Rejecting is typed; letting it through only moves the failure into
     * {@code Math.multiplyExact} deep inside the planner.
     */
    public static long requirePlanAmount(long amount) {
        if(amount<=0)throw new IllegalArgumentException("目标数量必须大于零");
        if(amount>RouteGraphMath.MAX_DEMAND)
            throw new IllegalArgumentException("目标数量超过上限 "+RouteGraphMath.MAX_DEMAND+"；未规划");
        return amount;
    }
    public static boolean expired(long now,long started) {return now-started>200;}
    public static boolean beginRetryDue(long now,long retryAt) {return retryAt>=0 && now>=retryAt;}
    public static boolean currentPlanResponse(int response,int latest,String requested,String current) {
        return response==latest && java.util.Objects.equals(requested,current);
    }
    public static void requirePlanningEnabled(String action,boolean enabled) {
        if("apply".equals(action) && !enabled)throw new IllegalArgumentException("AE-Craftroute 的自动合成没有开启；未应用强制画布");
    }
}
