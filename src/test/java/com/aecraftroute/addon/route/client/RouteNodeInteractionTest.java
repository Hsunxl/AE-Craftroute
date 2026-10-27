package com.aecraftroute.addon.route.client;

import org.junit.jupiter.api.Test;
import static com.aecraftroute.addon.route.client.RouteNodeInteraction.Action.*;
import static org.junit.jupiter.api.Assertions.*;

class RouteNodeInteractionTest {
    @Test void recipeSelectionAndDeletionHaveSeparateGestures() {
        assertEquals(RECIPES, RouteNodeInteraction.press(1, false));
        assertEquals(RECIPES, RouteNodeInteraction.press(0, true));
        assertEquals(DRAG, RouteNodeInteraction.press(0, false));
        assertEquals(REMOVE, RouteNodeInteraction.release(false, false));
        assertEquals(QUANTITY, RouteNodeInteraction.press(2, true));
        assertEquals(NONE, RouteNodeInteraction.press(3, false));
    }
    @Test void machineClickSurvivesButDragNeverOpensEitherMenu() {
        assertEquals(MACHINE, RouteNodeInteraction.release(false, true));
        assertEquals(NONE, RouteNodeInteraction.release(true, true));
        assertEquals(NONE, RouteNodeInteraction.release(true, false));
    }
    @Test void outAndBackMotionStaysADrag() {
        boolean moved = RouteNodeInteraction.moved(false, 5, 0);
        assertTrue(RouteNodeInteraction.moved(moved, 0, 0));
        assertFalse(RouteNodeInteraction.moved(false, 1, 1));
        assertTrue(RouteNodeInteraction.moved(false, 0, 4));
    }
}
