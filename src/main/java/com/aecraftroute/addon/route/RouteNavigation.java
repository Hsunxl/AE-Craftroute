package com.aecraftroute.addon.route;

public final class RouteNavigation {
    private RouteNavigation() {}
    public static int cycle(int current,int delta,int count) {return count<=0?0:Math.floorMod(current+delta,count);}
}
