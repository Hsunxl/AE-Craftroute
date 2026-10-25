package com.aecraftroute.addon.route.client;

import com.aecraftroute.addon.route.RouteGraphMath;

/** Independent numeric row below the item icon; all coordinates are world-space. */
public record RouteQuantityField(int x,int y,int width,int height) {
    public static final int NODE_HEIGHT=84,NAME_Y=56,STATE_Y=70;
    public static RouteQuantityField of(int nodeX,int nodeY,int nodeWidth) {
        return new RouteQuantityField(nodeX+4,nodeY+36,Math.max(1,nodeWidth-8),16);
    }
    public boolean contains(double mx,double my) {return mx>=x && mx<x+width && my>=y && my<y+height;}
    public static boolean editable(boolean finalProduct,boolean cycle) {return finalProduct && !cycle;}
    public static boolean wheelEdits(boolean overField,boolean ctrl,boolean horizontal,boolean cycle) {
        return overField && !ctrl && !horizontal && !cycle;
    }
    /** Wheel during typing edits the pending buffer; no model write until Enter. Empty uses current demand. */
    public static String stepText(String pending,long demand,long output,int direction,boolean fine) {
        Long parsed=RouteGraphMath.parseDemand(pending);
        return Long.toString(RouteGraphMath.stepDemand(parsed==null?demand:parsed,output,direction,fine));
    }
}
