package com.aecraftroute.addon.api;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class CraftRouteCraftingRegistryTest {
    @Test
    void thirdPartyRequesterRequiresExplicitOptIn() {
        assertFalse(CraftRouteCraftingRegistry.isUnregisteredThirdParty("appeng.me.SomeRequester"));
        assertFalse(CraftRouteCraftingRegistry.isUnregisteredThirdParty("com.aecraftroute.addon.selftest.Requester"));
        assertTrue(CraftRouteCraftingRegistry.isUnregisteredThirdParty("com.example.privatecpu.Requester"));
        CraftRouteCraftingRegistry.register("opted_in_cpu_test_marker");
        assertFalse(CraftRouteCraftingRegistry.isUnregisteredThirdParty(
                "com.example.opted_in_cpu_test_marker.Requester"));
    }
    @Test void bothGrowthExecutorsCheckRequesterBeforeInspectingOrReorderingPatterns() throws Exception {
        for(String mixin:new String[]{"GrowthCpuMixin","AdvancedGrowthCpuMixin"}) {
            var stream=getClass().getClassLoader().getResourceAsStream("com/aecraftroute/addon/mixin/"+mixin+".class");
            assertNotNull(stream);
            var node=new ClassNode();
            try(stream) {new ClassReader(stream).accept(node,0);}
            var mark=node.methods.stream().filter(m->m.name.equals("aecraftroute$mark")).findFirst().orElseThrow();
            var calls=new ArrayList<String>();
            for(var instruction:mark.instructions) if(instruction instanceof MethodInsnNode call)
                calls.add(call.owner+"."+call.name);
            int boundary=calls.indexOf("com/aecraftroute/addon/api/CraftRouteCraftingRegistry.isUnregisteredThirdParty");
            int inspect=calls.indexOf("com/aecraftroute/addon/vm/TwoNodeGrowthPlanner.containsRing");
            assertTrue(boundary>=0 && inspect>boundary,mixin+" must leave foreign execution untouched");
        }
    }
}
