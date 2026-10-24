package com.aecraftroute.addon.route;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RoutePlanningRequestsTest {
    @Test void newCanvasHandshakeResetsOldHighSequenceForNewOrReusedMenu() {
        for(String session:new String[]{"different menu","same menu reopened"}) {
            var requests=new RoutePlanningRequests<String>();var old=requests.replace("player",9000);var worker=new CompletableFuture<>();requests.bind("player",old,worker);
            requests.begin("player");var fresh=requests.replace("player",1);
            assertTrue(worker.isCancelled(),session);assertNotNull(fresh,session);assertTrue(requests.current("player",fresh),session);
        }
    }
    @Test void replacementCancelsOldRequestBeforeItsSynchronousCallbackCanCommit() {
        var requests=new RoutePlanningRequests<String>();var commits=new AtomicInteger();
        var old=requests.replace("player",1);var first=new CompletableFuture<Void>();requests.bind("player",old,first);
        first.whenComplete((v,e)->{if(requests.current("player",old))commits.incrementAndGet();requests.forget("player",old);});
        var current=requests.replace("player",2);
        assertTrue(first.isCancelled());assertTrue(requests.current("player",current));assertEquals(0,commits.get());
    }
    @Test void oldOutOfOrderRequestDoesNotCancelNewerSequence() {
        var requests=new RoutePlanningRequests<String>();var newer=requests.replace("p",10);var work=new CompletableFuture<>();requests.bind("p",newer,work);
        assertNull(requests.replace("p",9));assertFalse(work.isCancelled());assertTrue(requests.current("p",newer));
    }
    @Test void cancellationBeforeFutureAttachmentStillCancelsActualWork() {
        var requests=new RoutePlanningRequests<String>();var ticket=requests.replace("p",1);requests.cancel("p");
        var work=new CompletableFuture<>();requests.bind("p",ticket,work);assertTrue(work.isCancelled());assertEquals(0,requests.size());
    }
    @Test void logoutAndServerStopRemoveLeasesBeforeCallbacksAndClearEveryPlayer() {
        var requests=new RoutePlanningRequests<String>();var a=requests.replace("a",1);var b=requests.replace("b",1);
        var first=new CompletableFuture<>();var second=new CompletableFuture<>();requests.bind("a",a,first);requests.bind("b",b,second);
        var commits=new AtomicInteger();
        first.whenComplete((v,e)->{if(requests.current("a",a))commits.incrementAndGet();});
        second.whenComplete((v,e)->{if(requests.current("b",b))commits.incrementAndGet();});
        requests.clear();assertTrue(first.isCancelled());assertTrue(second.isCancelled());assertEquals(0,commits.get());assertEquals(0,requests.size());
    }
    @Test void disabledProxyRejectsApplyBeforeSubmittingAnythingButAllowsReadOnlyPreview() {
        var error=assertThrows(IllegalArgumentException.class,()->RouteRequestRules.requirePlanningEnabled("apply",false));
        assertTrue(error.getMessage().contains("未应用"));
        assertDoesNotThrow(()->RouteRequestRules.requirePlanningEnabled("preview",false));
        assertDoesNotThrow(()->RouteRequestRules.requirePlanningEnabled("apply",true));
    }
}
