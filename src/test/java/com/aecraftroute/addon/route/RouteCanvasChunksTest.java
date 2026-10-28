package com.aecraftroute.addon.route;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RouteCanvasChunksTest {
    @Test void largeUnicodeCanvasIsReassembledExactly() {
        var nodes=new ArrayList<RouteApply.Node>();
        for(int i=0;i<1000;i++)nodes.add(new RouteApply.Node("k"+i,"c"+i,"测试😀","root/"+i));
        String wire=RouteCanvasPayload.encode(100,nodes);
        assertTrue(wire.length()>RouteCanvasPayload.MAX_CHARACTERS);
        var parts=RouteCanvasChunks.split(wire);assertTrue(parts.size()>1);
        var chunks=new RouteCanvasChunks<String>();var player=UUID.randomUUID();String result=null;
        for(int i=0;i<parts.size();i++) {
            var piece=parts.get(i);assertTrue(piece.length()<=RouteCanvasChunks.CHUNK_CHARS);
            assertFalse(Character.isHighSurrogate(piece.charAt(piece.length()-1)));
            result=chunks.accept(player,1,2,"key",i,parts.size(),piece,1);
            if(i<parts.size()-1)assertNull(result);
        }
        assertEquals(wire,result);assertEquals(nodes.size(),RouteCanvasPayload.decode(result).nodes().size());
    }
    @Test void outOfOrderCrossSessionAndExpiredTransfersCannotCommit() {
        var b=new RouteCanvasChunks<String>();var id=UUID.randomUUID();
        assertNull(b.accept(id,1,2,"k",0,2,"a",0));
        assertThrows(IllegalArgumentException.class,()->b.accept(id,1,2,"k",1,2,"b",1+60_000_000_001L));
        assertNull(b.accept(id,1,3,"k",0,2,"a",2));
        assertThrows(IllegalArgumentException.class,()->b.accept(id,2,3,"k",1,2,"b",3));
        assertThrows(IllegalArgumentException.class,()->b.accept(id,1,3,"k",1,2,"b",4));
    }
    @Test void packetCodecCarriesChunkActionWithoutTruncation() {
        var request=new RouteNetwork.Request(9,17,"apply_chunk",null,"0|1|"+"材😀".repeat(300));
        var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY);
        try {
            RouteNetwork.Request.CODEC.encode(buffer,request);
            assertEquals(request,RouteNetwork.Request.CODEC.decode(buffer));
        } finally {buffer.release();}
    }
}
