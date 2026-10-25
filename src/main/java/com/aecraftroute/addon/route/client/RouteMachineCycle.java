package com.aecraftroute.addon.route.client;
/** Selection depends on time and candidate identity, not the old stand-alone recipe box position. */
public final class RouteMachineCycle {
    private RouteMachineCycle() {}
    public static int index(long ticks,int count,int previous,boolean hovered) {
        if(count<=0)return -1;
        if(hovered && previous>=0 && previous<count)return previous;
        return (int)Math.floorMod(ticks/60,(long)count);
    }
}
