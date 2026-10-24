package com.aecraftroute.addon.telemetry;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryTest {
    @Test void firstEventIsVisibleReasonsAndOwnersAreIndependent() {
        var now = new AtomicLong(10);
        var a = new LogThrottle(30,now::get);
        var b = new LogThrottle(30,now::get);
        assertTrue(a.acquire("blocked"));
        assertFalse(a.acquire("blocked"));
        assertTrue(a.acquire("missing"));
        assertTrue(b.acquire("blocked"));
        now.addAndGet(29); assertFalse(a.acquire("blocked"));
        now.incrementAndGet(); assertTrue(a.acquire("blocked"));
        a.reset(); assertTrue(a.acquire("blocked"));
    }
    @Test void waitingGetsGraceIntervalAndMonotonicClockWrapsSafely() {
        var now = new AtomicLong(Long.MAX_VALUE-10);
        var throttle = new LogThrottle(30,now::get);
        assertFalse(throttle.acquireDelayed("waiting"));
        now.addAndGet(29); assertFalse(throttle.acquireDelayed("waiting"));
        now.incrementAndGet(); assertTrue(throttle.acquireDelayed("waiting"));
    }
    @Test void cancellationAndFailureRemainDistinctAcrossAsyncWrapping() {
        assertEquals("completed",PlanningTelemetry.outcome(null));
        assertEquals("cancelled",PlanningTelemetry.outcome(new CompletionException(new ExecutionException(new CancellationException()))));
        assertEquals("failed:IllegalStateException:budget",PlanningTelemetry.outcome(new CompletionException(new IllegalStateException("budget"))));
    }
    @Test void observationDoesNotReplaceFutureOrBreakCancellation() {
        var calculation = new CompletableFuture<String>();
        assertSame(calculation,PlanningTelemetry.observe(calculation,System.nanoTime(),"target",1,false));
        assertTrue(calculation.cancel(true)); assertTrue(calculation.isCancelled());
        var error = new IllegalArgumentException("invalid");
        var failed = CompletableFuture.<String>failedFuture(error);
        assertSame(failed,PlanningTelemetry.observe(failed,System.nanoTime(),"target",1,false));
        assertSame(error,assertThrows(CompletionException.class,failed::join).getCause());
    }
}
