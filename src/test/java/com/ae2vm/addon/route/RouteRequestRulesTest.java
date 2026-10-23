package com.ae2vm.addon.route;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouteRequestRulesTest {
    @Test void backgroundRefreshMustNotReplaceStatusWithWaiting() {
        for(var action:new String[]{"query","begin","workbench"})assertFalse(RouteRequestRules.mutation(action));
    }
    @Test void actualOperationsAwaitServerConfirmation() {
        for(var action:new String[]{"encode","takecache","upload","restore","lock","replan"})assertTrue(RouteRequestRules.mutation(action));
    }
    @Test void pendingOperationsExpireRatherThanBlockingForever() {
        assertFalse(RouteRequestRules.expired(300,100));assertTrue(RouteRequestRules.expired(301,100));
    }
}
