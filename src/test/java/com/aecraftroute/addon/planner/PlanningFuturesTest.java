package com.aecraftroute.addon.planner;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PlanningFuturesTest {
    @Test void requesterCancellationReachesTheWorkerWithInterruptFlag() {
        var interrupted=new AtomicBoolean();
        var source=new CompletableFuture<String>() {
            @Override public boolean cancel(boolean interrupt) { interrupted.set(interrupt); return super.cancel(interrupt); }
        };
        var view=PlanningFutures.observe(source,(value,error)->fail("Cancellation is not an error notification"));
        assertTrue(view.cancel(true)); assertTrue(source.isCancelled()); assertTrue(interrupted.get());
    }
    @Test void workerCancellationPropagatesAndDoesNotNotifyFailure() {
        var source=new CompletableFuture<String>();
        var view=PlanningFutures.observe(source,(value,error)->fail("Canceled calculation"));
        source.cancel(false); assertTrue(view.isCancelled());
    }
    @Test void observerCannotLoseVerifiedPlanOrReplaceTheOriginalFailure() {
        assertEquals("proof",PlanningFutures.observe(CompletableFuture.completedFuture("proof"),(v,e)->{throw new IllegalStateException();}).join());
        var failure=new IllegalArgumentException("unreachable");
        var view=PlanningFutures.observe(CompletableFuture.failedFuture(failure),(v,e)->{throw new IllegalStateException();});
        assertSame(failure,assertThrows(CompletionException.class,view::join).getCause());
    }
}
