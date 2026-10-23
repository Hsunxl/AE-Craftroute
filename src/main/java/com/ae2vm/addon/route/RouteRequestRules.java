package com.ae2vm.addon.route;

import java.util.Set;

public final class RouteRequestRules {
    private static final Set<String> BACKGROUND=Set.of("query","workbench","begin");
    private RouteRequestRules() {}
    public static boolean mutation(String action) {return !BACKGROUND.contains(action);}
    public static boolean expired(long now,long started) {return now-started>200;}
}
