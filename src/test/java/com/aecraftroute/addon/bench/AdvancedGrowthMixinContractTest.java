package com.aecraftroute.addon.bench;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Read real AdvancedAE bytecode without initializing Minecraft/optional mod classes.
 * This checks the integration ABI; it is not a substitute for a game Mixin smoke test.
 */
class AdvancedGrowthMixinContractTest {
    private static final String LOGIC = "net/pedroksl/advanced_ae/common/logic/";
    private static final String MIXIN = "com/aecraftroute/addon/mixin/";
    private static ClassNode node(String name) throws IOException {
        try (var stream = AdvancedGrowthMixinContractTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(stream, name);
            var node = new ClassNode(); new ClassReader(stream).accept(node, 0); return node;
        }
    }
    private static FieldNode field(ClassNode node, String name, String descriptor) {
        return node.fields.stream().filter(f -> f.name.equals(name) && f.desc.equals(descriptor))
                .findFirst().orElseThrow(() -> new AssertionError(node.name + "." + name + ": " + descriptor));
    }
    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor))
                .findFirst().orElseThrow(() -> new AssertionError(node.name + "." + name + descriptor));
    }
    private static boolean annotation(ClassNode node, String descriptor) {
        return (node.visibleAnnotations != null && node.visibleAnnotations.stream().anyMatch(a -> a.desc.equals(descriptor)))
                || (node.invisibleAnnotations != null && node.invisibleAnnotations.stream().anyMatch(a -> a.desc.equals(descriptor)));
    }
    @Test void installedQuantumCpuHasIndependentCompatibleAbi() throws Exception {
        var cpu = node(LOGIC + "AdvCraftingCPULogic");
        assertEquals("java/lang/Object", cpu.superName);
        field(cpu, "job", "L" + LOGIC + "ExecutingCraftingJob;");
        assertNotEquals(0, field(cpu, "inventory", "Lappeng/crafting/inv/ListCraftingInventory;").access & Opcodes.ACC_FINAL);
        field(cpu, "cpu", "Lnet/pedroksl/advanced_ae/common/cluster/AdvCraftingCPU;");
        method(cpu, "insert", "(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;)J");
        method(cpu, "trySubmitJob", "(Lappeng/api/networking/IGrid;Lappeng/api/networking/crafting/ICraftingPlan;Lappeng/api/networking/security/IActionSource;Lappeng/api/networking/crafting/ICraftingRequester;)Lappeng/api/networking/crafting/ICraftingSubmitResult;");
        for (String name : List.of("readFromNBT", "writeToNBT"))
            method(cpu, name, "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V");
        var job = node(LOGIC + "ExecutingCraftingJob");
        assertNotEquals(0, field(job, "tasks", "Ljava/util/Map;").access & Opcodes.ACC_FINAL);
        field(job, "waitingFor", "Lappeng/crafting/inv/ListCraftingInventory;");
        field(job, "finalOutput", "Lappeng/api/stacks/GenericStack;");
        field(job, "timeTracker", "L" + LOGIC + "ElapsedTimeTracker;");
        field(node(LOGIC + "ExecutingCraftingJob$TaskProgress"), "value", "J");
        method(node(LOGIC + "ElapsedTimeTracker"), "decrementItems", "(JLappeng/api/stacks/AEKeyType;)V");
        method(node("net/pedroksl/advanced_ae/common/cluster/AdvCraftingCPU"), "markDirty", "()V");
    }
    @Test void optionalMixinsAreRegisteredAndDoNotCreditRetainedItemsAsDelivery() throws Exception {
        String config;
        try (var stream = getClass().getClassLoader().getResourceAsStream("aecraftroute.mixins.json")) {
            assertNotNull(stream); config = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String name : List.of("AdvancedGrowthCpuMixin", "AdvancedGrowthJobAccessor", "AdvancedGrowthTaskAccessor", "AdvancedGrowthTimeAccessor")) {
            assertTrue(config.contains("\"" + name + "\""));
            assertTrue(annotation(node(MIXIN + name), "Lorg/spongepowered/asm/mixin/Pseudo;"), name);
        }
        for(String name:List.of("GrowthCpuMixin","AdvancedGrowthCpuMixin")) {
            var cpu = node(MIXIN + name);
            boolean hasFlushDelivery=false;
            for (var method : cpu.methods) for (var insn : method.instructions) {
                if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD)
                    assertNotEquals("remainingAmount", f.name, "Retaining feedback is not order delivery");
                if (insn instanceof MethodInsnNode m) {
                    // The old protocol delegated final surplus to native insert. The
                    // deferred protocol has a separate actual-acceptance flush path.
                    if(m.owner.equals("appeng/crafting/CraftingLink")) {
                        assertEquals("aecraftroute$releaseFeedback",method.name,
                                "Intake and input-consumption hooks must not deliver to requester");
                        if(m.name.equals("insert"))hasFlushDelivery=true;
                    }
                    if(m.name.equals("aecraftroute$setRemainingAmount"))
                        assertEquals("aecraftroute$releaseFeedback",method.name,
                                "Only actual delivery may reduce the outstanding order");
                }
            }
            assertTrue(hasFlushDelivery,name+" must have an explicit deferred-delivery path");
        }
        var access = node(MIXIN + "AdvancedGrowthJobAccessor");
        var setter = method(access, "aecraftroute$setTasks", "(Ljava/util/Map;)V");
        assertTrue(setter.visibleAnnotations.stream().anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mutable;")));
    }
}
