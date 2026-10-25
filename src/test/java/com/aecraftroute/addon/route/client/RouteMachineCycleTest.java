package com.aecraftroute.addon.route.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RouteMachineCycleTest {
    @Test void rotationRetainsThreeSecondCycleAfterMovingTheIcon() {
        assertEquals(0,RouteMachineCycle.index(59,3,-1,false));assertEquals(1,RouteMachineCycle.index(60,3,-1,false));
        assertEquals(2,RouteMachineCycle.index(120,3,-1,false));assertEquals(0,RouteMachineCycle.index(180,3,-1,false));
    }
    @Test void hoverFreezesCandidateButStaleCandidateCannotSurviveNewChoices() {
        assertEquals(2,RouteMachineCycle.index(999,3,2,true));assertEquals(1,RouteMachineCycle.index(60,2,8,true));
        assertEquals(-1,RouteMachineCycle.index(60,0,0,true));
    }
}
