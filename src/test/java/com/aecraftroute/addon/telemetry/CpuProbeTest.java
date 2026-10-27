package com.aecraftroute.addon.telemetry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The probe is diagnostic only, so its contract is narrow: it must read the same booleans
 * AE2 reads, and it must never claim a CPU is unusable when the state is merely unreadable.
 */
class CpuProbeTest {
    /** The shape every AE2 CPU family exposes, including the third-party ones. */
    public static final class FakeCpu {
        final boolean active;
        final boolean busy;
        final Object node;

        FakeCpu(boolean active, boolean busy, Object node) {
            this.active = active;
            this.busy = busy;
            this.node = node;
        }

        public boolean isActive() { return active; }
        public boolean isBusy() { return busy; }
        public long getAvailableStorage() { return 4096L; }
        public Object getNode() { return node; }
    }

    /** Power, boot and channels, exactly as {@code IGridNode.isActive()} combines them. */
    public static final class FakeNode {
        public boolean isActive() { return false; }
        public boolean isPowered() { return true; }
        public boolean hasGridBooted() { return false; }
        public boolean meetsChannelRequirements() { return true; }
    }

    @Test
    void describeReportsEveryReadablePredicate() {
        var text = CpuProbe.describe(new FakeCpu(false, true, new FakeNode()));
        assertTrue(text.contains("isActive=false"), text);
        assertTrue(text.contains("isBusy=true"), text);
        assertTrue(text.contains("getAvailableStorage=4096"), text);
        assertTrue(text.contains("node="), "the grid node is described too: " + text);
        assertTrue(text.contains("hasGridBooted=false"), "the node's own predicates are shown: " + text);
    }

    @Test
    void describeToleratesObjectsWithoutTheAe2Shape() {
        assertEquals("java.lang.Object{}", CpuProbe.describe(new Object()));
        // A null target means AE2 picks the CPU itself, which is worth saying out loud in a log.
        assertEquals("<automatic>", CpuProbe.describe(null));
    }

    @Test
    void onlyAPositiveFalseCountsAsInactive() {
        assertTrue(CpuProbe.isProvablyInactive(new FakeCpu(false, false, null)));
        assertFalse(CpuProbe.isProvablyInactive(new FakeCpu(true, false, null)));
        assertFalse(CpuProbe.isProvablyInactive(new Object()), "unreadable state is never treated as offline");
        assertFalse(CpuProbe.isProvablyInactive(null));
    }

    @Test
    void inactiveReasonSeparatesMissingCoreFromUnpoweredNode() {
        assertTrue(CpuProbe.describeInactiveReason(new FakeCpu(false, false, null)).contains("no grid node"));
        assertTrue(CpuProbe.describeInactiveReason(new FakeCpu(false, false, new FakeNode())).contains("hasGridBooted=false"));
    }
}
