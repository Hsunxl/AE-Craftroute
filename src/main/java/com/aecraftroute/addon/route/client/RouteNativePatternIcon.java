package com.aecraftroute.addon.route.client;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEItemKey;
import appeng.core.definitions.AEItems;
import com.aecraftroute.addon.route.RouteView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Native recipes show actual terminal-group/provider icons, independent of JEI's recipe cache. */
public final class RouteNativePatternIcon {
    private RouteNativePatternIcon() {}
    private static AEKey icon(List<RouteView.PatternSource> sources) {
        var mc=Minecraft.getInstance();
        for(var source:sources)if(source.icon()!=null)try {return source.icon().decode(mc.level.registryAccess());}
            catch(RuntimeException invalid) { /* Retain a truthful pattern fallback. */ }
        return AEItemKey.of(AEItems.PROCESSING_PATTERN.stack());
    }
    public static void draw(GuiGraphics g,int x,int y,List<RouteView.PatternSource> sources) {
        appeng.api.client.AEKeyRendering.drawInGui(Minecraft.getInstance(),g,x,y,icon(sources));
    }
    public static List<Component> tooltip(List<RouteView.PatternSource> sources) {
        var lines=new ArrayList<Component>();
        if(sources.isEmpty())lines.add(Component.literal("网络编码样板：暂无可见在线供应器来源，显示样板图标（不是机器）"));
        else {
            lines.add(Component.literal("实际样板来源（最多显示 8 个）："));
            for(var source:sources)lines.add(Component.literal(source.name()+" · "+source.location()+" · 槽 "+(source.slot()+1)));
            lines.add(Component.literal("图标来自供应器终端分组，不是按原料猜测的机器；点击查询该图标物品"));
        }
        return lines;
    }
    public static void show(List<RouteView.PatternSource> sources) {
        if(RouteClient.showJei!=null)RouteClient.showJei.accept(icon(sources));
    }
}
