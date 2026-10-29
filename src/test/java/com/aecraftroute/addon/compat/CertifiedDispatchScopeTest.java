package com.aecraftroute.addon.compat;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CertifiedDispatchScopeTest {
    final Object owner=new Object(),pattern=new Object(),inputs=new Object();
    final AtomicInteger commits=new AtomicInteger(),uncertain=new AtomicInteger();
    boolean dispatch(BooleanSupplier provider) {
        return CertifiedDispatchScope.dispatch(owner,pattern,inputs,commits::incrementAndGet,uncertain::incrementAndGet,provider);
    }
    boolean receipt() {return CertifiedDispatchScope.confirmAccepted(pattern,inputs);}
    @Test void ordinaryAcceptanceCommitsOnce() {
        assertTrue(dispatch(()->true));assertEquals(1,commits.get());assertEquals(0,uncertain.get());
        assertFalse(receipt());
    }
    @Test void rejectionDoesNotAdvanceOrMarkUncertain() {
        assertFalse(dispatch(()->false));assertEquals(0,commits.get());assertEquals(0,uncertain.get());
    }
    @Test void repeatedDurableReceiptsAndTrueReturnStillCommitOnce() {
        assertTrue(dispatch(()->{assertTrue(receipt());assertTrue(receipt());return true;}));
        assertEquals(1,commits.get());
    }
    @Test void acceptedOwnershipOverridesFalseReturn() {
        assertTrue(dispatch(()->{receipt();return false;}));assertEquals(1,commits.get());
    }
    @Test void acceptedOwnershipSurvivesRecoverableCleanupFailure() {
        assertTrue(dispatch(()->{receipt();throw new IllegalStateException("cleanup");}));
        assertEquals(1,commits.get());assertEquals(0,uncertain.get());assertFalse(receipt());
    }
    @Test void unknownExceptionNeverBecomesRefundableRejection() {
        assertThrows(IllegalStateException.class,()->dispatch(()->{throw new IllegalStateException("unknown ownership");}));
        assertEquals(0,commits.get());assertEquals(1,uncertain.get());assertFalse(receipt());
    }
    @Test void nestedHooksShareOneCommit() {
        assertTrue(dispatch(()->dispatch(()->{receipt();return true;})));
        assertEquals(1,commits.get());assertEquals(0,uncertain.get());
    }
    @Test void unrelatedNestedJobRestoresOuterScope() {
        var other=new Object();var inner=new AtomicInteger();
        assertTrue(dispatch(()->{
            assertTrue(CertifiedDispatchScope.dispatch(other,pattern,new Object(),inner::incrementAndGet,
                    ()->fail("inner uncertain"),()->true));
            assertTrue(receipt());return true;
        }));
        assertEquals(1,commits.get());assertEquals(1,inner.get());
    }
    @Test void copiedHolderArrayOrDifferentPatternCannotConfirmOwnership() {
        assertFalse(dispatch(()->{
            assertFalse(CertifiedDispatchScope.confirmAccepted(pattern,new Object()));
            assertFalse(CertifiedDispatchScope.confirmAccepted(new Object(),inputs));return false;
        }));assertEquals(0,commits.get());
    }
    @Test void anotherThreadCannotConfirmThisThreadsAttempt() {
        var seen=new AtomicBoolean(true);
        assertFalse(dispatch(()->{
            var thread=new Thread(()->seen.set(receipt()));thread.start();
            try {thread.join();}catch(InterruptedException e){throw new AssertionError(e);}
            return false;
        }));assertFalse(seen.get());
    }
    @Test void fatalErrorsPropagateAndNeverBecomeOrdinaryRejections() {
        assertThrows(AssertionError.class,()->dispatch(()->{throw new AssertionError("fatal");}));
        assertEquals(1,uncertain.get());assertFalse(receipt());
    }
    @Test void failedCommitCannotBeRetriedOrRefunded() {
        assertThrows(IllegalStateException.class,()->CertifiedDispatchScope.dispatch(owner,pattern,inputs,
                ()->{throw new IllegalStateException("cursor changed");},uncertain::incrementAndGet,()->true));
        assertEquals(1,uncertain.get());assertFalse(receipt());
    }
    @Test void reentrantAttemptAfterReceiptDoesNotPushInputsAgain() {
        var pushes=new AtomicInteger();
        assertTrue(dispatch(()->{receipt();return dispatch(()->{pushes.incrementAndGet();return true;});}));
        assertEquals(0,pushes.get());assertEquals(1,commits.get());
    }
}
