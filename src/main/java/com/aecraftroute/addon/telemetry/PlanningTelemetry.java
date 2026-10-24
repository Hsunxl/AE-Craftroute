package com.aecraftroute.addon.telemetry;

import com.aecraftroute.addon.CraftRouteAddon;
import com.aecraftroute.addon.config.CraftRouteConfig;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/** Observes the original future without replacing it or altering cancellation. */
public final class PlanningTelemetry {
    private static final AtomicLong REQUESTS = new AtomicLong();

    public static <T> CompletableFuture<T> observe(CompletableFuture<T> calculation, long started,
            Object target, long amount, boolean constrained) {
        if (!CraftRouteConfig.isDebugLogging()) return calculation;
        long request = REQUESTS.incrementAndGet();
        calculation.whenComplete((plan, error) -> CraftRouteAddon.LOGGER.info(
                "[AE-Craftroute] calculation #{} {}: target={} amount={} constrained={} elapsedMs={}",
                request, outcome(error), target, amount, constrained, (System.nanoTime() - started) / 1_000_000.0));
        return calculation;
    }

    public static String outcome(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null)
            error = error.getCause();
        return error == null ? "completed" : error instanceof CancellationException ? "cancelled"
                : "failed:" + error.getClass().getSimpleName() + ":" + error.getMessage();
    }

    private PlanningTelemetry() {}
}
