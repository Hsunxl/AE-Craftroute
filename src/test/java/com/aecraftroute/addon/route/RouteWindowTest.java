package com.aecraftroute.addon.route;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RouteWindowTest {
    @Test void expandedWorkspaceReservesEqualJeiColumns() {
        var w=RouteWindow.of(854,480);
        assertEquals(new RouteWindow(112,8,630,464),w);
        assertEquals(w.left(),854-w.right());
        assertEquals(w.top(),480-w.bottom());
    }
    @Test void internalGeometryUsesSameAbsoluteBoundsAsJei() {
        var w=RouteWindow.of(854,480);var l=w.layout();
        assertEquals(w.right(),l.contentRight());
        assertEquals(w.left()+8,l.canvasLeft());
        assertEquals(w.right()-8,l.panelRight());
        assertTrue(l.toolbarY()>=w.top());
        assertTrue(l.canvasBottom()<w.bottom());
        assertTrue(l.canvasRight()<l.panelLeft());
    }
    @Test void usersGuiScaleFourHasRealCanvasAndRecipeBodyNotAConfirmationSize() {
        var w=RouteWindow.of(700,310);var l=w.layout();
        assertEquals(new RouteWindow(98,8,504,294),w);
        assertTrue(l.canvasRight()-l.canvasLeft()>=280);
        assertTrue(l.recipeBodyHeight()>=90);
        assertTrue(w.left()>=64);
        assertTrue(w.width()>=320);
    }
    @Test void resizingRecomputesWorkspaceInsteadOfRememberingSmallParent() {
        assertEquals(400,RouteWindow.of(400,240).width());
        assertEquals(630,RouteWindow.of(854,480).width());
        assertEquals(1056,RouteWindow.of(1280,720).width());
    }
    @Test void minimumAndTinyScreensRemainBounded() {
        for(int[] size:new int[][]{{195,222},{320,200},{400,240},{700,310},{854,480},{1280,720}}) {
            var w=RouteWindow.of(size[0],size[1]);
            assertTrue(w.left()>=0 && w.top()>=0);
            assertTrue(w.right()<=size[0] && w.bottom()<=size[1]);
            assertTrue(w.width()>0 && w.height()>0);
        }
    }
    @Test void rejectsInvalidScreenBeforeRendering() {
        assertThrows(IllegalArgumentException.class,()->RouteWindow.of(0,310));
        assertThrows(IllegalArgumentException.class,()->RouteWindow.of(700,-1));
    }
}
