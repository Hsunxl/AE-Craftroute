package com.ae2vm.addon.route;

import appeng.api.crafting.IPatternDetails;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class RouteIdentity {
    private RouteIdentity() {}
    public static String pattern(IPatternDetails p, HolderLookup.Provider registries) {
        if (p.getDefinition() == null) throw new RoutePlanningException("样板没有稳定标识，暂不支持手动锁定");
        try {
            var bytes = canonical(p.getDefinition().toTagGeneric(registries)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String canonical(Tag tag) {
        if (tag instanceof CompoundTag c) {
            var b = new StringBuilder("{");
            c.getAllKeys().stream().sorted().forEach(k -> b.append(StringTag.quoteAndEscape(k)).append(':').append(canonical(c.get(k))).append(','));
            return b.append('}').toString();
        }
        if (tag instanceof ListTag l) {
            var b = new StringBuilder("[");
            l.forEach(t -> b.append(canonical(t)).append(','));
            return b.append(']').toString();
        }
        return tag.toString();
    }
}
