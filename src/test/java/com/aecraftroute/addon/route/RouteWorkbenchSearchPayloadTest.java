package com.aecraftroute.addon.route;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouteWorkbenchSearchPayloadTest {
    @Test void oldJsonAndConstructorsDefaultToAnUnfilteredView() {
        assertEquals("",new RouteWorkbench.ViewRequest(0,0,8).query());
        assertEquals("",new RouteWorkbench.ViewRequest(0,0,8,4).query());
        var legacy=RouteNetwork.GSON.fromJson("{\"providerPage\":4,\"slotPage\":2,\"columns\":8}",RouteWorkbench.ViewRequest.class);
        assertEquals("",legacy.query());assertEquals(1,legacy.rows());assertEquals(4,legacy.providerPage());
    }
    @Test void filteredViewAndUploadRoundTripWithoutChangingTheAuthoritativeDestination() {
        var view=new RouteWorkbench.ViewRequest(4,3,8,5,"  高级 Advanced_AE  ");
        assertEquals("高级 Advanced_AE",view.query());
        assertEquals(view,RouteNetwork.GSON.fromJson(RouteNetwork.GSON.toJson(view),RouteWorkbench.ViewRequest.class));
        String token="00000000-0000-0000-0000-000000000001",provider="00000000-0000-0000-0000-000000000002";
        var upload=new RouteWorkbench.Upload(17,token,provider,511,view);
        var restored=RouteNetwork.GSON.fromJson(RouteNetwork.GSON.toJson(upload),RouteWorkbench.Upload.class);
        assertEquals(upload,restored);assertEquals(provider,restored.provider());assertEquals(token,restored.token());assertEquals(511,restored.slot());
        assertEquals(view.query(),restored.view().query());
    }
    @Test void rejectsOverlongQueriesAndDefaultsExplicitNull() {
        assertEquals("",new RouteWorkbench.ViewRequest(0,0,8,1,null).query());
        assertThrows(IllegalArgumentException.class,()->new RouteWorkbench.ViewRequest(0,0,8,1,"a".repeat(129)));
        assertThrows(RuntimeException.class,()->RouteNetwork.GSON.fromJson("{\"columns\":8,\"query\":\""+"a".repeat(129)+"\"}",RouteWorkbench.ViewRequest.class));
    }
    @Test void responseEchoesFilterForRejectingStaleProviderWindowsWithoutLosingCacheData() {
        var state=new RouteWorkbench.State(true,"",7,java.util.List.of(),java.util.List.of(),0,0,0,0,0,"目标供应器");
        assertEquals(state,RouteNetwork.GSON.fromJson(RouteNetwork.GSON.toJson(state),RouteWorkbench.State.class));
        assertEquals("",new RouteWorkbench.State(true,"",7,java.util.List.of(),java.util.List.of(),0,0,0,0,0).query());
    }
}
