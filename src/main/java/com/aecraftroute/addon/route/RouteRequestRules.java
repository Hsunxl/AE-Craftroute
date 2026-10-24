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
    public static boolean expired(long now,long started) {return now-started>200;}
    public static boolean beginRetryDue(long now,long retryAt) {return retryAt>=0 && now>=retryAt;}
    public static boolean currentPlanResponse(int response,int latest,String requested,String current) {
        return response==latest && java.util.Objects.equals(requested,current);
    }
    public static void requirePlanningEnabled(String action,boolean enabled) {
        if("apply".equals(action) && !enabled)throw new IllegalArgumentException("AE-Craftroute 的自动合成没有开启；未应用强制画布");
    }
}
