package com.aecraftroute.addon.route;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

class RoutePolicyTest {
    RoutePolicy<String> empty() { return new RoutePolicy<>(Map.of()); }
    List<String> choices(RoutePolicy<String> p) { return p.filter("dust",List.of("grind","external","bulk"),s->s); }
    @Test void automaticKeepsAll() { assertEquals(3,choices(empty()).size()); }
    @Test void lockIsNotAPreference() { assertEquals(List.of("external"),choices(empty().change("dust","lock","external"))); }
    @Test void removedLockDoesNotFallBack() { assertTrue(choices(empty().change("dust","lock","removed")).isEmpty()); }
    @Test void banningOnePreservesAlternatives() { assertEquals(List.of("external","bulk"),choices(empty().change("dust","ban","grind"))); }
    @Test void banIsToggle() { assertEquals(3,choices(empty().change("dust","ban","grind").change("dust","ban","grind")).size()); }
    @Test void removingLastBanRestoresFullAutomaticEngine() { assertTrue(empty().change("dust","ban","grind").change("dust","ban","grind").rules().isEmpty()); }
    @Test void stockOnlyDisablesAllProduction() { assertTrue(choices(empty().change("dust","stock","")).isEmpty()); }
    @Test void restoreAutomaticClearsLockAndBan() { assertEquals(3,choices(empty().change("dust","ban","grind").change("dust","lock","bulk").change("dust","auto","")).size()); }
    @Test void banningLockedPatternUnlocksButKeepsBan() { var p=empty().change("dust","lock","external").change("dust","ban","external");assertNull(p.rule("dust").locked());assertFalse(choices(p).contains("external")); }
    @Test void independentRequestsDoNotMutatePreviousSnapshot() { var before=empty().change("dust","lock","external");var after=before.change("dust","lock","grind");assertEquals(List.of("external"),choices(before));assertEquals(List.of("grind"),choices(after)); }
    @Test void unrelatedKeyDoesNotComputeIdentity() { assertEquals(List.of("x"),empty().filter("other",List.of("x"),p->{throw new AssertionError();})); }
    @Test void deeplyImmutable() { var bans=new HashSet<>(Set.of("x"));var map=new HashMap<String,RoutePolicy.Rule>();map.put("a",new RoutePolicy.Rule(null,bans,false));var p=new RoutePolicy<>(map);bans.clear();map.clear();assertTrue(p.rule("a").banned().contains("x")); }
    @Test void rejectsUnknownActionsAndEmptyIdentity() { assertThrows(IllegalArgumentException.class,()->empty().change("dust","evil",""));assertThrows(IllegalArgumentException.class,()->empty().change("dust","lock","")); }
    @Test void nullQueryIsAutomatic() { assertEquals(RoutePolicy.Rule.automatic(),empty().rule(null)); }
    @Test void clearingOnlyCanvasPreservesMaterialRules() {
        var rules=empty().change("dust","lock","external");
        var forced=new RoutePolicy<>(rules.rules(),new RoutePolicy.Canvas<>("dust",4,List.of(new RoutePolicy.Path<>("root","dust","external"))));
        var cleared=forced.withoutCanvas();
        assertNull(cleared.canvas());
        assertEquals(rules.rules(),cleared.rules());
        assertEquals(List.of("external"),choices(cleared));
        assertSame(cleared,cleared.withoutCanvas());
    }
    @Test void boundsRuleCount() { var p=empty();for(int i=0;i<128;i++)p=p.change("k"+i,"stock","");var full=p;assertThrows(IllegalArgumentException.class,()->full.change("extra","stock","")); }
    @Test void manualErrorsRemainIdentifiableThroughFutureWrappers() { assertTrue(RoutePlanningException.contains(new CompletionException(new RoutePlanningException("missing"))));assertFalse(RoutePlanningException.contains(new IllegalStateException())); }
}
