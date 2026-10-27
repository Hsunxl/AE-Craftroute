package com.aecraftroute.addon.route;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouteProviderSearchTest {
    @Test void rapidTypingWaitsForAFullQuietInterval() {
        var search=new RouteProviderSearch();long delay=RouteProviderSearch.DELAY_NANOS;
        search.changed("a",0);search.changed("ad",delay-1);search.changed("advanced",delay);
        assertFalse(search.due(2*delay-1));assertTrue(search.due(2*delay));
    }
    @Test void pendingRequestRejectionRetainsDirtyAndRetriesOnlyAfterTheDelay() {
        var search=new RouteProviderSearch();long delay=RouteProviderSearch.DELAY_NANOS;
        search.changed("供应器 99",0);assertTrue(search.due(delay));
        search.attempted("供应器 99",false,delay);
        assertTrue(search.dirty());assertFalse(search.matchesSubmitted("供应器 99"));
        assertFalse(search.due(delay+1));assertFalse(search.due(2*delay-1));assertTrue(search.due(2*delay));
        search.attempted("供应器 99",true,2*delay);
        assertFalse(search.dirty());assertFalse(search.due(100*delay));assertTrue(search.matchesSubmitted("供应器 99"));
    }
    @Test void clearIsANewSearchAndCaseWhitespaceOnlyEditsAreEquivalent() {
        var search=new RouteProviderSearch();long delay=RouteProviderSearch.DELAY_NANOS;
        search.attempted(" Advanced_AE ",true,0);
        search.changed("ADVANCED_AE",0);assertFalse(search.dirty());
        search.changed("",1);assertFalse(search.due(delay));assertTrue(search.due(delay+1));
        assertFalse(search.matchesSubmitted(""));search.attempted("",true,delay+1);assertTrue(search.matchesSubmitted(" "));
    }
    @Test void rejectedRefreshAlsoRetriesEvenWithTheSameFilter() {
        var search=new RouteProviderSearch();long delay=RouteProviderSearch.DELAY_NANOS;
        search.attempted("",false,0);assertTrue(search.due(delay));
        search.attempted("",true,delay);assertFalse(search.dirty());
    }
}
