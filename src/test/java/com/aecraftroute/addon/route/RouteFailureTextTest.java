package com.aecraftroute.addon.route;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouteFailureTextTest {
    @Test void arithmeticOverflowNeverBecomesAnullErrorInCanvasReply() {
        assertEquals("数量超出整数范围",RouteFailureText.message(new ArithmeticException()));
        assertEquals("数量超出整数范围",RouteFailureText.message(
                new IllegalArgumentException("wrapped",new ArithmeticException())));
    }
    @Test void usefulRootCauseSurvivesButMissingMessagesGetAType() {
        assertEquals("样板已失效",RouteFailureText.message(
                new IllegalArgumentException("wrapper",new IllegalStateException("样板已失效"))));
        assertEquals("IllegalArgumentException",RouteFailureText.message(new IllegalArgumentException()));
    }
}
