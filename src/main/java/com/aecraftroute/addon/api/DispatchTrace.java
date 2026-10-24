package com.aecraftroute.addon.api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Counters that record which crafting CPU logic actually received a certified
 * production program. Behaviour alone cannot prove which CPU ran a job when a
 * network has more than one, so the CPU bridges report here and tests read it.
 */
public final class DispatchTrace {
    private static final Logger LOG = LoggerFactory.getLogger("aecraftroute-dispatch");
    private static final AtomicInteger CERTIFIED = new AtomicInteger();
    private static final Map<String, Integer> HOSTS = new ConcurrentHashMap<>();

    private DispatchTrace() {
    }

    /** Called by the CPU bridges after a certified plan is accepted. */
    public static void certified(String host) {
        CERTIFIED.incrementAndGet();
        HOSTS.merge(host, 1, Integer::sum);
        LOG.info("[AE-Craftroute] certified production program attached on {}", host);
    }

    public static int certifiedAttaches() {
        return CERTIFIED.get();
    }

    public static Map<String, Integer> hosts() {
        return Map.copyOf(HOSTS);
    }

    public static void reset() {
        CERTIFIED.set(0);
        HOSTS.clear();
    }
}
