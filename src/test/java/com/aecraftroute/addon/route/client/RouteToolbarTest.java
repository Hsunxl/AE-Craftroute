package com.aecraftroute.addon.route.client;
import com.aecraftroute.addon.route.RouteLayout;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RouteToolbarTest {
    @Test void mappingReloadFitsTheExpandedWorkspace() {
        for(int width:new int[]{320,400,504,630,1056})for(int count:new int[]{8,9}) {
            var cells=RouteToolbar.of(width-16,count);
            assertTrue(cells.buttonWidth()>=26);
            assertTrue((count-1)*cells.cellWidth()+cells.buttonWidth()<=width-16);
        }
    }
    @Test void actionsFitAllSupportedWindowWidths() {
        for(int width:new int[]{320,400,640,854,1280})for(int count:new int[]{7,8}) {
            int available=RouteLayout.of(width,240).contentRight()-16;
            var cells=RouteToolbar.of(available,count);
            assertTrue(cells.buttonWidth()>=26);
            assertTrue((count-1)*cells.cellWidth()+cells.buttonWidth()<=available);
        }
    }
    @Test void invalidSizesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->RouteToolbar.of(5,0));
        assertThrows(IllegalArgumentException.class,()->RouteToolbar.of(1,8));
    }
}
