package com.aecraftroute.addon.route.client;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RouteTreeMouseCacheTest {
    boolean changed(RouteTreeMouseCache cache,int[] a) {
        return cache.changed(a[0],a[1],a[2],a[3],a[4],a[5],a[6],a[7],a[8],a[9]/100f);
    }
    @Test void unchangedFrameReusesPointButEveryNativeCoordinateDependencyInvalidatesIt() {
        int[] initial={100,120,10,20,30,40,30,30,8,100};
        var cache=new RouteTreeMouseCache();
        assertTrue(changed(cache,initial));
        for(int frame=0;frame<10000;frame++)assertFalse(changed(cache,initial));
        for(int dependency=0;dependency<initial.length;dependency++) {
            var moved=initial.clone();moved[dependency]++;
            assertTrue(changed(cache,moved),"dependency "+dependency);
            assertFalse(changed(cache,moved));assertTrue(changed(cache,initial));
        }
    }
    @Test void installedNativeHitTestHasNoUntrackedCoordinateDependencies() throws Exception {
        String owner="com/neuvillette/ae2ct/gui/CraftingTreeWidget";
        var node=new ClassNode();
        try(var bytes=getClass().getClassLoader().getResourceAsStream(owner+".class")) {
            assertNotNull(bytes);new ClassReader(bytes).accept(node,0);
        }
        var method=node.methods.stream().filter(m->m.name.equals("getMousePoint") && m.desc.equals("(DD)Ljava/awt/Point;")).findFirst().orElseThrow();
        var fields=new HashSet<String>();var calls=new HashSet<String>();
        for(var insn:method.instructions) {
            if(insn instanceof FieldInsnNode field) {
                assertEquals(Opcodes.GETFIELD,field.getOpcode());assertEquals(owner,field.owner);fields.add(field.name);
            }
            if(insn instanceof MethodInsnNode call)calls.add(call.owner+"."+call.name+call.desc);
        }
        assertEquals(Set.of("screen","outputX","outputY","spacingX","spacingY","stackLength","scroll"),fields);
        assertEquals(Set.of(owner+".isMouseOutScreen(DD)Z",
                "appeng/client/gui/AEBaseScreen.getGuiLeft()I","appeng/client/gui/AEBaseScreen.getGuiTop()I",
                "java/awt/Point.<init>(II)V"),calls);
    }
}
