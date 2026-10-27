package com.aecraftroute.addon.route.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RouteMachineCycleTest {
    @Test void firstFrameWithImmutableCandidatesAndNoCachedIconDoesNotCrash() {
        for(var values:java.util.List.of(java.util.List.of("provider"),java.util.List.of("a","b"),java.util.List.of("a","b","c"))) {
            assertEquals(0,RouteMachineCycle.index(0,values,null,false));
            assertEquals(0,RouteMachineCycle.index(0,values,null,true));
        }
        assertEquals(-1,RouteMachineCycle.index(60,java.util.List.of(),null,true));
    }
    @Test void immutableCandidatesPreserveHoverIdentityAndRecoverStaleCache() {
        var values=java.util.List.of("a","b");
        assertEquals(0,RouteMachineCycle.index(60,values,"a",true));
        assertEquals(1,RouteMachineCycle.index(60,values,"a",false));
        assertEquals(1,RouteMachineCycle.index(60,values,"removed",true));
    }
    @Test void rotationRetainsThreeSecondCycleAfterMovingTheIcon() {
        assertEquals(0,RouteMachineCycle.index(59,3,-1,false));assertEquals(1,RouteMachineCycle.index(60,3,-1,false));
        assertEquals(2,RouteMachineCycle.index(120,3,-1,false));assertEquals(0,RouteMachineCycle.index(180,3,-1,false));
    }
    @Test void hoverFreezesCandidateButStaleCandidateCannotSurviveNewChoices() {
        assertEquals(2,RouteMachineCycle.index(999,3,2,true));assertEquals(1,RouteMachineCycle.index(60,2,8,true));
        assertEquals(-1,RouteMachineCycle.index(60,0,0,true));
    }
}
