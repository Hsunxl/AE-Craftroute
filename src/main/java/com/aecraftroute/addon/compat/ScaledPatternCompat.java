package com.aecraftroute.addon.compat;

import appeng.api.crafting.IPatternDetails;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;

import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Function;

/** Soft compatibility for the public ScaledPattern getter/constructor contract.
 * Never substitutes the inner pattern in an executable plan: one outer dispatch
 * remains one task, with the outer input/output quantities and provider identity.
 * Metadata supplements definition codecs which cannot restore virtual wrappers.
 */
public final class ScaledPatternCompat {
    private static final String WRAPPERS = "aecraftrouteScaledWrappers";
    private static final String ORIGINAL = "aecraftrouteScaledOriginal";
    private static final int MAX_DEPTH = 16;

    public static CompoundTag encode(IPatternDetails pattern, Function<IPatternDetails, CompoundTag> leaf) {
        Objects.requireNonNull(pattern);
        var layers = new ListTag();
        var seen = Collections.newSetFromMap(new IdentityHashMap<IPatternDetails, Boolean>());
        long operations = 1;
        while (isScaledClass(pattern.getClass())) {
            if (!seen.add(pattern) || layers.size() >= MAX_DEPTH)
                throw new IllegalArgumentException("Cyclic or excessively nested scaled pattern");
            try {
                Object value = pattern.getClass().getMethod("getOperationsPerPush").invoke(pattern);
                if (!(value instanceof Long count) || count <= 0)
                    throw new IllegalArgumentException("Invalid scaled pattern multiplier");
                operations = Math.multiplyExact(operations, count);
                var layer = new CompoundTag();
                layer.putString("class", pattern.getClass().getName());
                layer.putLong("operations", count);
                layers.add(layer);
                Object original = pattern.getClass().getMethod("getOriginal").invoke(pattern);
                if (!(original instanceof IPatternDetails inner))
                    throw new IllegalArgumentException("Scaled pattern has no original pattern");
                constructor(pattern.getClass(),inner);
                pattern = inner;
            } catch (ReflectiveOperationException exception) {
                throw failure("Cannot inspect scaled pattern", exception);
            }
        }
        var encoded = Objects.requireNonNull(leaf.apply(pattern));
        if (layers.isEmpty()) return encoded;
        var result = new CompoundTag();
        result.put(WRAPPERS, layers);
        result.put(ORIGINAL, encoded);
        return result;
    }

    public static IPatternDetails decode(CompoundTag tag, Function<CompoundTag, IPatternDetails> leaf, Level level) {
        if (!tag.contains(WRAPPERS)) return leaf.apply(tag); // Existing plain-pattern saves remain readable.
        var layers = tag.getList(WRAPPERS, Tag.TAG_COMPOUND);
        if (layers.isEmpty() || layers.size() > MAX_DEPTH || !tag.contains(ORIGINAL, Tag.TAG_COMPOUND))
            throw new IllegalArgumentException("Invalid scaled pattern metadata");
        long operations = 1;
        for (int i = 0; i < layers.size(); i++) {
            long count = layers.getCompound(i).getLong("operations");
            if (count <= 0) throw new IllegalArgumentException("Invalid scaled pattern multiplier");
            operations = Math.multiplyExact(operations, count);
        }
        IPatternDetails pattern = Objects.requireNonNull(leaf.apply(tag.getCompound(ORIGINAL)), "Pattern no longer decodes");
        for (int i = layers.size() - 1; i >= 0; i--) {
            var layer = layers.getCompound(i);
            try {
                Class<?> type = Class.forName(layer.getString("class"), false, ScaledPatternCompat.class.getClassLoader());
                if (!IPatternDetails.class.isAssignableFrom(type) || !isScaledClass(type))
                    throw new IllegalArgumentException("Not a supported scaled pattern class");
                // Level-aware constructors preserve reusable/damaged-tool slot semantics.
                var selected = constructor(type,pattern);
                long count = layer.getLong("operations");
                pattern = (IPatternDetails) (selected.getParameterCount() == 3
                        ? selected.newInstance(pattern, count, level) : selected.newInstance(pattern, count));
            } catch (ReflectiveOperationException exception) {
                throw failure("Cannot restore scaled pattern", exception);
            }
        }
        return pattern;
    }

    private static boolean isScaledClass(Class<?> type) {
        return type.getSimpleName().startsWith("Scaled") && type.getSimpleName().contains("Pattern");
    }

    /** Cheap structural preflight, before hashing potentially cyclic wrappers.
     * Uses no registry/definition access; unsupported wrappers fail before submission.
     */
    public static void validate(IPatternDetails pattern) {
        Objects.requireNonNull(pattern);
        if (isScaledClass(pattern.getClass())) encode(pattern,ignored -> new CompoundTag());
    }

    private static java.lang.reflect.Constructor<?> constructor(Class<?> type,IPatternDetails original) {
        java.lang.reflect.Constructor<?> selected = null;
        for (var constructor : type.getConstructors()) {
            var args = constructor.getParameterTypes();
            if ((args.length == 2 || args.length == 3) && args[0].isInstance(original)
                    && args[1] == long.class && (args.length == 2 || args[2] == Level.class)
                    && (selected == null || args.length > selected.getParameterCount())) selected = constructor;
        }
        if (selected == null) throw new IllegalArgumentException("Scaled pattern constructor is unavailable");
        return selected;
    }

    private static IllegalArgumentException failure(String message, ReflectiveOperationException exception) {
        Throwable cause = exception instanceof InvocationTargetException invocation ? invocation.getCause() : exception;
        if (cause instanceof java.util.concurrent.CancellationException cancelled) throw cancelled;
        if (cause instanceof Error error) throw error;
        return new IllegalArgumentException(message, cause);
    }

    private ScaledPatternCompat() {}
}
