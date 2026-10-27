package com.aecraftroute.addon.route.client;
import com.aecraftroute.addon.route.RouteView;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RouteStartupIndexTest {
    @Test void identicalNamesNeverAliasDifferentKeys() {
        var index=RouteStartupIndex.of(List.of(new RouteView.Stack("iron","同名",2),new RouteView.Stack("gold","同名",7)));
        assertEquals(2L,index.get("iron"));assertEquals(7L,index.get("gold"));assertNull(index.get("同名"));
    }
    @Test void duplicatesAggregateAndNonpositiveRowsAreIgnored() {
        var index=RouteStartupIndex.of(List.of(new RouteView.Stack("a","A",2),new RouteView.Stack("a","B",7),new RouteView.Stack("b","B",0)));
        assertEquals(9L,index.get("a"));assertFalse(index.containsKey("b"));
        assertThrows(UnsupportedOperationException.class,()->index.put("c",1L));
    }
    @Test void extremeCountsDoNotOverflow() {
        assertEquals(Long.MAX_VALUE,RouteStartupIndex.of(List.of(new RouteView.Stack("a","A",Long.MAX_VALUE),new RouteView.Stack("a","A",1))).get("a"));
        assertTrue(RouteStartupIndex.of(List.of()).isEmpty());
    }
    @Test void decodedIdentityCanNormalizeEquivalentSerializedKeys() {
        var index=RouteStartupIndex.of(List.of(new RouteView.Stack("IRON","a",2),new RouteView.Stack("iron","b",3)),
                stack->stack.key().toLowerCase(java.util.Locale.ROOT));
        assertEquals(java.util.Map.of("iron",5L),index);
    }
}
