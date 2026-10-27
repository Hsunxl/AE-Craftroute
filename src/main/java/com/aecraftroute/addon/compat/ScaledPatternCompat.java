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

/** Soft compatibility for the public ScaledPattern getter/constructor contract
 * and the verified ExtendedAE Plus strict molecular layer.
 * Never substitutes the inner pattern in an executable plan: one outer dispatch
 * remains one task, with the outer input/output quantities and provider identity.
 * Metadata supplements definition codecs which cannot restore virtual wrappers.
 */
public final class ScaledPatternCompat {
    private static final String WRAPPERS = "aecraftrouteScaledWrappers";
    private static final String ORIGINAL = "aecraftrouteScaledOriginal";
    private static final int MAX_DEPTH = 16;
    private static final String STRICT = "com.extendedae_plus.util.crafting.StrictMolecularAssemblerPattern";

    public static CompoundTag encode(IPatternDetails pattern, Function<IPatternDetails, CompoundTag> leaf) {
        Objects.requireNonNull(pattern);
        var layers = new ListTag();
        var seen = Collections.newSetFromMap(new IdentityHashMap<IPatternDetails, Boolean>());
        long operations = 1;
        while (isScaledClass(pattern.getClass()) || isStrictClass(pattern.getClass())) {
            if (!seen.add(pattern) || layers.size() >= MAX_DEPTH)
                throw new IllegalArgumentException("Cyclic or excessively nested scaled pattern");
            try {
                if(isStrictClass(pattern.getClass())) {
                    // Its definition is the delegate's definition: a leaf codec alone
                    // silently restores a fuzzy AECraftingPattern instead of strict inputs.
                    // Only this exact installed ABI opts into private delegate access.
                    var layer=new CompoundTag();layer.putString("class",STRICT);layer.putLong("operations",1);
                    layer.putBoolean("strict",true);
                    layer.putBoolean("allowScaling",(boolean)pattern.getClass().getMethod("eap$allowScaling").invoke(pattern));
                    layer.putInt("multiplierLimit",(int)pattern.getClass().getMethod("eap$getMultiplierLimit").invoke(pattern));
                    var delegate=pattern.getClass().getDeclaredField("delegate");delegate.setAccessible(true);
                    Object original=delegate.get(pattern);
                    if(!(original instanceof IPatternDetails inner))throw new IllegalArgumentException("Strict pattern has no delegate");
                    layers.add(layer);pattern=inner;continue;
                }
                Object value = multiplier(pattern);
                long count = positiveMultiplier(value);
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
                if(isStrictClass(type)) {
                    if(!layer.getBoolean("strict") || layer.getLong("operations")!=1
                            || !layer.contains("allowScaling",Tag.TAG_BYTE) || !layer.contains("multiplierLimit",Tag.TAG_INT))
                        throw new IllegalArgumentException("Invalid strict pattern metadata");
                    pattern=(IPatternDetails)type.getMethod("wrap",appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern.class)
                            .invoke(null,pattern);
                    type.getMethod("eap$setAllowScaling",boolean.class).invoke(pattern,layer.getBoolean("allowScaling"));
                    type.getMethod("eap$setMultiplierLimit",int.class).invoke(pattern,layer.getInt("multiplierLimit"));
                    continue;
                }
                if (!IPatternDetails.class.isAssignableFrom(type) || !isScaledClass(type) || layer.getBoolean("strict"))
                    throw new IllegalArgumentException("Not a supported scaled pattern class");
                // A level-aware wrapper needs a real world. Offline inspection/tests may
                // have none, so use a two-argument constructor or fail closed.
                var selected = constructor(type,pattern,level != null);
                long count = layer.getLong("operations");
                pattern = (IPatternDetails) (selected.getParameterCount() == 3
                        ? selected.newInstance(pattern, count, level) : selected.newInstance(pattern, count));
            } catch (ReflectiveOperationException | LinkageError exception) {
                throw failure("Cannot restore scaled pattern", exception);
            }
        }
        return pattern;
    }

    private static boolean isScaledClass(Class<?> type) {
        return type.getSimpleName().startsWith("Scaled") && type.getSimpleName().contains("Pattern");
    }
    private static boolean isStrictClass(Class<?> type) {return type.getName().equals(STRICT);}

    /** Installed extendedae_plus wrappers use either getMultiplier() or an
     * inherited protected multiplier field, not getOperationsPerPush(). */
    private static Object multiplier(IPatternDetails pattern) throws ReflectiveOperationException {
        Class<?> type=pattern.getClass();
        for(String getter:new String[]{"getOperationsPerPush","getMultiplier"}) {
            try {return type.getMethod(getter).invoke(pattern);}
            catch(NoSuchMethodException absent) { /* try the next public contract */ }
        }
        // Private-field access is only a verified ExtendedAE Plus ABI. Do not infer
        // another add-on's execution semantics from an unrelated field name.
        for(Class<?> owner=type;owner!=null
                && owner.getName().startsWith("com.extendedae_plus.api.crafting.");owner=owner.getSuperclass()) {
            try {
                var field=owner.getDeclaredField("multiplier");
                field.setAccessible(true);
                return field.get(pattern);
            } catch(NoSuchFieldException absent) { /* inherited field may be higher */ }
        }
        throw new NoSuchMethodException("Scaled pattern has no supported multiplier: "+type.getName());
    }

    private static long positiveMultiplier(Object value) {
        if (!(value instanceof Number number))
            throw new IllegalArgumentException("Invalid scaled pattern multiplier");
        try {
            long count = new java.math.BigDecimal(number.toString()).longValueExact();
            if (count > 0) return count;
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw new IllegalArgumentException("Invalid scaled pattern multiplier", invalid);
        }
        throw new IllegalArgumentException("Invalid scaled pattern multiplier");
    }

    /** Cheap structural preflight, before hashing potentially cyclic wrappers.
     * Uses no registry/definition access; unsupported wrappers fail before submission.
     */
    public static void validate(IPatternDetails pattern) {
        Objects.requireNonNull(pattern);
        if (isScaledClass(pattern.getClass()) || isStrictClass(pattern.getClass())) encode(pattern,ignored -> new CompoundTag());
    }

    private static java.lang.reflect.Constructor<?> constructor(Class<?> type,IPatternDetails original) {
        return constructor(type,original,true);
    }

    private static java.lang.reflect.Constructor<?> constructor(Class<?> type,IPatternDetails original,boolean levelAvailable) {
        java.lang.reflect.Constructor<?> selected = null;
        for (var constructor : type.getConstructors()) {
            var args = constructor.getParameterTypes();
            if ((args.length == 2 || (levelAvailable && args.length == 3)) && args[0].isInstance(original)
                    && args[1] == long.class && (args.length == 2 || args[2] == Level.class)
                    && (selected == null || args.length > selected.getParameterCount())) selected = constructor;
        }
        if (selected == null) throw new IllegalArgumentException("Scaled pattern constructor is unavailable");
        return selected;
    }

    private static IllegalArgumentException failure(String message, Throwable exception) {
        Throwable cause = exception instanceof InvocationTargetException invocation ? invocation.getCause() : exception;
        if (cause instanceof java.util.concurrent.CancellationException cancelled) throw cancelled;
        // Missing optional mod dependencies are linkage failures, not a reason to
        // abort the whole server load. ProductionJob marks this schedule invalid.
        if (cause instanceof Error error && !(error instanceof LinkageError)) throw error;
        return new IllegalArgumentException(message, cause);
    }

    private ScaledPatternCompat() {}
}
