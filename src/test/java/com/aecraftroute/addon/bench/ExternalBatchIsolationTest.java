package com.aecraftroute.addon.bench;

import com.aecraftroute.addon.compat.CertifiedExecutionOwner;
import com.aecraftroute.addon.mixin.*;
import com.aecraftroute.addon.planner.*;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ExternalBatchIsolationTest {
    @Test void injectorReturnsZeroOnlyForCertifiedOwnersWithoutTouchingTheJob() throws Exception {
        var target=new NeoEcoExternalCpuFastPathMixin() {};
        var inject=Arrays.stream(NeoEcoExternalCpuFastPathMixin.class.getDeclaredMethods())
                .filter(m->m.getName().equals("aecraftroute$keepCertifiedDispatch")).findFirst().orElseThrow();
        inject.setAccessible(true);
        var state=new java.util.concurrent.atomic.AtomicBoolean(true);
        CertifiedExecutionOwner owner=state::get;
        var job=new Object();
        var blocked=new CallbackInfoReturnable<Integer>("execute",true);
        inject.invoke(target,owner,job,null,1,null,null,null,blocked);
        assertTrue(blocked.isCancelled());assertEquals(0,blocked.getReturnValue().intValue());
        state.set(false);
        var normal=new CallbackInfoReturnable<Integer>("execute",true);
        inject.invoke(target,owner,job,null,1,null,null,null,normal);
        assertFalse(normal.isCancelled());
        var unrelated=new CallbackInfoReturnable<Integer>("execute",true);
        inject.invoke(target,new Object(),job,null,1,null,null,null,unrelated);
        assertFalse(unrelated.isCancelled());
    }
    private ProductionJob program() {
        var key=BenchAEKey.of("ownership");
        return new ProductionJob(new CertifiedCraftingPlan(new GenericStack(key,1),1,new KeyCounter(),Map.of(),List.of()));
    }
    private void checkOwner(Object owner,Class<?> mixin) throws Exception {
        var field=mixin.getDeclaredField("aecraftroute$production");field.setAccessible(true);
        assertFalse(CertifiedExecutionOwner.blocksExternalBatch(owner));
        field.set(owner,program());assertTrue(CertifiedExecutionOwner.blocksExternalBatch(owner));
        field.set(owner,null);assertFalse(CertifiedExecutionOwner.blocksExternalBatch(owner));
    }
    @Test void nativeCpuOwnershipTracksItsActualCertifiedProgram() throws Exception {
        checkOwner(new ProductionCpuMixin(){},ProductionCpuMixin.class);
    }
    @Test void quantumCpuOwnershipTracksItsActualCertifiedProgram() throws Exception {
        checkOwner(new AdvancedProductionCpuMixin(){},AdvancedProductionCpuMixin.class);
    }
    @Test void unknownAndNullOwnersKeepTheirBatchExecution() {
        assertFalse(CertifiedExecutionOwner.blocksExternalBatch(null));
        assertFalse(CertifiedExecutionOwner.blocksExternalBatch(new Object()));
    }
    @Test void installedNeoEcoAbiMatchesOptionalInjectionDescriptor() throws Exception {
        String name="cn/dancingsnow/neoecoae/crafting/execution/ECOExternalCpuFastPath";
        try(var in=getClass().getClassLoader().getResourceAsStream(name+".class")) {
            assertNotNull(in,"Installed NeoECO jar is required for this ABI regression");
            var node=new ClassNode();new ClassReader(in).accept(node,0);
            assertTrue(node.methods.stream().anyMatch(m->m.name.equals("execute") && m.desc.equals(
                    "(Ljava/lang/Object;Ljava/lang/Object;Lappeng/crafting/inv/ListCraftingInventory;ILappeng/me/service/CraftingService;Lappeng/api/networking/energy/IEnergyService;Lnet/minecraft/world/level/Level;)I")));
        }
        try(var in=getClass().getClassLoader().getResourceAsStream("aecraftroute.mixins.json")) {
            assertNotNull(in);assertTrue(new String(in.readAllBytes(),StandardCharsets.UTF_8).contains("\"NeoEcoExternalCpuFastPathMixin\""));
        }
    }
}
