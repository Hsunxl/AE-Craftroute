package com.aecraftroute.addon.telemetry;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CpuOfflineLogThrottleTest {
    @Test void repeatedOfflineRefusalsOnlyLogOncePerWindow() {
        var throttle = new CpuOfflineLogThrottle(30_000_000_000L);
        assertTrue(throttle.shouldLog(1));
        assertFalse(throttle.shouldLog(2));
        assertFalse(throttle.shouldLog(30_000_000_000L));
        assertTrue(throttle.shouldLog(30_000_000_001L));
    }

    @Test void invalidWindowIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CpuOfflineLogThrottle(0));
    }
}
