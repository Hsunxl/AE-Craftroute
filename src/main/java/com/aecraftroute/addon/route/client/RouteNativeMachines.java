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
    private final RouteMachineTray<AEKey> tray=new RouteMachineTray<>();
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
        if(!hover || key==null || !values.contains(key))key=values.get(RouteMachineCycle.index(ticks,values.size(),values.indexOf(key),hover));
        shown.put(path,key);drawKey(g,key,x+1,y+1);
        if(hover)tray.offerWorld(g,values,x,y);
    }
    private static void drawKey(GuiGraphics g,AEKey key,int x,int y) {appeng.api.client.AEKeyRendering.drawInGui(Minecraft.getInstance(),g,x,y,key);}
    public void clearOverlay() {tray.clear();}
    public void renderOverlay(GuiGraphics g,int mx,int my) {tray.render(g,mx,my,RouteNativeMachines::drawKey,key->key.getDisplayName().getString());}
    public boolean click(double mx,double my,int button) {return tray.click(mx,my,button,key->{if(RouteClient.showJei!=null)RouteClient.showJei.accept(key);});}
    public boolean scroll(double mx,double my,double delta) {return tray.scroll(mx,my,delta);}
}
