package com.aecraftroute.addon.telemetry;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Per-owner, per-reason monotonic throttle. Not persisted; first event is visible.
 * Thread-confined to the owning server-side CPU tick; do not share across threads.
 */
public final class LogThrottle {
    private final Map<String, Long> last = new HashMap<>();
    private final long intervalNanos;
    private final LongSupplier clock;

    public LogThrottle() { this(30_000_000_000L, System::nanoTime); }
    public LogThrottle(long intervalNanos, LongSupplier clock) {
        if (intervalNanos <= 0) throw new IllegalArgumentException("Interval must be positive");
        this.intervalNanos = intervalNanos;
        this.clock = java.util.Objects.requireNonNull(clock);
    }
    public boolean acquire(String reason) {
        long now = clock.getAsLong();
        Long previous = last.get(reason);
        if (previous != null && now - previous < intervalNanos) return false;
        last.put(reason, now);
        return true;
    }
    /** Normal asynchronous returns get a grace interval before the first warning. */
    public boolean acquireDelayed(String reason) {
        if (!last.containsKey(reason)) { last.put(reason,clock.getAsLong()); return false; }
        return acquire(reason);
    }
    public void reset() { last.clear(); }
}
