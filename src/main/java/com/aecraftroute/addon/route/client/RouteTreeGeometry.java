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
        var placed=new ArrayList<String>();
        // A subtree shift must never move an already accepted box. Process every parent
        // before its descendants even if a restored/imported list arrives out of order.
        var ordered=new ArrayList<>(boxes);
        ordered.sort(Comparator.comparingInt(Box::depth));
        for(var incoming:ordered) {
            String path=incoming.path();
            // Keep a legal requested position, regardless of depth. Resolve only real
            // two-dimensional collisions; entire depth bands must not pin upward drags.
            boolean moved;
            do {
                moved=false;
                var box=positions.get(path);
                for(String otherPath:placed) {
                    var other=positions.get(otherPath);
                    if(!overlapsWithGap(box,other))continue;
                    int right=other.x()+other.width()+X_GAP-box.x();
                    int down=other.y()+other.height()+Y_GAP-box.y();
                    int dx=right<=down?right:0,dy=right<=down?0:down;
                    positions.replaceAll((p,b)->inSubtree(path,p)?b.move(dx,dy):b);
                    moved=true;break;
                }
            } while(moved);
            placed.add(path);
        }
        return List.copyOf(positions.values());
    }
    private static boolean overlapsWithGap(Box a,Box b) {
        return (long)a.x()< (long)b.x()+b.width()+X_GAP
                && (long)b.x()< (long)a.x()+a.width()+X_GAP
                && (long)a.y()< (long)b.y()+b.height()+Y_GAP
                && (long)b.y()< (long)a.y()+a.height()+Y_GAP;
    }
    public static List<Box> drag(List<Box> boxes,String root,int dx,int dy) {return constrain(translate(boxes,root,dx,dy));}
    /** Local offsets inherit the full parent displacement, including off-screen/collapsed descendants. */
    public static int[] relative(int x,int y,int autoX,int autoY,int parentDx,int parentDy) {
        return new int[]{x-autoX-parentDx,y-autoY-parentDy};
    }
}
