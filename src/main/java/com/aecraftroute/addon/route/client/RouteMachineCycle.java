package com.aecraftroute.addon.route.client;
/** Selection depends on time and candidate identity, not the old stand-alone recipe box position. */
public final class RouteMachineCycle {
    private RouteMachineCycle() {}
    /** Immutable lists reject indexOf(null); a missing cached icon is a normal first frame. */
    public static <T> int index(long ticks,java.util.List<T> candidates,T previous,boolean hovered) {
        return index(ticks,candidates.size(),previous==null?-1:candidates.indexOf(previous),hovered);
    }
    public static int index(long ticks,int count,int previous,boolean hovered) {
        if(count<=0)return -1;
        if(hovered && previous>=0 && previous<count)return previous;
        return (int)Math.floorMod(ticks/60,(long)count);
    }
}
