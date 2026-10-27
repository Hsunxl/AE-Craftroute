package com.aecraftroute.addon.vm;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Validates the versioned growth-feedback metadata stored beside native CPU NBT. */
public final class LegacyFeedbackSave {
    public record State(boolean enabled, long restoreSeed, boolean deferred, long held) {}

    private LegacyFeedbackSave() {}

    public static State read(CompoundTag tag) {
        boolean enabled = readBooleanAlias(tag, "aecraftrouteGrowthFeedback", "ae2vmGrowthFeedback", false);
        long seed = readLongAlias(tag, "aecraftrouteRestoreTargetSeed", "ae2vmRestoreTargetSeed", 0);
        if (seed < 0) throw new IllegalArgumentException("Negative saved growth-feedback seed");

        boolean hasDeferred = tag.contains("aecraftrouteDeferredFeedback");
        if (hasDeferred && !tag.contains("aecraftrouteDeferredFeedback", Tag.TAG_BYTE))
            throw new IllegalArgumentException("Invalid deferred feedback marker type");
        boolean deferred = hasDeferred && tag.getBoolean("aecraftrouteDeferredFeedback");
        if (deferred && !enabled)
            throw new IllegalArgumentException("Deferred feedback marker without growth-feedback job");
        // The writer only emits the deferred marker while a native job is being saved,
        // so a marker without one is corrupt and the retained credit has no owner.
        // The native loader reads its own job as a compound; any other type is
        // necessarily corrupt and would also hide the credit from the CPU.
        if (deferred && !tag.contains("job", Tag.TAG_COMPOUND))
            throw new IllegalArgumentException("Deferred feedback marker without saved job");
        long held = 0;
        if (deferred) {
            if (!tag.contains("aecraftrouteDeferredHeld", Tag.TAG_LONG))
                throw new IllegalArgumentException("Missing or invalid deferred feedback held count");
            held = tag.getLong("aecraftrouteDeferredHeld");
        } else if (tag.contains("aecraftrouteDeferredHeld")) {
            // The legacy protocol never retains deferred credit, so a leftover count
            // (or one of the wrong type) contradicts the marker and must not be dropped.
            if (!tag.contains("aecraftrouteDeferredHeld", Tag.TAG_LONG))
                throw new IllegalArgumentException("Orphaned deferred feedback held count");
            if (tag.getLong("aecraftrouteDeferredHeld") != 0)
                throw new IllegalArgumentException("Deferred feedback held count without deferred marker");
        }
        return new State(enabled, seed, deferred, held);
    }

    private static boolean readBooleanAlias(CompoundTag tag, String current, String legacy, boolean fallback) {
        if (tag.contains(current, Tag.TAG_BYTE)) return tag.getBoolean(current);
        if (tag.contains(legacy, Tag.TAG_BYTE)) return tag.getBoolean(legacy);
        if (tag.contains(current) || tag.contains(legacy))
            throw new IllegalArgumentException("Invalid saved growth-feedback flag type");
        return fallback;
    }

    private static long readLongAlias(CompoundTag tag, String current, String legacy, long fallback) {
        if (tag.contains(current, Tag.TAG_LONG)) return tag.getLong(current);
        if (tag.contains(legacy, Tag.TAG_LONG)) return tag.getLong(legacy);
        if (tag.contains(current) || tag.contains(legacy))
            throw new IllegalArgumentException("Invalid saved growth-feedback seed type");
        return fallback;
    }
}
