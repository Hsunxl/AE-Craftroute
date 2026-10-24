package com.aecraftroute.addon.vm;

import java.util.Objects;
import java.util.function.BooleanSupplier;

import static com.aecraftroute.addon.vm.CompiledNumericProgram.*;

/** Numeric interpreter only: it contributes a candidate word, never a proof. */
public final class NumericCraftingVM {
    private NumericCraftingVM() {}
    public enum Status { EXECUTED, BLOCKED, TIMED_OUT }
    public record Step(int recipe, long count) {}

    public static final class Execution {
        private final Status status;
        private final Step step;
        private final long[] inventory;
        private Execution(Status status, Step step, long[] ownedInventory) {
            this.status = status; this.step = step; this.inventory = ownedInventory;
        }
        public Status status() { return status; }
        public Step step() { return step; }
        /** A single detached copy for committing a successful candidate. */
        public long[] inventory() { return inventory == null ? null : inventory.clone(); }
        public long quantity(int key) {
            if (inventory == null) throw new IllegalStateException("No committed numeric inventory");
            return inventory[key];
        }
    }

    /**
     * A candidate is atomic: writes affect a private copy and become observable
     * only at HALT. Timeout/block returns no stock or step; cancellation and
     * arithmetic failures propagate unchanged, also without touching the input.
     */
    public static Execution execute(CompiledNumericProgram program, int recipe, long requested,
            long[] stock, BooleanSupplier expired) {
        Objects.requireNonNull(program); Objects.requireNonNull(expired);
        if (expired.getAsBoolean()) return failed(Status.TIMED_OUT);
        if (recipe < 0 || recipe >= program.recipeCount() || requested <= 0 || stock.length != program.keyCount())
            throw new IllegalArgumentException("Numeric execution arguments");
        long[] working = stock.clone();
        for (long value : working) {
            if (expired.getAsBoolean()) return failed(Status.TIMED_OUT);
            if (value < 0) throw new IllegalArgumentException("Negative numeric inventory");
        }
        long count = requested;
        Step step = null;
        for (int pc = program.entry(recipe); ; pc++) {
            if (expired.getAsBoolean()) return failed(Status.TIMED_OUT);
            int key = program.operand(pc, 0);
            switch (program.opcode(pc)) {
                case CHECK_PREFIX -> {
                    long input = program.constant(program.operand(pc, 1));
                    long delta = program.constant(program.operand(pc, 2));
                    if (working[key] < input) return failed(Status.BLOCKED);
                    if (delta < 0) count = Math.min(count, 1 + (working[key] - input) / -delta);
                }
                case LIMIT_OUTPUT -> {
                    long delta = program.constant(program.operand(pc, 1));
                    count = Math.min(count, (Long.MAX_VALUE - working[key]) / delta);
                    if (count == 0) return failed(Status.BLOCKED);
                }
                case APPLY_DELTA -> {
                    long delta = program.constant(program.operand(pc, 1));
                    working[key] = Math.addExact(working[key], Math.multiplyExact(delta, count));
                }
                case RECORD_PATTERN -> step = new Step(key, count);
                case HALT -> {
                    if (step == null) throw new IllegalStateException("Numeric program omitted pattern record");
                    return new Execution(Status.EXECUTED, step, working);
                }
                default -> throw new IllegalStateException("Unknown numeric opcode at " + pc);
            }
        }
    }
    private static Execution failed(Status status) { return new Execution(status, null, null); }
}
