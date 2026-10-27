package com.aecraftroute.addon.route.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RouteAnchorTest {
    @Test void relayoutKeepsScreenPositionAtEveryZoom() {
        var anchor=new RouteAnchor("root/a","iron",75,200);
        var pan=anchor.restore("iron",300,50,40,-90);
        for(float zoom:new float[]{0.25F,0.7F,1F,2F}) {
            assertEquals((75+40)*zoom,(300+pan.x())*zoom,0.001);
            assertEquals((200-90)*zoom,(50+pan.y())*zoom,0.001);
        }
    }
    @Test void replacedMaterialAtSamePathKeepsPan() {
        assertEquals(new RouteAnchor.Pan(4,5),new RouteAnchor("root/a","iron",8,9).restore("gold",100,200,4,5));
    }
    @Test void repeatedRebuildDoesNotDrift() {
        assertEquals(new RouteAnchor.Pan(-500,200),new RouteAnchor("root","iron",8,9).restore("iron",8,9,-500,200));
    }
}
