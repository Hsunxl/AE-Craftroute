package com.aecraftroute.addon.route;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Request-local authoritative index: encoded pattern identity -> actual provider slots. */
public final class RoutePatternSources {
    private RoutePatternSources() {}
    public static Map<String,List<RouteView.PatternSource>> find(IGrid grid,ServerLevel level,Set<String> wanted) {
        var result=new LinkedHashMap<String,List<RouteView.PatternSource>>();
        if(wanted.isEmpty())return Map.of();
        for(var provider:RouteProviders.active(grid)) {
            var group=provider.getTerminalGroup();var icon=group.icon();String location="";
            if(provider instanceof PatternProviderLogicHost host) {
                var pos=host.getBlockEntity().getBlockPos();
                location=host.getBlockEntity().getLevel().dimension().location()+" "+pos.getX()+", "+pos.getY()+", "+pos.getZ();
                if(icon==null)icon=host.getTerminalIcon();
            }
            var image=icon==null?null:RouteView.Stack.of(icon,1,level.registryAccess());
            var inventory=provider.getTerminalPatternInventory();
            for(int slot=0;slot<inventory.size();slot++) {
                var item=inventory.getStackInSlot(slot);if(item.isEmpty())continue;
                try {
                    var pattern=PatternDetailsHelper.decodePattern(item,level);if(pattern==null)continue;
                    String id=RouteIdentity.pattern(pattern,level.registryAccess());if(!wanted.contains(id))continue;
                    var rows=result.computeIfAbsent(id,k->new ArrayList<>());
                    // Bound network metadata; retain deterministic first providers and exact slots.
                    if(rows.size()<8)rows.add(new RouteView.PatternSource(group.name().getString(),image,location,slot));
                } catch(RuntimeException invalid) {
                    // One stale/unsupported third-party pattern cannot hide other providers.
                }
            }
        }
        result.replaceAll((id,rows)->List.copyOf(rows));return Map.copyOf(result);
    }
}
