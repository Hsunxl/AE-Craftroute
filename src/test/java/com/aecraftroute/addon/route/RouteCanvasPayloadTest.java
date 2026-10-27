package com.aecraftroute.addon.route;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RouteCanvasPayloadTest {
    List<RouteApply.Node> nodes(String name) {
        var nodes=new ArrayList<RouteApply.Node>();
        for(int i=0;i<RouteCanvasPayload.MAX_NODES;i++)nodes.add(new RouteApply.Node(
                "{id:\"minecraft:iron_ingot\"}","a".repeat(64),name,"root/"+i));
        return nodes;
    }
    @Test void compactLargeCanvasPreservesEveryConstraintAndAmountWithoutTruncating() {
        var nodes=nodes("长材料提示名称".repeat(80));
        String wire=RouteCanvasPayload.encode(100_000,nodes);
        assertTrue(wire.length()<=RouteCanvasPayload.MAX_CHARACTERS);
        assertDoesNotThrow(()->new RouteNetwork.Request(1,2,"apply",null,wire));
        var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY);
        try {
            var request=new RouteNetwork.Request(1,2,"apply",null,wire);
            RouteNetwork.Request.CODEC.encode(buffer,request);
            assertEquals(request,RouteNetwork.Request.CODEC.decode(buffer));
        } finally {buffer.release();}
        var json=JsonParser.parseString(wire).getAsJsonObject();
        assertEquals(100_000,json.get("amount").getAsLong());
        var received=json.getAsJsonArray("nodes");assertEquals(nodes.size(),received.size());
        for(int i=0;i<nodes.size();i++) {
            var actual=received.get(i).getAsJsonObject();var expected=nodes.get(i);
            assertEquals(expected.keyTag(),actual.get("k").getAsString());
            assertEquals(expected.choiceId(),actual.get("c").getAsString());
            assertEquals(expected.path(),actual.get("p").getAsString());assertFalse(actual.has("n"));
        }
    }
    @Test void ordinaryCanvasKeepsDiagnosticNames() {
        var json=JsonParser.parseString(RouteCanvasPayload.encode(1,List.of(nodes("铁锭").getFirst()))).getAsJsonObject();
        assertEquals("铁锭",json.getAsJsonArray("nodes").get(0).getAsJsonObject().get("n").getAsString());
    }
    @Test void omittedDiagnosticNameStillReportsTheExactPathOnServerFailure() {
        var base=new RoutePolicy<appeng.api.stacks.AEKey>(Map.of());
        var result=RouteApply.prepare(null,base,null,null,1,List.of(new RouteApply.Node(null,"id",null,"root/3")));
        assertSame(base,result.policy());
        assertTrue(result.problems().getFirst().contains("root/3"));
        assertFalse(result.problems().getFirst().contains("「null」"));
    }
    @Test void tooManyBranchesOrOversizeConstraintsAreRejectedRatherThanSilentlyWeakened() {
        var nodes=nodes("label");nodes.add(nodes.getFirst());
        assertThrows(IllegalArgumentException.class,()->RouteCanvasPayload.encode(1,nodes));
        var large=List.of(new RouteApply.Node("x".repeat(24001),"id","name","root"));
        var error=assertThrows(IllegalArgumentException.class,()->RouteCanvasPayload.encode(1,large));
        assertTrue(error.getMessage().contains("未应用任何分支"));
    }
}
