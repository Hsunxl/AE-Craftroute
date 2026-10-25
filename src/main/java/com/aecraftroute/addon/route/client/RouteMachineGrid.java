package com.aecraftroute.addon.route.client;

/** Screen-space tray geometry. Rows scroll only when all candidates cannot fit on screen. */
public record RouteMachineGrid(int x,int y,int columns,int rows,int count) {
    public int width() {return columns*20+8;}
    public int height() {return rows*20+22;}
    public int capacity() {return columns*rows;}
    public boolean contains(double mx,double my) {return mx>=x && mx<x+width() && my>=y && my<y+height();}
    public int hit(double mx,double my,int first) {
        int cx=(int)Math.floor((mx-x-4)/20),cy=(int)Math.floor((my-y-18)/20);
        if(cx<0 || cx>=columns || cy<0 || cy>=rows)return -1;
        int index=first+cy*columns+cx;return index<count?index:-1;
    }
    public static RouteMachineGrid of(int anchorX,int anchorY,int anchorWidth,int count,int screenWidth,int screenHeight) {
        int columns=Math.max(1,Math.min(Math.min(10,Math.max(2,count)),Math.max(1,(screenWidth-16)/20)));
        int rows=Math.max(1,Math.min((count+columns-1)/columns,Math.max(1,(screenHeight-30)/20)));
        int width=columns*20+8,height=rows*20+22;
        int x=anchorX+anchorWidth;
        if(x+width>screenWidth-4)x=anchorX-width;
        x=Math.max(4,Math.min(x,screenWidth-width-4));
        int y=Math.max(4,Math.min(anchorY,screenHeight-height-4));
        return new RouteMachineGrid(x,y,columns,rows,count);
    }
}
