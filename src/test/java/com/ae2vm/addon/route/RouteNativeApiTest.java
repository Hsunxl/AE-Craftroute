package com.ae2vm.addon.route;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual compiled integration calls; these tests do not replace in-game input validation. */
class RouteNativeApiTest {
    private Set<String> calls(String name) throws IOException {
        var result=new HashSet<String>();
        try(var in=getClass().getResourceAsStream("/com/ae2vm/addon/route/"+name+".class")) {
            assertNotNull(in);new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e) {
                    return new MethodVisitor(Opcodes.ASM9) {public void visitMethodInsn(int op,String owner,String method,String desc,boolean itf) {result.add(owner+"."+method);}};
                }
            },0);
        }
        return result;
    }
    @Test void canvasIsAPlainScreenSoJeiCanDrawItsIngredientList() throws Exception {
        try(var in=getClass().getResourceAsStream("/com/ae2vm/addon/route/client/RouteScreen.class")) {
            // JEI's ScreenEvent.Render.Post handler explicitly skips AbstractContainerScreen.
            assertEquals("net/minecraft/client/gui/screens/Screen",new ClassReader(in).getSuperName());
        }
    }
    @Test void materialsUseRegisteredAddonConverters() throws Exception {
        var c=calls("client/RouteJeiConverters");
        assertTrue(c.contains("tamaized/ae2jeiintegration/api/integrations/jei/IngredientConverters.getConverter"));
        assertTrue(c.contains("tamaized/ae2jeiintegration/api/integrations/jei/IngredientConverter.getStackFromIngredient"));
    }
    @Test void canvasAndProviderIconsUseAe2RegisteredRenderers() throws Exception {
        for(var name:List.of("client/RouteScreen","client/RouteWorkbenchPanel"))
            assertTrue(calls(name).contains("appeng/api/client/AEKeyRendering.drawInGui"));
    }
    @Test void encoderCallsBothNativePatternEncoders() throws Exception {
        var c=calls("RoutePatternEncoder");
        assertTrue(c.contains("appeng/api/crafting/PatternDetailsHelper.encodeProcessingPattern"));
        assertTrue(c.contains("appeng/api/crafting/PatternDetailsHelper.encodeCraftingPattern"));
    }
    @Test void screenNavigationUsesPlainSetScreenPath() throws Exception {
        assertTrue(calls("client/RouteClient").stream().anyMatch(c->c.endsWith(".setScreen")));
        assertTrue(calls("client/RouteScreen").stream().anyMatch(c->c.endsWith(".setScreen")));
        assertFalse(calls("client/RouteClient").stream().anyMatch(c->c.endsWith(".switchToScreen")));
    }
    @Test void canvasKeepsVanillaRenderHooksForJeiBackground() throws Exception {
        var c=calls("client/RouteScreen");
        assertTrue(c.contains("net/minecraft/client/gui/screens/Screen.render"));
        assertTrue(c.contains("net/minecraft/client/gui/screens/Screen.renderBackground"));
    }
    @Test void backwardsFromFirstDeviceWrapsToLast() {assertEquals(99,RouteNavigation.cycle(0,-1,100));}
    @Test void forwardsFromLastDeviceWrapsToFirst() {assertEquals(0,RouteNavigation.cycle(99,1,100));}
    @Test void multipleWheelStepsAndEmptyListsAreSafe() {
        assertEquals(97,RouteNavigation.cycle(0,-3,100));assertEquals(0,RouteNavigation.cycle(0,-1,0));
    }
}
