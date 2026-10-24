package com.aecraftroute.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Legacy structural stand-in for a smart-doubling processing wrapper.
 * Each push multiplies the input multipliers and output quantities, while possible
 * input templates remain unchanged. This fixture intentionally retains nested
 * wrappers so the codec can test bounded recursive reconstruction.
 *
 * <p>The current upstream wrapper can flatten nesting, encode its multiplier in a
 * component and exempt reusable slots from scaling. Those optional-mod runtime
 * behaviors are not claimed by this offline fixture. Executable plans must retain
 * this OUTER pattern's identity, quantities and one-push task counting.
 */
public final class ScaledBenchPatternDetails implements IPatternDetails {

    private final IPatternDetails original;
    private final long operationsPerPush;
    private final IInput[] inputs;
    private final List<GenericStack> outputs;

    public ScaledBenchPatternDetails(IPatternDetails original, long operationsPerPush) {
        if (operationsPerPush <= 0) {
            throw new IllegalArgumentException("operationsPerPush must be positive");
        }
        this.original = Objects.requireNonNull(original, "original");
        this.operationsPerPush = operationsPerPush;
        IInput[] originalInputs = original.getInputs();
        this.inputs = new IInput[originalInputs.length];
        for (int i = 0; i < originalInputs.length; i++) {
            this.inputs[i] = new ScaledInput(originalInputs[i], operationsPerPush);
        }
        List<GenericStack> scaledOutputs = new ArrayList<>(original.getOutputs().size());
        for (GenericStack output : original.getOutputs()) {
            if (output != null) {
                scaledOutputs.add(new GenericStack(
                        output.what(), Math.multiplyExact(output.amount(), operationsPerPush)));
            }
        }
        this.outputs = List.copyOf(scaledOutputs);
    }

    /** The wrapped (unscaled) pattern. */
    public IPatternDetails getOriginal() {
        return original;
    }

    /** How many original operations one scaled push represents. */
    public long getOperationsPerPush() {
        return operationsPerPush;
    }

    @Override
    public AEItemKey getDefinition() {
        return null; // never called on the VM path
    }

    @Override
    public IInput[] getInputs() {
        return inputs.clone();
    }

    @Override
    public List<GenericStack> getOutputs() {
        return outputs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ScaledBenchPatternDetails s)) {
            return false;
        }
        return operationsPerPush == s.operationsPerPush && original.equals(s.original);
    }

    @Override
    public int hashCode() {
        return 31 * original.hashCode() + Long.hashCode(operationsPerPush);
    }

    @Override
    public String toString() {
        return "ScaledBenchPatternDetails[operationsPerPush=" + operationsPerPush
                + ", original=" + original + ']';
    }

    /** Input view with the multiplier scaled by {@code operationsPerPush}. */
    private record ScaledInput(IInput original, long operationsPerPush) implements IInput {
        @Override
        public GenericStack[] getPossibleInputs() {
            return original.getPossibleInputs();
        }

        @Override
        public long getMultiplier() {
            return Math.multiplyExact(original.getMultiplier(), operationsPerPush);
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            return original.isValid(input, level);
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return original.getRemainingKey(template);
        }
    }
}
