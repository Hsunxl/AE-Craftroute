package com.aecraftroute.addon.bench;

import appeng.api.stacks.AEKey;
import appeng.crafting.inv.CraftingSimulationState;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link CraftingSimulationState} so the VM runs offline (no IGrid,
 * no Minecraft world). Its legacy byte probe is test-only; no production Mixin
 * is needed to support an offline test utility.
 */
public final class BenchSimulationState extends CraftingSimulationState {

    private final Map<BenchAEKey, Long> stock;

    public BenchSimulationState(Map<BenchAEKey, Long> stock) {
        this.stock = stock;
    }

    @Override
    protected long simulateExtractParent(AEKey what, long amount) {
        long available = what instanceof BenchAEKey k ? stock.getOrDefault(k, 0L) : 0L;
        return Math.min(available, amount);
    }

    @Override
    protected Iterable<AEKey> findFuzzyParent(AEKey input) {
        return List.of(input);
    }

    public double getBytes() {
        // Same semantics as the @Accessor mixin: read the private parent `bytes` field.
        try {
            Field f = CraftingSimulationState.class.getDeclaredField("bytes");
            f.setAccessible(true);
            return f.getDouble(this);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Cannot read CraftingSimulationState.bytes", e);
        }
    }
}
