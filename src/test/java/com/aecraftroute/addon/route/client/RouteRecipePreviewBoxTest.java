package com.aecraftroute.addon.route.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RouteRecipePreviewBoxTest {
    @Test void previewIsAdjacentToIconWithNoDeadGap() {
        var p=RouteRecipePreviewBox.of(100,50,18,854,480);
        assertEquals(118,p.x());assertEquals(50,p.y());
        assertTrue(p.contains(118,50));assertFalse(p.contains(p.x()+p.width(),50));
    }
    @Test void rightEdgeFallsBackToLeftAndBottomIsClamped() {
        var p=RouteRecipePreviewBox.of(800,470,18,854,480);
        assertEquals(540,p.x());assertTrue(p.y()+p.height()<=476);
    }
    @Test void allCanvasZoomAnchorSizesRemainOnScreen() {
        for(int sw:new int[]{320,427,700,854,1280})for(int sh:new int[]{200,240,310,480})
            for(int aw:new int[]{5,9,18,36})for(int ax:new int[]{0,sw/2,sw-20}) {
                var p=RouteRecipePreviewBox.of(ax,sh-20,aw,sw,sh);
                assertTrue(p.x()>=4 && p.y()>=4);
                assertTrue(p.x()+p.width()<=sw-4 && p.y()+p.height()<=sh-4);
            }
    }
}
