package com.aecraftroute.addon.telemetry;

import java.util.concurrent.atomic.AtomicLong;

/** Limits repeated CPU_OFFLINE diagnostics without affecting submission results. */
public final class CpuOfflineLogThrottle {
    private final long intervalNanos;
    private final AtomicLong lastNanos = new AtomicLong();

    public CpuOfflineLogThrottle(long intervalNanos) {
        if (intervalNanos <= 0) throw new IllegalArgumentException("intervalNanos");
        this.intervalNanos = intervalNanos;
    }

    public boolean shouldLog(long nowNanos) {
        long previous = lastNanos.get();
        if (previous != 0 && nowNanos - previous < intervalNanos) return false;
        return lastNanos.compareAndSet(previous, nowNanos);
    }
}
