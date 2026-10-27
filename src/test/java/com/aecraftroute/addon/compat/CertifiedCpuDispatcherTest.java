package com.aecraftroute.addon.compat;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.CraftingSubmitErrorCode;
import appeng.api.networking.security.IActionSource;
import appeng.crafting.execution.CraftingSubmitResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import com.google.common.collect.ImmutableSet;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CertifiedCpuDispatcherTest {
    public static class SelectableCluster {
        public boolean canBeAutoSelectedFor(IActionSource source) {return true;}
    }
    public static class ExcludedCluster {
        public boolean canBeAutoSelectedFor(IActionSource source) {return false;}
    }
    public static class BrokenCluster {
        public boolean canBeAutoSelectedFor(IActionSource source) {throw new IllegalStateException("missing mode");}
    }
    public static class MissingSubmitCluster {}
    public static class NullSubmitCluster {
        public appeng.api.networking.crafting.ICraftingSubmitResult submitJob(IGrid grid,
                appeng.api.networking.crafting.ICraftingPlan plan,IActionSource source,
                appeng.api.networking.crafting.ICraftingRequester requester) {return null;}
    }
    public static class ThrowingSubmitCluster {
        public appeng.api.networking.crafting.ICraftingSubmitResult submitJob(IGrid grid,
                appeng.api.networking.crafting.ICraftingPlan plan,IActionSource source,
                appeng.api.networking.crafting.ICraftingRequester requester) {
            throw new IllegalStateException("already entered submit");
        }
    }
    @Test void automaticRerouteRespectsForeignCpuSelectionAndFailsClosedWhenUnknown() {
        assertTrue(CertifiedCpuDispatcher.canAutoSelect(new SelectableCluster(),null));
        assertFalse(CertifiedCpuDispatcher.canAutoSelect(new ExcludedCluster(),null));
        assertFalse(CertifiedCpuDispatcher.canAutoSelect(new BrokenCluster(),null));
        assertFalse(CertifiedCpuDispatcher.canAutoSelect(new Object(),null));
    }
    @Test void onlyExecutorAvailabilityFailuresPermitSilentReroute() {
        for(var code:new CraftingSubmitErrorCode[]{CraftingSubmitErrorCode.CPU_OFFLINE,
                CraftingSubmitErrorCode.CPU_BUSY,CraftingSubmitErrorCode.CPU_TOO_SMALL,
                CraftingSubmitErrorCode.NO_CPU_FOUND,CraftingSubmitErrorCode.NO_SUITABLE_CPU_FOUND})
            assertTrue(CertifiedCpuDispatcher.canReroute(code),code.toString());
        assertFalse(CertifiedCpuDispatcher.canReroute(CraftingSubmitErrorCode.MISSING_INGREDIENT));
        assertFalse(CertifiedCpuDispatcher.canReroute(CraftingSubmitErrorCode.INCOMPLETE_PLAN));
        assertFalse(CertifiedCpuDispatcher.canReroute(null));
    }
    @Test void missingAdvancedApiMayRerouteButInvokedFailureMustNotRetry() {
        assertEquals(CraftingSubmitErrorCode.NO_SUITABLE_CPU_FOUND,
                CertifiedCpuDispatcher.submitAdvanced(new MissingSubmitCluster(),null,null,null,null).errorCode());
        var failure=assertThrows(IllegalStateException.class,()->
                CertifiedCpuDispatcher.submitAdvanced(new ThrowingSubmitCluster(),null,null,null,null));
        assertEquals("already entered submit",failure.getCause().getMessage());
        assertThrows(IllegalStateException.class,()->
                CertifiedCpuDispatcher.submitAdvanced(new NullSubmitCluster(),null,null,null,null));
    }
    @Test
    void incompatibleExplicitCpuFallsBackToAutomaticSelection() {
        var selected = (ICraftingCPU) Proxy.newProxyInstance(ICraftingCPU.class.getClassLoader(),
                new Class<?>[]{ICraftingCPU.class}, (proxy, method, args) -> null);
        var selectionAttempted=new AtomicBoolean();
        var service=(ICraftingService)Proxy.newProxyInstance(ICraftingService.class.getClassLoader(),
                new Class<?>[]{ICraftingService.class}, (proxy,method,args)-> {
                    if(method.getName().equals("getCpus")) {selectionAttempted.set(true);return ImmutableSet.of();}
                    return null;
                });
        var grid=(IGrid)Proxy.newProxyInstance(IGrid.class.getClassLoader(),
                new Class<?>[]{IGrid.class}, (proxy,method,args)->method.getName().equals("getCraftingService")?service:null);
        var result = CertifiedCpuDispatcher.submit(grid, null, selected, null, null, false);
        assertTrue(selectionAttempted.get());
        assertFalse(result.successful());
        assertSame(CraftingSubmitResult.NO_CPU_FOUND, result);
    }
}
