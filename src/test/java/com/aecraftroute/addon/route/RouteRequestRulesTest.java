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
    @Test void pendingOperationsExpireRatherThanBlockingForever() {
        assertFalse(RouteRequestRules.expired(300,100));assertTrue(RouteRequestRules.expired(301,100));
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
