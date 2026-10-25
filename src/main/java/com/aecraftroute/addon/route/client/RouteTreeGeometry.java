package com.aecraftroute.addon.route.client;
import java.util.*;

/** Material-only tree constraints and subtree motion, independent of Minecraft rendering. */
public final class RouteTreeGeometry {
    public static final int X_GAP=16,Y_GAP=28;
    private RouteTreeGeometry() {}
    public record Box(String path,int depth,int x,int y,int width,int height) {
        Box move(int dx,int dy) {return new Box(path,depth,x+dx,y+dy,width,height);}
    }
    public static boolean inSubtree(String root,String path) {return root.equals(path)||path.startsWith(root+"/");}
    public static List<Box> translate(List<Box> boxes,String root,int dx,int dy) {
        return boxes.stream().map(box->inSubtree(root,box.path())?box.move(dx,dy):box).toList();
    }
    public static List<Box> constrain(List<Box> boxes) {
        var positions=new LinkedHashMap<String,Box>();boxes.forEach(box->positions.put(box.path(),box));
        int maxDepth=boxes.stream().mapToInt(Box::depth).max().orElse(-1),previousBottom=Integer.MIN_VALUE;
        for(int depth=0;depth<=maxDepth;depth++) {
            int level=depth;
            var row=positions.values().stream().filter(box->box.depth()==level)
                    .sorted(Comparator.comparingInt(Box::x).thenComparing(Box::path)).map(Box::path).toList();
            int right=Integer.MIN_VALUE,bottom=previousBottom;
            for(String path:row) {
                var box=positions.get(path);
                int x=right==Integer.MIN_VALUE?box.x():Math.max(box.x(),right+X_GAP);
                int y=previousBottom==Integer.MIN_VALUE?box.y():Math.max(box.y(),previousBottom+Y_GAP);
                int dx=x-box.x(),dy=y-box.y();
                if(dx!=0 || dy!=0)positions.replaceAll((p,b)->inSubtree(path,p)?b.move(dx,dy):b);
                box=positions.get(path);right=box.x()+box.width();bottom=Math.max(bottom,box.y()+box.height());
            }
            previousBottom=bottom;
        }
        return List.copyOf(positions.values());
    }
    public static List<Box> drag(List<Box> boxes,String root,int dx,int dy) {return constrain(translate(boxes,root,dx,dy));}
    /** Local offsets inherit the full parent displacement, including off-screen/collapsed descendants. */
    public static int[] relative(int x,int y,int autoX,int autoY,int parentDx,int parentDy) {
        return new int[]{x-autoX-parentDx,y-autoY-parentDy};
    }
}
