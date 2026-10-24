package com.aecraftroute.addon.route.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouteEmcAccessTest {
    @Test void noTargetDoesNotRequireProjectEOrMinecraftStartup() {
        assertFalse(RouteEmcAccess.query(null).available());
    }
    @Test void absentOrZeroValueDoesNotAdvertiseFreeConversion() {
        assertFalse(new RouteEmcAccess.State(0,null,null).available());
        assertFalse(new RouteEmcAccess.State(-1,null,null).available());
        assertTrue(new RouteEmcAccess.State(Long.MAX_VALUE,null,null).available());
    }
    @Test void emptyBrowserKeepsNormalNavigationAndNoEmcOption() {
        var browser=new RouteJeiRecipes(null);
        assertTrue(browser.options().isEmpty());assertNull(browser.currentDraft());
        assertFalse(browser.selectEmc());
        browser.page(1);browser.page(-1);browser.selectIndex(0);
        assertTrue(browser.options().isEmpty());assertNull(browser.currentDraft());
    }
    @Test void emcOnlyTargetIsSelectableWithoutANativeJeiRecipe() throws Exception {
        var browser=new RouteJeiRecipes(null);
        var state=RouteJeiRecipes.class.getDeclaredField("emc");state.setAccessible(true);
        state.set(browser,new RouteEmcAccess.State(128,true,java.math.BigInteger.valueOf(1024)));
        assertEquals(1,browser.options().size());
        assertEquals("EMC 转换",browser.options().getFirst().label());
        assertEquals(0,browser.options().getFirst().index());
        assertDoesNotThrow(()->{browser.selectIndex(0);browser.page(1);browser.page(-1);browser.focusRecipe("aecraftroute:emc/test");});
        var index=RouteJeiRecipes.class.getDeclaredField("index");index.setAccessible(true);
        assertEquals(0,index.getInt(browser));
    }
}
