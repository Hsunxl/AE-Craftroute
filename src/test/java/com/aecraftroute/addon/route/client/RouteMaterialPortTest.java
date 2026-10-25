package com.aecraftroute.addon.route.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RouteMaterialPortTest {
    @Test void portStaysInsideTheMaterialAndFollowsIt() {
        for(int width:new int[]{86,100,140}) {
            var p=RouteMaterialPort.of(50,80,width);assertEquals(50+width-22,p.x());assertEquals(84,p.y());
            assertTrue(p.x()+18<=50+width);assertTrue(p.y()+18<=80+62);
            var moved=RouteMaterialPort.of(90,130,width);assertEquals(40,moved.x()-p.x());assertEquals(50,moved.y()-p.y());
        }
    }
    @Test void iconHitAreaDoesNotIncludeAnImaginaryMachineBelowTheNode() {
        var p=RouteMaterialPort.of(0,0,86);assertTrue(p.contains(p.x(),p.y()));
        assertFalse(p.contains(p.x()+18,p.y()));assertFalse(p.contains(43,90));
    }
}
