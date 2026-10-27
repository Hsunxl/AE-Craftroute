package com.aecraftroute.addon.bench;

import com.aecraftroute.addon.compat.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CertifiedExecutionScopeTest {
    final CertifiedExecutionOwner certified=()->true,ordinary=()->false;
    @Test void allPatternsAreProtectedOnlyDuringCertifiedExecution() {
        assertTrue(ThunderboltBatchCompat.allowsBatch(null));
        CertifiedExecutionScope.run(certified,()->{assertTrue(CertifiedExecutionScope.active());assertFalse(ThunderboltBatchCompat.allowsBatch(null));});
        assertFalse(CertifiedExecutionScope.active());assertTrue(ThunderboltBatchCompat.allowsBatch(null));
    }
    @Test void nestedOrdinaryCpuKeepsItsOwnBatchingAndRestoresOuterOwnership() {
        CertifiedExecutionScope.run(certified,()->{
            CertifiedExecutionScope.run(ordinary,()->{assertFalse(CertifiedExecutionScope.active());assertTrue(ThunderboltBatchCompat.allowsBatch(null));});
            assertTrue(CertifiedExecutionScope.active());
        });assertFalse(CertifiedExecutionScope.active());
    }
    @Test void exceptionCannotLeakTheRestrictionIntoTheNextJob() {
        assertThrows(IllegalStateException.class,()->CertifiedExecutionScope.run(certified,()->{throw new IllegalStateException();}));
        assertFalse(CertifiedExecutionScope.active());
        CertifiedExecutionScope.run(new Object(),()->assertTrue(ThunderboltBatchCompat.allowsBatch(null)));
    }
    @Test void otherThreadsAndOrdinaryOwnersAreNeverGloballyDisabled() throws Exception {
        var executor=Executors.newSingleThreadExecutor();
        try {CertifiedExecutionScope.run(certified,()->{
            try {assertFalse(executor.submit(CertifiedExecutionScope::active).get(1,TimeUnit.SECONDS));}
            catch(Exception failure){throw new AssertionError(failure);}
        });}finally{executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));}
    }
}
