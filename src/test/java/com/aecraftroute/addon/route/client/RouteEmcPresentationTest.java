package com.aecraftroute.addon.route.client;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;
class RouteEmcPresentationTest {
    @Test void numericCurrencyAndTransmutationMachineAreDifferentTypes() {
        assertTrue(RouteEmcPresentation.isCurrencyType("appliede:emc"));
        assertTrue(RouteEmcPresentation.isTransmutationDevice("projecte:transmutation_table"));
        assertTrue(RouteEmcPresentation.isTransmutationDevice("projecte:transmutation_tablet"));
        assertFalse(RouteEmcPresentation.isTransmutationDevice("appliede:emc"));
        assertFalse(RouteEmcPresentation.isCurrencyType("projecte:transmutation_table"));
        assertFalse(RouteEmcPresentation.isCurrencyType("ae2:item"));assertFalse(RouteEmcPresentation.isCurrencyType("other:emc"));
    }
    @Test void onlyLeafCurrencyCanBecomeAnInlineCost() {
        assertTrue(RouteEmcPresentation.inline(true,false,false));assertFalse(RouteEmcPresentation.inline(false,false,false));
        assertFalse(RouteEmcPresentation.inline(true,true,false));assertFalse(RouteEmcPresentation.inline(true,false,true));
    }
    @Test void costCannotOverflowOrChangePersonalKnowledgeAndBalance() {
        var max=BigInteger.valueOf(Long.MAX_VALUE);
        assertEquals(max.multiply(max),RouteEmcPresentation.cost(Long.MAX_VALUE,Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()->RouteEmcPresentation.cost(-1,3));
    }
}
