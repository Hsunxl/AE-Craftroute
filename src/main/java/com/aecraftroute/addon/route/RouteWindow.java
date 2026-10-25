package com.aecraftroute.addon.route;

/** Expanded central workspace, distinct from small confirmation dialogs and JEI sidebars. */
public record RouteWindow(int left,int top,int width,int height) {
    public static RouteWindow of(int screenWidth,int screenHeight) {
        if(screenWidth<=0 || screenHeight<=0)throw new IllegalArgumentException("窗口尺寸无效");
        // Preserve both JEI sidebars without inheriting the small crafting-confirmation size.
        int side=Math.min(Math.max(0,(screenWidth-400)/2),Math.max(64,Math.min(112,screenWidth*14/100)));
        int vertical=Math.min(8,Math.max(0,(screenHeight-200)/2));
        int w=screenWidth-2*side,h=screenHeight-2*vertical;
        return new RouteWindow((screenWidth-w)/2,(screenHeight-h)/2,w,h);
    }
    public int right() {return left+width;}
    public int bottom() {return top+height;}
    public RouteLayout layout() {
        // This window already reserves both JEI sidebars; do not reserve a second right strip.
        var l=RouteLayout.inContent(width,height);
        return new RouteLayout(width,height,right(),left+l.panelLeft(),left+l.panelRight(),l.panelWidth(),
                left+l.canvasLeft(),left+l.canvasRight(),top+l.canvasTop(),top+l.canvasBottom(),
                top+l.toolbarY(),l.compact(),l.collapseCache());
    }
}
