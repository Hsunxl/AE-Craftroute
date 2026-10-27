package com.aecraftroute.addon.route.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.aecraftroute.addon.route.client.RouteWheelIntent.*;
class RouteWheelIntentTest {
    @Test void defaultAlwaysZoomsEvenOverNodesAndWithControl() {
        for(boolean node:new boolean[]{false,true})for(boolean ctrl:new boolean[]{false,true})
            assertEquals(ZOOM,of(ctrl,false,false,node,false,false));
    }
    @Test void shiftPansEvenOverNodes() {
        assertEquals(PAN,of(false,true,false,true,false,false));
        assertEquals(PAN,of(true,true,false,false,false,false));
    }
    @Test void altEditsAndAltShiftStillAllowsSingleUnits() {
        assertEquals(QUANTITY,of(false,false,true,true,false,false));
        assertEquals(QUANTITY,of(false,true,true,true,false,false));
        assertEquals(ZOOM,of(false,false,true,false,false,false));
    }
    @Test void horizontalGesturePans() {
        for(boolean node:new boolean[]{false,true})assertEquals(PAN,of(false,false,false,node,true,false));
    }
    @Test void compatibilityRetainsControlAndShiftQuantity() {
        assertEquals(ZOOM,of(true,true,true,true,false,true));
        assertEquals(QUANTITY,of(false,false,false,true,false,true));
        assertEquals(QUANTITY,of(false,true,false,true,false,true));
        assertEquals(PAN,of(false,false,false,false,false,true));
    }
}
