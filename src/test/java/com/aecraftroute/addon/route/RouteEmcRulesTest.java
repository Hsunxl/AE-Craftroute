package com.aecraftroute.addon.route;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RouteEmcRulesTest {
    private RouteView.Draft emc() {return RouteEmcRules.draft(new RouteView.Stack("star","下界之星",64),139264);}
    @Test void recognizesOnlyReservedRouteNamespace() {
        assertTrue(RouteEmcRules.isEmc(emc()));
        assertFalse(RouteEmcRules.isEmc((RouteView.Draft)null));
        assertFalse(RouteEmcRules.isEmc("projecte:collectors/fuel"));
        assertFalse(RouteEmcRules.isEmc("minecraft:emc"));
    }
    @Test void unitCostAndDemandProduceExactTotal() {
        assertEquals(BigInteger.valueOf(278528),RouteEmcRules.cost(139264,2));
        assertEquals(BigInteger.ZERO,RouteEmcRules.cost(139264,0));
        assertEquals(139264,RouteEmcRules.unit(emc()));
    }
    @Test void largePlansNeverOverflowLong() {
        var max=BigInteger.valueOf(Long.MAX_VALUE);
        assertEquals(max.multiply(max),RouteEmcRules.cost(Long.MAX_VALUE,Long.MAX_VALUE));
    }
    @Test void rejectsInvalidPricesOrNegativeDemand() {
        assertThrows(IllegalArgumentException.class,()->RouteEmcRules.cost(0,1));
        assertThrows(IllegalArgumentException.class,()->RouteEmcRules.cost(-1,1));
        assertThrows(IllegalArgumentException.class,()->RouteEmcRules.cost(1,-1));
    }
    @Test void routeHasOneOutputAndNoFabricatedIngredient() {
        var draft=emc();
        assertTrue(draft.inputs().isEmpty());assertEquals(1,draft.outputs().getFirst().amount());
        assertEquals("star",draft.outputs().getFirst().key());
        assertEquals("EMC 转换",draft.category());
    }
    @Test void emcRouteCannotBeEncodedEvenWithForgedInputs() {
        var d=emc();var input=new RouteView.Stack("iron","iron",1);
        var forged=new RouteView.Draft(d.recipeId(),d.category(),List.of(input),d.outputs(),d.note());
        assertTrue(assertThrows(IllegalArgumentException.class,()->RoutePatternRules.validate(forged)).getMessage().contains("EMC"));
    }
    @Test void normalProcessingRemainsValid() {
        var stack=new RouteView.Stack("iron","iron",1);
        assertDoesNotThrow(()->RoutePatternRules.validate(new RouteView.Draft("test:processing","machine",List.of(stack),List.of(stack),"")));
    }
    @Test void emcSnapshotRoundTripsAndKeepsCost() {
        var draft=emc();
        assertEquals(draft,RouteNetwork.GSON.fromJson(RouteNetwork.GSON.toJson(draft),RouteView.Draft.class));
    }
    @Test void replacingNormalRecipeWithEmcKeepsFullOutputDemand() {
        var draft=emc();
        var choice=new RouteView.Choice("jei:"+draft.recipeId(),draft.inputs(),draft.outputs(),false);
        assertEquals(1,RouteGraphMath.outputAmount(choice,"star"));
        assertEquals(64,RouteGraphMath.runs(64,RouteGraphMath.outputAmount(choice,"star")));
    }
}
