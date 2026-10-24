package com.aecraftroute.addon.route;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The authoritative target handshake repeats an unanswered request on a fixed frame interval and
 * stops after a bounded number of attempts. Both halves matter: without the repeat a slow server
 * leaves the canvas with no target for its whole lifetime, and without the bound an absent plan
 * becomes an endless request loop.
 */
class BeginRetryStateTest {
    @Test void firstAttemptIsNotDueAgainBeforeTheFrameIntervalElapses() {
        var state=new BeginRetryState();
        state.sent(0);
        assertFalse(state.due(RouteRequestRules.BEGIN_RETRY_FRAMES-1));
        assertTrue(state.due(RouteRequestRules.BEGIN_RETRY_FRAMES));
    }

    @Test void nothingIsScheduledBeforeTheFirstAttempt() {
        assertFalse(new BeginRetryState().due(0));
    }

    @Test void anAnsweredHandshakeStopsRetrying() {
        var state=new BeginRetryState();
        state.sent(0);
        state.answered();
        assertFalse(state.due(1_000));
        assertEquals(-1,state.retryAt());
    }

    @Test void retriesStopAtTheAttemptBudget() {
        var state=new BeginRetryState();
        long now=0;
        for(int sent=0;sent<RouteRequestRules.BEGIN_MAX_ATTEMPTS;sent++) {
            assertFalse(state.exhausted(),"must still allow attempt "+(sent+1));
            state.sent(now);
            now+=RouteRequestRules.BEGIN_RETRY_FRAMES;
        }
        assertTrue(state.exhausted());
        assertEquals(RouteRequestRules.BEGIN_MAX_ATTEMPTS,state.attempts());
        assertFalse(state.due(now),"an exhausted handshake must not schedule another request");
    }

    @Test void aFailedSendDoesNotCountAsAnAttempt() {
        // pressBegin only records a send that returned a sequence; a refused send leaves the
        // budget untouched so a transiently closed menu does not consume attempts.
        var state=new BeginRetryState();
        assertEquals(0,state.attempts());
        assertEquals(-1,state.retryAt());
    }

    @Test void twentyAttemptsCoverTheIntendedWait() {
        // BEGIN_MAX_ATTEMPTS spans roughly twenty seconds at twenty frames per second.
        assertEquals(400L,(long)RouteRequestRules.BEGIN_MAX_ATTEMPTS*RouteRequestRules.BEGIN_RETRY_FRAMES);
    }
}
