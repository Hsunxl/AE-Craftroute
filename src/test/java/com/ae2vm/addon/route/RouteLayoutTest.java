package com.ae2vm.addon.route;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouteLayoutTest {
    @Test void jeiOpeningBeforeScreenInitUsesCurrentLogicalWindow() {
        assertEquals(RouteLayout.of(700,310),RouteLayout.forScreen(0,0,700,310));
    }
    @Test void partiallyInitializedScreenUsesOneConsistentWindowSnapshot() {
        assertEquals(RouteLayout.of(700,310),RouteLayout.forScreen(700,0,700,310));
        assertEquals(RouteLayout.of(700,310),RouteLayout.forScreen(0,310,700,310));
    }
    @Test void initializedAndResizedScreenKeepsItsOwnDimensions() {
        assertEquals(RouteLayout.of(854,480),RouteLayout.forScreen(854,480,700,310));
    }
    @Test void screenshotAtGuiScaleFourIsNotRejected() {
        var l=RouteLayout.of(700,310);
        assertTrue(l.usable());assertTrue(l.compact());
        assertTrue(l.recipeBodyHeight()>=70);
    }
    @Test void standardMinecraftLogicalWindowsStayUsable() {
        for(int[] size:new int[][]{{320,240},{427,240},{480,270},{512,288},{640,360},{700,306},{854,480},{1280,720}}) {
            var l=RouteLayout.of(size[0],size[1]);
            assertTrue(l.usable(),size[0]+"x"+size[1]);
            assertTrue(l.canvasRight()-l.canvasLeft()>=96);
            assertTrue(l.panelWidth()-14>=120);
            assertTrue(l.canvasBottom()>l.canvasTop()+100);
            assertTrue(l.recipeBodyHeight()>=40,"JEI body at "+size[0]+"x"+size[1]);
            assertTrue(l.panelRight()<l.contentRight());
        }
    }
    @Test void cachePagesReplaceVerticalOverflow() {
        var small=RouteLayout.of(480,270);
        assertTrue(small.collapseCache());assertEquals(20,small.cacheReservedHeight());
        var medium=RouteLayout.of(700,310);
        assertFalse(medium.collapseCache());assertEquals(1,medium.cacheRows());
        assertEquals(58,medium.cacheReservedHeight());
    }
    @Test void allStandardGuiScalesOfUsersWindowFit() {
        for(int scale=1;scale<=5;scale++) {
            var l=RouteLayout.of((2799+scale-1)/scale,(1230+scale-1)/scale);
            assertTrue(l.usable());assertTrue(l.recipeBodyHeight()>=40);
        }
    }
    @Test void genuineTinyWindowsHaveAnExplicitFallback() {
        assertFalse(RouteLayout.of(280,180).usable());
        assertThrows(IllegalArgumentException.class,()->RouteLayout.of(0,300));
    }
    @Test void fitCentersContentInsideTheViewport() {
        var fit=RouteLayout.fit(0,0,100,50,300,200);
        assertEquals(1.25F,fit.zoom());
        assertEquals(150F,(fit.panX()+50)*fit.zoom(),1F);
        assertEquals(100F,(fit.panY()+25)*fit.zoom(),1F);
    }
    @Test void fitShrinksADraggedTreeSoEveryNodeStaysVisible() {
        var fit=RouteLayout.fit(-20,-10,980,510,374,220);
        assertTrue(fit.zoom()<1F);
        assertEquals(12F,(fit.panX()-20)*fit.zoom(),1F);
        assertTrue((fit.panY()-10)*fit.zoom()>=11F);
        assertTrue((fit.panX()+980)*fit.zoom()<=374F);
        assertTrue((fit.panY()+510)*fit.zoom()<=220F);
    }
    @Test void fitClampsZoomForTinyAndEnormousTrees() {
        assertEquals(1.25F,RouteLayout.fit(0,0,8,8,300,200).zoom());
        assertEquals(0.25F,RouteLayout.fit(0,0,100000,100000,320,180).zoom());
    }
    @Test void fitHandlesASingleNodeWithoutDividingByZero() {
        var fit=RouteLayout.fit(5,5,5,5,300,200);
        assertEquals(1.25F,fit.zoom());
        assertEquals(150F,(fit.panX()+5)*fit.zoom(),1F);
        assertEquals(100F,(fit.panY()+5)*fit.zoom(),1F);
    }
    @Test void fitRejectsInvalidBoundsOrViewport() {
        assertThrows(IllegalArgumentException.class,()->RouteLayout.fit(10,0,0,10,300,200));
        assertThrows(IllegalArgumentException.class,()->RouteLayout.fit(0,0,0,0,0,200));
        assertThrows(IllegalArgumentException.class,()->RouteLayout.fit(0,0,0,0,300,-1));
    }
}
