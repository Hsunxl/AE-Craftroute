package com.aecraftroute.addon.route.client;

/** Popup coordinates are screen-space, never canvas zoom-space. */
public record RouteRecipePreviewBox(int x,int y,int width,int height) {
    public static RouteRecipePreviewBox of(int anchorX,int anchorY,int anchorWidth,int screenWidth,int screenHeight) {
        int w=Math.max(1,Math.min(260,screenWidth-8)),h=Math.max(1,Math.min(188,screenHeight-8));
        int x=anchorX+anchorWidth;
        if(x+w>screenWidth-4)x=anchorX-w;
        return new RouteRecipePreviewBox(Math.max(4,Math.min(x,screenWidth-w-4)),
                Math.max(4,Math.min(anchorY,screenHeight-h-4)),w,h);
    }
    public boolean contains(double mx,double my) {return mx>=x && mx<x+width && my>=y && my<y+height;}
}
