package com.aecraftroute.addon.route;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class RouteProviderFilterTest {
    private RouteProviderFilter.Metadata metadata(int id) {
        return new RouteProviderFilter.Metadata("供应器 "+id,id<50?"ae2":"advanced_ae","ae2:pattern_provider",id+",64,-12 / "+id+" 64 -12");
    }
    @Test void searchesAllOneHundredProvidersBeforePaging() {
        var all=IntStream.range(0,100).boxed().toList();
        var result=RouteProviderFilter.select(all,this::metadata,"供应器 99",0,1);
        assertEquals(List.of(99),result.items());assertEquals(1,result.total());assertEquals(1,result.pages());assertEquals(0,result.page());
    }
    @Test void filteredCountAndLastPageComeFromMatchesOnly() {
        var all=IntStream.range(0,100).boxed().toList();
        var result=RouteProviderFilter.select(all,this::metadata,"ADVANCED_AE",999,7);
        assertEquals(50,result.total());assertEquals(8,result.pages());assertEquals(7,result.page());assertEquals(List.of(99),result.items());
    }
    @Test void emptyQueryIncludesEverythingWithoutReadingMetadataOrInventories() {
        var all=IntStream.range(0,100).boxed().toList();
        var result=RouteProviderFilter.select(all,id->{throw new AssertionError("empty query must not inspect provider metadata");},"  ",3,1);
        assertEquals(List.of(3),result.items());assertEquals(100,result.total());assertEquals(100,result.pages());
    }
    @Test void noMatchesHasPageZeroAndZeroPages() {
        var result=RouteProviderFilter.select(List.of(1,2),this::metadata,"不存在的设备",99,1);
        assertTrue(result.items().isEmpty());assertEquals(0,result.page());assertEquals(0,result.pages());assertEquals(0,result.total());
    }
    @Test void matchesNameModIdItemIdAndAvailableCoordinateMetadata() {
        var meta=metadata(99);
        assertTrue(RouteProviderFilter.matches(meta,"  供应器 99  "));
        assertTrue(RouteProviderFilter.matches(meta," Advanced_AE "));
        assertTrue(RouteProviderFilter.matches(meta,"AE2:PATTERN_PROVIDER"));
        assertTrue(RouteProviderFilter.matches(meta,"99,64,-12"));assertTrue(RouteProviderFilter.matches(meta,"99 64 -12"));
        assertFalse(RouteProviderFilter.matches(meta,"missing"));
    }
    @Test void nullAndUnknownMetadataAreSafe() {
        assertTrue(RouteProviderFilter.matches(null,null));assertFalse(RouteProviderFilter.matches(null,"ae2"));
        assertFalse(RouteProviderFilter.matches(new RouteProviderFilter.Metadata(null,null,null,null),"ae2"));
    }
    @Test void queryUsesRootCaseFoldingNotTurkishDefaultLocale() {
        Locale old=Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertTrue(RouteProviderFilter.matches(new RouteProviderFilter.Metadata("IRON",null,null,null)," iron "));
        } finally {Locale.setDefault(old);}
    }
    @Test void maximumQueryLengthIsBoundedBeforeSearch() {
        assertEquals(128,RouteProviderFilter.normalize("a".repeat(128)).length());
        assertThrows(IllegalArgumentException.class,()->RouteProviderFilter.normalize("a".repeat(129)));
        // ROOT case folding can expand a code point; it must not be revalidated as new input per provider.
        assertDoesNotThrow(()->RouteProviderFilter.select(List.of(1),this::metadata,"İ".repeat(128),0,1));
    }
}
