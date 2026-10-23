package com.ae2vm.addon.route;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoutePayloadTest {
    RegistryFriendlyByteBuf buffer(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);}
    @Test void emptyTargetQueryRetainsMenuAndRevision(){
        var b=buffer();try{var p=new RouteNetwork.Request(42,987,"query",null,"");RouteNetwork.Request.CODEC.encode(b,p);assertEquals(p,RouteNetwork.Request.CODEC.decode(b));}finally{b.release();}
    }
    @Test void responsePreservesChineseTextAndSequence(){
        var b=buffer();try{var p=new RouteNetwork.Response(9,100,"{\"message\":\"锁定外部产粉路线\"}");RouteNetwork.Response.CODEC.encode(b,p);assertEquals(p,RouteNetwork.Response.CODEC.decode(b));}finally{b.release();}
    }
    @Test void rejectsOversizedAction(){var b=buffer();try{assertThrows(RuntimeException.class,()->RouteNetwork.Request.CODEC.encode(b,new RouteNetwork.Request(1,1,"x".repeat(17),null,"")));}finally{b.release();}}
    @Test void rejectsOversizedFingerprint(){var b=buffer();try{assertThrows(RuntimeException.class,()->RouteNetwork.Request.CODEC.encode(b,new RouteNetwork.Request(1,1,"lock",null,"x".repeat(129))));}finally{b.release();}}
    @Test void encodingHasSeparateBoundedPayload() {
        var b=buffer();try {
            var p=new RouteNetwork.Request(1,2,"encode",null,"x".repeat(24000));
            RouteNetwork.Request.CODEC.encode(b,p);assertEquals(p,RouteNetwork.Request.CODEC.decode(b));
            assertThrows(RuntimeException.class,()->new RouteNetwork.Request(1,2,"encode",null,"x".repeat(24001)));
        }finally{b.release();}
    }
    @Test void workbenchUploadAndViewHaveBoundedPayloads() {
        for(String action:java.util.List.of("workbench","takecache","upload")) {
            var b=buffer();try {
                var p=new RouteNetwork.Request(1,2,action,null,"x".repeat(1024));
                RouteNetwork.Request.CODEC.encode(b,p);assertEquals(p,RouteNetwork.Request.CODEC.decode(b));
                assertThrows(RuntimeException.class,()->new RouteNetwork.Request(1,2,action,null,"x".repeat(1025)));
            }finally{b.release();}
        }
    }

    /** A whole canvas must not be truncated to the single-lock payload limit. */
    @Test void wholeCanvasApplyGetsItsOwnLargePayload() {
        var b=buffer();try {
            var p=new RouteNetwork.Request(1,2,"apply",null,"x".repeat(24000));
            RouteNetwork.Request.CODEC.encode(b,p);assertEquals(p,RouteNetwork.Request.CODEC.decode(b));
            assertThrows(RuntimeException.class,()->new RouteNetwork.Request(1,2,"apply",null,"x".repeat(24001)));
        }finally{b.release();}
    }

    /** ...but a plain single-material op must still be small, so apply cannot loosen everything. */
    @Test void singleLockStaysOnTheSmallPayloadLimit() {
        assertDoesNotThrow(()->new RouteNetwork.Request(1,2,"lock",null,"x".repeat(128)));
        assertThrows(RuntimeException.class,()->new RouteNetwork.Request(1,2,"lock",null,"x".repeat(129)));
        assertThrows(RuntimeException.class,()->new RouteNetwork.Request(1,2,"apply",null,"x".repeat(24001)));
        assertThrows(RuntimeException.class,()->new RouteNetwork.Request(1,2,"query",null,"x".repeat(129)));
    }
}
