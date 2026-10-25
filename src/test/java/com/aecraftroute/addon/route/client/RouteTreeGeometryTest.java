package com.aecraftroute.addon.route.client;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RouteTreeGeometryTest {
    RouteTreeGeometry.Box box(String path,int depth,int x,int y) {return new RouteTreeGeometry.Box(path,depth,x,y,86,62);}
    final List<RouteTreeGeometry.Box> tree=List.of(box("root",0,150,0),box("root/0",1,0,100),
            box("root/0/0",2,0,200),box("root/1",1,250,100),box("root/1/0",2,250,200));
    @Test void parentDragMovesAllAndOnlyItsDescendantsWhenSpaceAllows() {
        var moved=RouteTreeGeometry.drag(tree,"root/0",20,5);
        assertEquals(tree.get(1).x()+20,moved.get(1).x());assertEquals(tree.get(2).x()+20,moved.get(2).x());
        assertEquals(tree.get(2).y()+5,moved.get(2).y());assertEquals(tree.get(0),moved.get(0));assertEquals(tree.get(3),moved.get(3));
    }
    @Test void rootDragMovesTheWholeTreeExactly() {
        assertEquals(RouteTreeGeometry.translate(tree,"root",75,-40),RouteTreeGeometry.drag(tree,"root",75,-40));
    }
    @Test void pathPrefixesNeverMoveAnotherSibling() {
        assertTrue(RouteTreeGeometry.inSubtree("root/1","root/1/0"));
        assertFalse(RouteTreeGeometry.inSubtree("root/1","root/10"));
        assertFalse(RouteTreeGeometry.inSubtree("root/1","root/10/0"));
    }
    void assertConstraints(List<RouteTreeGeometry.Box> boxes) {
        for(var a:boxes)for(var b:boxes)if(!a.path().equals(b.path())) {
            if(a.depth()==b.depth())assertTrue(a.x()+a.width()+16<=b.x() || b.x()+b.width()+16<=a.x());
            if(a.depth()<b.depth())assertTrue(a.y()+a.height()+28<=b.y());
        }
    }
    @Test void draggingAcrossPeersOrAboveParentCannotBreakBands() {
        assertConstraints(RouteTreeGeometry.drag(tree,"root/1",-250,-400));
        assertConstraints(RouteTreeGeometry.drag(tree,"root/0",250,300));
    }
    @Test void collisionsMoveTheCollidingSubtreeNotJustItsRoot() {
        var initial=List.of(box("root",0,0,0),box("root/0",1,0,100),box("root/0/0",2,0,200),
                box("root/1",1,10,100),box("root/1/0",2,10,200));
        var result=RouteTreeGeometry.constrain(initial);
        assertEquals(result.get(3).x()-10,result.get(4).x()-10);assertConstraints(result);
    }
    @Test void localOffsetDoesNotDoubleApplyParentMotion() {
        var offset=RouteTreeGeometry.relative(270,340,100,200,150,100);
        assertArrayEquals(new int[]{20,40},offset);
        assertEquals(270,100+150+offset[0]);assertEquals(340,200+100+offset[1]);
    }
    @Test void solvingIsDeterministicIdempotentAndHandlesEmptyTrees() {
        assertTrue(RouteTreeGeometry.constrain(List.of()).isEmpty());
        var random=new Random(50);
        for(int trial=0;trial<100;trial++) {
            var nodes=new ArrayList<RouteTreeGeometry.Box>();nodes.add(box("root",0,random.nextInt(100),random.nextInt(100)));
            for(int i=0;i<5;i++) {
                nodes.add(box("root/"+i,1,random.nextInt(200)-100,random.nextInt(200)-100));
                nodes.add(box("root/"+i+"/0",2,random.nextInt(200)-100,random.nextInt(200)-100));
            }
            var solved=RouteTreeGeometry.constrain(nodes);assertConstraints(solved);
            assertEquals(solved,RouteTreeGeometry.constrain(solved));assertEquals(solved,RouteTreeGeometry.constrain(nodes));
        }
    }
}
