package com.aecraftroute.addon.mixin;

import com.aecraftroute.addon.route.client.RouteClient;
import com.neuvillette.ae2ct.api.CraftingTreeHelper;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.ArrayList;
import java.util.Collections;

/** Optional AE2CT integration; hover + F8, or Shift-right-click a tree node. */
@Pseudo
@Mixin(targets = "com.neuvillette.ae2ct.gui.CraftingTreeWidget", remap = false)
public abstract class RouteTreeWidgetMixin {
    @Shadow private CraftingTreeHelper.NodeManager _nodeManager;
    @Shadow private java.awt.Point getMousePoint(double x, double y) { throw new AssertionError(); }
    @Shadow private boolean isMouseOutScreen(double x, double y) { throw new AssertionError(); }
    @Shadow @Final protected appeng.client.gui.AEBaseScreen<?> screen;
    @Shadow private int outputX,outputY,spacingX,spacingY,stackLength;
    @Shadow private float scroll;
    @Unique private final com.aecraftroute.addon.route.client.RouteTreeMouseCache aecraftroute$mouseCache=
            new com.aecraftroute.addon.route.client.RouteTreeMouseCache();
    @Unique private java.awt.Point aecraftroute$mousePoint;
    @Unique private java.awt.Point aecraftroute$lookupPoint(double x,double y) {
        if(aecraftroute$mouseCache.changed(x,y,screen.getGuiLeft(),screen.getGuiTop(),
                outputX,outputY,spacingX,spacingY,stackLength,scroll))
            aecraftroute$mousePoint=getMousePoint(x,y);
        return aecraftroute$mousePoint;
    }
    @Inject(method = "draw", at = @At("TAIL"))
    private void aecraftroute$hover(net.minecraft.client.gui.GuiGraphics g,int left,int top,int x,int y,CallbackInfo ci) {
        var node = _nodeManager == null || isMouseOutScreen(x,y) ? null : _nodeManager.map.get(aecraftroute$lookupPoint(x,y));
        if (node == null || node.stack == null) {
            RouteClient.selectedTreeKey = null;
            RouteClient.selectedTreeAmount = 0;
            RouteClient.selectedTreePath = "";
        } else {
            RouteClient.selectedTreeKey = node.stack.what();
            RouteClient.selectedTreeAmount = node.amount == null ? 0 : node.amount;
            RouteClient.selectedTreePath = aecraftroute$path(node);
        }
    }
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void aecraftroute$select(double x, double y, int button, CallbackInfoReturnable<Boolean> ci) {
        if (button != 1 || !net.minecraft.client.gui.screens.Screen.hasShiftDown() || _nodeManager == null || isMouseOutScreen(x,y)) return;
        var node = _nodeManager.map.get(aecraftroute$lookupPoint(x,y));
        if (node != null && node.stack != null) {
            RouteClient.open(net.minecraft.client.Minecraft.getInstance().screen, node.stack.what(), null,
                    node.amount == null ? 0 : node.amount, "合成树", aecraftroute$path(node));
            ci.setReturnValue(true);
        }
    }
    @Inject(method = "<init>", at = @At("RETURN"))
    private void aecraftroute$reset(CallbackInfo ci) {
        RouteClient.selectedTreeKey = null;
        RouteClient.selectedTreeAmount = 0;
        RouteClient.selectedTreePath = "";
    }
    private static String aecraftroute$path(CraftingTreeHelper.Node node) {
        var parts = new ArrayList<String>();
        for (var current = node; current != null && parts.size() < 6; current = current.parent) {
            if (current.stack != null) parts.add(current.stack.what().getDisplayName().getString());
        }
        Collections.reverse(parts);
        return String.join(" → ", parts);
    }
}
