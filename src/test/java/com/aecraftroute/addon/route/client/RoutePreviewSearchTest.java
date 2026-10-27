package com.aecraftroute.addon.route.client;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RoutePreviewSearchTest {
    @Test void spreadsExpensiveCandidatesAcrossTicksAndWaitsForUniqueness() {
        var search=new RoutePreviewSearch();var calls=new AtomicInteger();
        var first=search.scan("iron",9,4,i->{calls.incrementAndGet();return i==1?"recipe":null;});
        assertEquals(new RoutePreviewSearch.Result(true,null),first);
        assertEquals(4,calls.get());
        var second=search.scan("iron",9,4,i->{calls.incrementAndGet();return null;});
        assertEquals(new RoutePreviewSearch.Result(true,null),second);
        assertEquals(8,calls.get());
        var third=search.scan("iron",9,4,i->{calls.incrementAndGet();return null;});
        assertEquals(new RoutePreviewSearch.Result(false,"recipe"),third);
        assertEquals(9,calls.get());
    }
    @Test void ambiguityNeverPublishesAnArbitraryFirstMatch() {
        var search=new RoutePreviewSearch();var calls=new AtomicInteger();
        assertTrue(search.scan("iron",12,4,i->{calls.incrementAndGet();return i==0?"first":null;}).pending());
        var ambiguous=search.scan("iron",12,4,i->{calls.incrementAndGet();return i==5?"second":null;});
        assertEquals(new RoutePreviewSearch.Result(false,null),ambiguous);
        assertEquals(6,calls.get());
    }
    @Test void newChoiceAndExplicitClearRestartTheScan() {
        var search=new RoutePreviewSearch();
        assertTrue(search.scan("old",5,4,i->null).pending());
        assertEquals(new RoutePreviewSearch.Result(false,"new"),search.scan("new",1,4,i->"new"));
        search.clear();
        assertEquals(new RoutePreviewSearch.Result(false,null),search.scan("new",0,4,i->fail("no candidates")));
    }
}
