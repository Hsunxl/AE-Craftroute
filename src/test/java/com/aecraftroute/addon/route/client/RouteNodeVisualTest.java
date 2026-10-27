package com.aecraftroute.addon.route.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RouteNodeVisualTest {
    @Test void unprovenNeverMeansMissing() {
        for(boolean stock:new boolean[]{false,true}) {
            var visual=RouteNodeVisual.of(false,stock,false);
            assertTrue(visual.label().contains("未证明"));
            assertFalse(visual.label().contains("缺"));
        }
    }
    @Test void feasibleMeansPlanNotStockCoverage() {
        assertEquals("计划已证明",RouteNodeVisual.of(true,false,false).label());
        assertEquals("仅用库存·计划可行",RouteNodeVisual.of(true,true,false).label());
        assertNotEquals(RouteNodeVisual.of(false,false,false).badgeBackground(),RouteNodeVisual.of(true,false,false).badgeBackground());
    }
    @Test void localOverrideIsNeverCertifiedByServerPlan() {
        assertEquals("本地编辑·未证明",RouteNodeVisual.of(true,false,true).label());
        assertEquals(RouteNodeVisual.of(false,true,true),RouteNodeVisual.of(true,true,true));
    }
}
