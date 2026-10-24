package com.aecraftroute.addon.route.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoutePopupPageTest {
    @Test void sixEntriesDoNotOverlapWhenFiveFit() {
        var first = RoutePopupPage.of(6, 5, 0);
        var last = first.move(6, 1);
        assertEquals("1–5 / 6", first.range(6));
        assertEquals("6–6 / 6", last.range(6));
        assertEquals(first.end(), last.start());
        assertEquals(1, last.rows());
        assertEquals(last, last.move(6, 1));
        assertEquals(first, last.move(6, -1));
    }
    @Test void scrollAndButtonsAdvanceWholePages() {
        var page = RoutePopupPage.of(16, 5, 0);
        for (int i = 1; i < 4; i++) {
            page = page.move(16, 1);
            assertEquals(i * 5, page.start());
        }
        assertEquals("16–16 / 16", page.range(16));
    }
    @Test void emptyPickerHasNoRowsAndCannotAdvance() {
        var empty = RoutePopupPage.of(0, 0, 100);
        assertEquals(0, empty.rows());
        assertEquals(1, empty.capacity());
        assertEquals(0, empty.move(0, 1).page());
        assertEquals("无配方", empty.range(0));
    }
    @Test void resizeAndShrinkingResultsClampByPageNotLastFullRow() {
        assertEquals("6–6 / 6", RoutePopupPage.of(6, 5, 999).range(6));
        assertEquals("6–6 / 6", RoutePopupPage.of(6, 1, 999).range(6));
        assertEquals(1, RoutePopupPage.capacity(12, 42, 5));
        assertEquals(5, RoutePopupPage.capacity(200, 42, 5));
        assertEquals("2147483646–2147483647 / 2147483647",
                RoutePopupPage.of(Integer.MAX_VALUE, 5, Integer.MAX_VALUE).range(Integer.MAX_VALUE));
    }
}
