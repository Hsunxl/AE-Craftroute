package com.aecraftroute.addon.route.client;
import appeng.api.stacks.*;
import appeng.core.definitions.AEItems;
import com.aecraftroute.addon.route.RouteView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import java.util.*;

/** Native source rotation survives graph rebuilds because selection is keyed by material path. */
public final class RouteNativeMachines {
    private final Map<String,AEKey> shown=new HashMap<>();

    private List<AEKey> candidates(List<RouteView.PatternSource> sources) {
        var result=new LinkedHashSet<AEKey>();
        for(var source:sources)if(source.icon()!=null)try {
            var key=source.icon().decode(Minecraft.getInstance().level.registryAccess());
            if(key!=null && !RouteEmcPresentation.isCurrency(key))result.add(key);
        } catch(RuntimeException ignored) {}
        if(result.isEmpty())result.add(AEItemKey.of(AEItems.PROCESSING_PATTERN.stack()));
        return List.copyOf(result);
    }
    public void draw(GuiGraphics g,String path,List<RouteView.PatternSource> sources,int x,int y,int mx,int my,long ticks) {
        var values=candidates(sources);boolean hover=mx>=x && mx<x+18 && my>=y && my<y+18;
        AEKey key=shown.get(path);
        key=values.get(RouteMachineCycle.index(ticks,values,key,hover));
        shown.put(path,key);drawKey(g,key,x+1,y+1);
        // Canvas badges open a recipe preview, not the old machine-icon tray.
    }
    private static void drawKey(GuiGraphics g,AEKey key,int x,int y) {appeng.api.client.AEKeyRendering.drawInGui(Minecraft.getInstance(),g,x,y,key);}
}
