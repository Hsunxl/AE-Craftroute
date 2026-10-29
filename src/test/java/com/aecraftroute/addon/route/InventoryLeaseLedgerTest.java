package com.aecraftroute.addon.route;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryLeaseLedgerTest {
    final AtomicLong now=new AtomicLong();
    final InventoryLeaseLedger<String> ledger=new InventoryLeaseLedger<>(now::get);
    @Test void differentTargetsCannotSpendTheSameSeed() {
        assertNotNull(ledger.replace("a",Map.of("seed",1L),Map.of("seed",1L),10));
        assertNull(ledger.replace("b",Map.of("seed",1L),Map.of("seed",1L),10));
        assertEquals(1,ledger.availableTo("a",Map.of("seed",1L)).get("seed"));
        assertEquals(0,ledger.availableTo("b",Map.of("seed",1L)).get("seed"));
    }
    @Test void replacementIsAtomicAndOldCallbacksCannotReleaseNewLease() {
        var first=ledger.replace("a",Map.of("x",4L),Map.of("x",2L),10);
        assertNull(ledger.replace("a",Map.of("x",4L),Map.of("x",5L),10));
        assertEquals(2,ledger.availableTo("b",Map.of("x",4L)).get("x"));
        var second=ledger.replace("a",Map.of("x",4L),Map.of("x",3L),10);
        assertFalse(ledger.release("a",first));assertTrue(ledger.release("a",second));
        assertFalse(ledger.release("a",second));
    }
    @Test void expiryAndGridEpochClearReleaseEverything() {
        var token=ledger.replace("a",Map.of("x",1L),Map.of("x",1L),10);
        now.set(10);assertFalse(ledger.release("a",token));
        assertNotNull(ledger.replace("b",Map.of("x",1L),Map.of("x",1L),10));
        ledger.clear();assertEquals(1,ledger.availableTo("a",Map.of("x",1L)).get("x"));
    }
    @Test void multiKeyFailureDoesNotPartiallyReserveAndPhysicalDrainIsClamped() {
        assertNull(ledger.replace("a",Map.of("x",2L),Map.of("x",1L,"y",1L),10));
        assertEquals(2,ledger.availableTo("b",Map.of("x",2L)).get("x"));
        ledger.replace("a",Map.of("x",Long.MAX_VALUE),Map.of("x",Long.MAX_VALUE),10);
        assertEquals(0,ledger.availableTo("b",Map.of("x",1L)).get("x"));
    }
    @Test void invalidAmountsCannotMutateAnExistingLease() {
        ledger.replace("a",Map.of("x",1L),Map.of("x",1L),10);
        assertThrows(IllegalArgumentException.class,()->ledger.replace("a",Map.of("x",1L),Map.of("x",0L),10));
        assertEquals(0,ledger.availableTo("b",Map.of("x",1L)).get("x"));
    }
}
