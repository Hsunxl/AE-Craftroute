package com.ae2vm.addon.route;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouteProviderWindowTest {
    @Test void onlyCoreWithActualMergedInventoryIsVisible() {
        assertFalse(RouteProviderWindow.visible(true,false,0));
        assertFalse(RouteProviderWindow.visible(true,false,512));
        assertFalse(RouteProviderWindow.visible(true,true,0));
        assertFalse(RouteProviderWindow.visible(false,true,512));
        assertTrue(RouteProviderWindow.visible(true,true,512));
    }
    @Test void completeMultiblockInventoryRemainsOneContiguousSpace() {
        var first=RouteProviderWindow.of(512,9,4,0);
        var next=RouteProviderWindow.of(512,9,4,1);
        assertEquals(36,first.end());assertEquals(first.end(),next.first());assertEquals(15,first.pages());
    }
    @Test void lastWindowDoesNotInventSlots() {
        var last=RouteProviderWindow.of(512,9,4,99);
        assertEquals(14,last.page());assertEquals(504,last.first());assertEquals(512,last.end());
    }
    @Test void scrollingBeforeFirstDoesNotWrapToLast() {assertEquals(0,RouteProviderWindow.of(512,9,4,-1).page());}
    @Test void smallProvidersShowAllNineRealSlots() {
        var w=RouteProviderWindow.of(9,9,4,0);assertEquals(9,w.end());assertEquals(1,w.pages());
    }
    @Test void rowsAreBoundedAndOldViewRequestsStillDecode() {
        assertEquals(1,new RouteWorkbench.ViewRequest(0,0,8).rows());
        assertEquals(1,new RouteWorkbench.ViewRequest(0,0,8,0).rows());
        assertThrows(IllegalArgumentException.class,()->new RouteWorkbench.ViewRequest(0,0,8,13));
    }
}
