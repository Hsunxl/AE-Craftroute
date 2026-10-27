package com.aecraftroute.addon.vm;

/**
 * Immutable, request-local numeric bytecode. Key and recipe numbers belong only
 * to the caller's frozen snapshot; no game objects or live network are retained.
 * This is a compiler/interpreter kernel adaptation, not the old VM's binary
 * format, runtime, or complete crafting engine.
 */
public final class CompiledNumericProgram {
    public static final byte CHECK_PREFIX = 1, LIMIT_OUTPUT = 2, APPLY_DELTA = 3,
            RECORD_PATTERN = 4, HALT = 5;
    private final int keyCount;
    private final byte[] code;
    // Three integer operand slots per instruction; quantities index constants.
    private final int[] operands, entries;
    private final long[] constants;

    CompiledNumericProgram(int keyCount, byte[] code, int[] operands, int[] entries, long[] constants) {
        this.keyCount = keyCount;
        this.code = code.clone(); this.operands = operands.clone();
        this.entries = entries.clone(); this.constants = constants.clone();
    }

    public int keyCount() { return keyCount; }
    public int recipeCount() { return entries.length; }
    int instructionCount() { return code.length; }
    public byte[] code() { return code.clone(); }
    public int[] operands() { return operands.clone(); }
    public int[] entryPoints() { return entries.clone(); }
    public long[] constants() { return constants.clone(); }
    int entry(int recipe) { return entries[recipe]; }
    byte opcode(int pc) { return code[pc]; }
    int operand(int pc, int slot) { return operands[pc * 3 + slot]; }
    long constant(int index) { return constants[index]; }
}
