package com.aecraftroute.addon.route;

import appeng.api.networking.IGrid;
import appeng.helpers.patternprovider.PatternContainer;
import java.util.*;

/** Enumerate real node owners, not an interface lookup in AE's exact-class machine index. */
public final class RouteProviders {
    private RouteProviders() {}
    public static List<PatternContainer> active(IGrid grid) {
        var seen=Collections.newSetFromMap(new IdentityHashMap<PatternContainer,Boolean>());
        var result=new ArrayList<PatternContainer>();
        for(var node:grid.getNodes()) {
            if(!node.isActive() || !(node.getOwner() instanceof PatternContainer provider))continue;
            if(RouteProviderWindow.visible(provider.getGrid()==grid,provider.isVisibleInTerminal(),
                    provider.getTerminalPatternInventory().size()) && seen.add(provider))result.add(provider);
        }
        result.sort(Comparator.comparingLong(PatternContainer::getTerminalSortOrder));
        return List.copyOf(result);
    }
}
