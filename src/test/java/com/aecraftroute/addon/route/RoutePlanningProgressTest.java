package com.aecraftroute.addon.route;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoutePlanningProgressTest {
    private RoutePlanningProgress p(long generation,long revision,boolean terminal) {
        return new RoutePlanningProgress(generation,revision,"search",0,terminal);
    }
    @Test void rejectsOldAndDuplicatePackets() {
        assertFalse(p(1,99,false).supersedes(p(2,1,false)));
        assertFalse(p(2,1,false).supersedes(p(2,1,false)));
        assertTrue(p(2,2,false).supersedes(p(2,1,false)));
    }
    @Test void terminalCannotBeResurrectedButNewRequestCanStart() {
        assertFalse(p(2,99,false).supersedes(p(2,2,true)));
        assertTrue(p(3,0,false).supersedes(p(2,2,true)));
        assertTrue(p(0,0,false).supersedes(null));
    }
}
