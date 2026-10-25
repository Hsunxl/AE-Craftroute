package com.aecraftroute.addon.route;

import appeng.api.networking.IGrid;
import appeng.helpers.patternprovider.PatternContainer;
import java.util.*;

/** Enumerate real node owners, not an interface lookup in AE's exact-class machine index. */
public final class RouteProviders {
    private RouteProviders() {}
    private static final Set<String> reported=java.util.concurrent.ConcurrentHashMap.newKeySet();
    static void issue(PatternContainer provider,String phase,RuntimeException e) {
        if(reported.add(provider.getClass().getName()+":"+phase))
            com.aecraftroute.addon.CraftRouteAddon.LOGGER.warn("Provider mapping failed for {} ({})",provider.getClass().getName(),phase,e);
    }
    private static long order(PatternContainer provider) {
        try {return provider.getTerminalSortOrder();}catch(RuntimeException e){issue(provider,"sort order",e);return Long.MAX_VALUE;}
    }
    public static List<PatternContainer> active(IGrid grid) {
        var seen=Collections.newSetFromMap(new IdentityHashMap<PatternContainer,Boolean>());
        var result=new ArrayList<PatternContainer>();
        for(var node:grid.getNodes()) {
            if(!node.isActive() || !(node.getOwner() instanceof PatternContainer provider))continue;
            try {
                if(RouteProviderWindow.visible(provider.getGrid()==grid,provider.isVisibleInTerminal(),
                        provider.getTerminalPatternInventory().size()) && seen.add(provider))result.add(provider);
            } catch(RuntimeException e) {issue(provider,"inventory",e);}
        }
        result.sort(Comparator.comparingLong(RouteProviders::order));
        return List.copyOf(result);
    }
}
