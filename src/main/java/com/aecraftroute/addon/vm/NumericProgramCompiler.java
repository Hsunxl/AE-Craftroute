package com.aecraftroute.addon.vm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

import static com.aecraftroute.addon.vm.CompiledNumericProgram.*;

/** Compiles exact sparse input/output vectors once per bounded probe. */
public final class NumericProgramCompiler {
    private NumericProgramCompiler() {}

    /**
     * Source arrays are read synchronously and never retained. A null return is
     * budget exhaustion, not an unreachable transition. Cancellation thrown by
     * expired is deliberately not caught. All compilation is charged to it.
     */
    public static CompiledNumericProgram compile(int keyCount, int[][] inputKeys, long[][] inputs,
            int[][] outputKeys, long[][] outputs, BooleanSupplier expired) {
        Objects.requireNonNull(expired);
        if (expired.getAsBoolean()) return null;
        if (keyCount < 0 || inputKeys.length != inputs.length || inputKeys.length != outputKeys.length
                || inputKeys.length != outputs.length) throw new IllegalArgumentException("Numeric recipe dimensions");
        var builder = new Builder();
        var entries = new int[inputKeys.length];
        for (int r = 0; r < entries.length; r++) {
            if (expired.getAsBoolean()) return null;
            entries[r] = builder.code.size();
            var amounts = new TreeMap<Integer, long[]>();
            for (int side = 0; side < 2; side++) {
                int[] keys = side == 0 ? inputKeys[r] : outputKeys[r];
                long[] values = side == 0 ? inputs[r] : outputs[r];
                if (keys.length != values.length || side == 1 && keys.length == 0)
                    throw new IllegalArgumentException("Numeric recipe operands");
                for (int i = 0; i < keys.length; i++) {
                    if (expired.getAsBoolean()) return null;
                    if (keys[i] < 0 || keys[i] >= keyCount || values[i] <= 0)
                        throw new IllegalArgumentException("Invalid numeric quantity/key");
                    var pair = amounts.computeIfAbsent(keys[i], ignored -> new long[2]);
                    if (pair[side] != 0) throw new IllegalArgumentException("Duplicate numeric key");
                    pair[side] = values[i];
                }
            }
            // All guards precede all writes. Input/output overlap has ONE delta.
            for (var e : amounts.entrySet()) {
                if (expired.getAsBoolean()) return null;
                long input = e.getValue()[0], delta = Math.subtractExact(e.getValue()[1], input);
                if (input > 0) builder.emit(CHECK_PREFIX, e.getKey(), builder.constant(input), builder.constant(delta));
                if (delta > 0) builder.emit(LIMIT_OUTPUT, e.getKey(), builder.constant(delta), 0);
            }
            for (var e : amounts.entrySet()) {
                if (expired.getAsBoolean()) return null;
                long delta = Math.subtractExact(e.getValue()[1], e.getValue()[0]);
                if (delta != 0) builder.emit(APPLY_DELTA, e.getKey(), builder.constant(delta), 0);
            }
            builder.emit(RECORD_PATTERN, r, 0, 0);
            builder.emit(HALT, 0, 0, 0);
        }
        if (expired.getAsBoolean()) return null;
        var code = new byte[builder.code.size()];
        var operands = new int[builder.operands.size()];
        var constants = new long[builder.constants.size()];
        for (int i = 0; i < code.length; i++) {
            if (expired.getAsBoolean()) return null;
            code[i] = builder.code.get(i);
        }
        for (int i = 0; i < operands.length; i++) {
            if (expired.getAsBoolean()) return null;
            operands[i] = builder.operands.get(i);
        }
        for (int i = 0; i < constants.length; i++) {
            if (expired.getAsBoolean()) return null;
            constants[i] = builder.constants.get(i);
        }
        var program = new CompiledNumericProgram(keyCount, code, operands, entries, constants);
        return expired.getAsBoolean() ? null : program;
    }

    private static final class Builder {
        final ArrayList<Byte> code = new ArrayList<>();
        final ArrayList<Integer> operands = new ArrayList<>();
        final ArrayList<Long> constants = new ArrayList<>();
        final Map<Long,Integer> constantIds = new HashMap<>();
        int constant(long value) {
            return constantIds.computeIfAbsent(value, ignored -> {
                int index = constants.size(); constants.add(value); return index;
            });
        }
        void emit(byte opcode, int a, int b, int c) {
            code.add(opcode); operands.add(a); operands.add(b); operands.add(c);
        }
    }
}
