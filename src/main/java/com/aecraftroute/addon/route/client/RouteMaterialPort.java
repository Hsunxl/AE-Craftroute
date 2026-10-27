package com.aecraftroute.addon.route.client;
/** The acquisition/machine badge is owned by a material, never an independently positioned node. */
public record RouteMaterialPort(int x,int y) {
    public static final int SIZE=18;
    public static RouteMaterialPort of(int x,int y,int materialWidth) {return new RouteMaterialPort(x+materialWidth-22,y+4);}
    public boolean contains(double mx,double my) {return mx>=x && mx<x+SIZE && my>=y && my<y+SIZE;}
}
