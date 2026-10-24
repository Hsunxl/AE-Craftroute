package com.aecraftroute.addon.planner;

import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Observing a calculation must not sever requester -> worker cancellation. */
public final class PlanningFutures {
    public static <T> CompletableFuture<T> observe(CompletableFuture<T> source,
            BiConsumer<? super T,? super Throwable> observer) {
        var result=new CompletableFuture<T>() {
            @Override public boolean cancel(boolean interrupt) {
                boolean changed=super.cancel(interrupt);
                if(changed) source.cancel(interrupt);
                return changed;
            }
        };
        source.whenComplete((value,error)-> {
            if(error instanceof CancellationException) { result.completeExceptionally(error); return; }
            // Logging/UI failures are not planning failures and must not discard a proof.
            try { observer.accept(value,error); } catch(RuntimeException ignored) { }
            if(error==null) result.complete(value); else result.completeExceptionally(error);
        });
        return result;
    }
    private PlanningFutures() {}
}
