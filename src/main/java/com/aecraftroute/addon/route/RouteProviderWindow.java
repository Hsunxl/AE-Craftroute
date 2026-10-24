package com.aecraftroute.addon.route;

/** One device, one contiguous window of its complete (possibly multiblock) inventory. */
public record RouteProviderWindow(int page,int pages,int first,int end) {
    public static RouteProviderWindow of(int total,int columns,int rows,int requestedPage) {
        if(total<0 || columns<1 || columns>18 || rows<1 || rows>12)throw new IllegalArgumentException("槽位视图无效");
        int capacity=columns*rows;
        int pages=Math.max(1,(int)(((long)total+capacity-1)/capacity));
        int page=Math.max(0,Math.min(requestedPage,pages-1));
        int first=page*capacity;
        return new RouteProviderWindow(page,pages,first,Math.min(total,first+capacity));
    }
    public static boolean visible(boolean sameNetwork,boolean visibleInTerminal,int slots) {
        return sameNetwork && visibleInTerminal && slots>0;
    }
}
