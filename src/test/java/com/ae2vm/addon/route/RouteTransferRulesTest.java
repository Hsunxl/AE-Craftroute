package com.ae2vm.addon.route;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RouteTransferRulesTest {
    @Test void nativeInsertSucceedsBeforeCacheDebit() {
        var order=new ArrayList<String>();
        RouteTransferRules.transferOne(1,true,true,()->{order.add("simulate");return true;},()->{order.add("insert");return true;},()->order.add("debit"));
        assertEquals(List.of("simulate","insert","debit"),order);
    }
    @Test void occupiedDestinationNeverSimulatesOrInsertsOrDebits() {
        assertThrows(IllegalArgumentException.class,()->RouteTransferRules.transferOne(1,false,true,()->fail(),()->fail(),()->fail()));
    }
    @Test void rejectedFilterNeverCallsNativeInventory() {
        assertThrows(IllegalArgumentException.class,()->RouteTransferRules.transferOne(1,true,false,()->fail(),()->fail(),()->fail()));
    }
    @Test void failedSimulationLeavesCacheUntouched() {
        assertThrows(IllegalArgumentException.class,()->RouteTransferRules.transferOne(1,true,true,()->false,()->fail(),()->fail()));
    }
    @Test void failedInsertionLeavesCacheUntouched() {
        assertThrows(IllegalArgumentException.class,()->RouteTransferRules.transferOne(1,true,true,()->true,()->false,()->fail()));
    }
    @Test void malformedStackCannotEnterTransfer() {
        for(int count:new int[]{0,-1,2,64})assertThrows(IllegalArgumentException.class,()->RouteTransferRules.transferOne(count,true,true,()->fail(),()->fail(),()->fail()));
    }
    @Test void staleTokenAndWrongSlotRejected() {
        assertDoesNotThrow(()->RouteTransferRules.validateToken(0,18,"a","a"));
        for(int slot:new int[]{-1,18,999})assertThrows(IllegalArgumentException.class,()->RouteTransferRules.validateToken(slot,18,"a","a"));
        assertThrows(IllegalArgumentException.class,()->RouteTransferRules.validateToken(0,18,"old","new"));
        assertThrows(IllegalArgumentException.class,()->RouteTransferRules.validateToken(0,18,null,"new"));
    }
    @Test void duplicateUploadCannotDebitTwice() {
        var tokens=new HashMap<Integer,String>();tokens.put(0,"token");var received=new AtomicInteger();
        Runnable upload=()->{
            RouteTransferRules.validateToken(0,18,"token",tokens.get(0));
            RouteTransferRules.transferOne(1,true,true,()->true,()->{received.incrementAndGet();return true;},()->tokens.remove(0));
        };
        upload.run();assertThrows(IllegalArgumentException.class,upload::run);assertEquals(1,received.get());
    }
    @Test void pagingAndUploadAreBounded() {
        assertThrows(IllegalArgumentException.class,()->new RouteWorkbench.ViewRequest(-1,0,8));
        assertThrows(IllegalArgumentException.class,()->new RouteWorkbench.ViewRequest(0,0,19));
        assertThrows(IllegalArgumentException.class,()->new RouteWorkbench.Upload(18,"x","y",0,null));
        var token=UUID.randomUUID().toString();
        assertEquals(RouteWorkbench.ViewRequest.first(),new RouteWorkbench.Upload(0,token,token,0,null).view());
    }
}
