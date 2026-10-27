package com.aecraftroute.addon.route.client;

/** Allocation-free invalidation key for AE2CT 1.1.1 getMousePoint. The returned
 * point is used only for map lookup, never inserted into a map or mutated. */
public final class RouteTreeMouseCache {
    private boolean initialized;
    private double x,y;
    private int left,top,outputX,outputY,spacingX,spacingY,stackLength;
    private float scroll;
    public boolean changed(double x,double y,int left,int top,int outputX,int outputY,
                           int spacingX,int spacingY,int stackLength,float scroll) {
        boolean changed=!initialized || this.x!=x || this.y!=y || this.left!=left || this.top!=top
                || this.outputX!=outputX || this.outputY!=outputY || this.spacingX!=spacingX
                || this.spacingY!=spacingY || this.stackLength!=stackLength || this.scroll!=scroll;
        initialized=true;this.x=x;this.y=y;this.left=left;this.top=top;this.outputX=outputX;
        this.outputY=outputY;this.spacingX=spacingX;this.spacingY=spacingY;
        this.stackLength=stackLength;this.scroll=scroll;
        return changed;
    }
}
