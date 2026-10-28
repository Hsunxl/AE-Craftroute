package com.aecraftroute.addon.route;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouteRequestRulesTest {
    @Test void backgroundRefreshMustNotReplaceStatusWithWaiting() {
        for(var action:new String[]{"query","begin","workbench","preview"})assertFalse(RouteRequestRules.mutation(action));
    }
    @Test void actualOperationsAwaitServerConfirmation() {
        for(var action:new String[]{"encode","takecache","upload","restore","lock","replan"})assertTrue(RouteRequestRules.mutation(action));
    }
    @Test void requestAmountsOutsideThePlanRangeAreRefusedBeforePlanning() {
        assertEquals(1,RouteRequestRules.requirePlanAmount(1));
        assertEquals(RouteGraphMath.MAX_DEMAND,RouteRequestRules.requirePlanAmount(RouteGraphMath.MAX_DEMAND));
        assertEquals("目标数量必须大于零",assertThrows(IllegalArgumentException.class,()->RouteRequestRules.requirePlanAmount(0)).getMessage());
        // A packet is not the client field: Long.MAX_VALUE must be refused here, not deep
        // inside the canvas planner multiplyExact where it can only surface as a generic failure.
        var tooLarge=assertThrows(IllegalArgumentException.class,
                ()->RouteRequestRules.requirePlanAmount(RouteGraphMath.MAX_DEMAND+1));
        assertTrue(tooLarge.getMessage().contains("目标数量超过上限"),tooLarge.getMessage());
        assertThrows(IllegalArgumentException.class,()->RouteRequestRules.requirePlanAmount(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()->RouteRequestRules.requirePlanAmount(-1));
    }
    @Test void pendingOperationsExpireRatherThanBlockingForever() {
        assertFalse(RouteRequestRules.expired(300,100));assertTrue(RouteRequestRules.expired(301,100));
        assertFalse(RouteRequestRules.expiredApply(1000,100));assertTrue(RouteRequestRules.expiredApply(1001,100));
    }
    @Test void olderSequenceNeverReplacesANewerPreviewEvenForTheSameGoal() {
        assertFalse(RouteRequestRules.currentPlanResponse(4,5,"dust:1000","dust:1000"));
        assertTrue(RouteRequestRules.currentPlanResponse(5,5,"dust:1000","dust:1000"));
    }
    @Test void changingTargetOrAmountDiscardsResponsesBeforeAReplacementWasSent() {
        assertFalse(RouteRequestRules.currentPlanResponse(5,5,"dust:1000","seed:1000"));
        assertFalse(RouteRequestRules.currentPlanResponse(5,5,"dust:1000","dust:1"));
        assertFalse(RouteRequestRules.currentPlanResponse(5,5,"dust:1000",null));
    }
}
