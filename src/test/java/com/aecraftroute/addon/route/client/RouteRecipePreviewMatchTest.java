package com.aecraftroute.addon.route.client;
import com.aecraftroute.addon.route.RouteView;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class RouteRecipePreviewMatchTest {
    private RouteView.Stack stack(String key,long n) {return new RouteView.Stack(key,"同名材料",n);}
    private RouteView.Choice choice(List<RouteView.Stack> in,List<RouteView.Stack> out,boolean alternatives) {return new RouteView.Choice("native-hash",in,out,alternatives);}
    private RouteView.Draft draft(List<RouteView.Stack> in,List<RouteView.Stack> out) {return new RouteView.Draft("mod:recipe","机器",in,out,"");}
    @Test void exactInputsOutputsCanDisplayEquivalentJeiRecipe() {
        var in=List.of(stack("a",9));var out=List.of(stack("b",1));
        assertTrue(RouteRecipePreviewMatch.matches(choice(in,out,false),draft(in,out)));
    }
    @Test void repeatedSlotsAndOrderDoNotChangeTotals() {
        var out=List.of(stack("b",1));
        assertTrue(RouteRecipePreviewMatch.matches(choice(List.of(stack("a",2),stack("c",1),stack("a",3)),out,false),draft(List.of(stack("c",1),stack("a",5)),out)));
    }
    @Test void matchingOutputAloneNeverSuffices() {
        var out=List.of(stack("b",1));var p=choice(List.of(stack("a",9)),out,false);
        assertFalse(RouteRecipePreviewMatch.matches(p,draft(List.of(stack("a",8)),out)));
        assertFalse(RouteRecipePreviewMatch.matches(p,draft(List.of(stack("other",9)),out)));
        assertFalse(RouteRecipePreviewMatch.matches(p,draft(List.of(stack("a",9)),List.of(stack("b",2)))));
    }
    @Test void secondaryOutputsAndAlternativesCannotBeDropped() {
        var in=List.of(stack("a",1));var out=List.of(stack("b",1));
        assertFalse(RouteRecipePreviewMatch.matches(choice(in,out,true),draft(in,out)));
        assertFalse(RouteRecipePreviewMatch.matches(choice(in,List.of(stack("b",1),stack("waste",1)),false),draft(in,out)));
    }
    @Test void malformedOrOverflowingDataDoesNotCrashPreview() {
        var out=List.of(stack("b",1));var d=draft(List.of(stack("a",1)),out);
        assertFalse(RouteRecipePreviewMatch.matches(null,d));
        assertFalse(RouteRecipePreviewMatch.matches(choice(List.of(stack("a",1)),null,false),d));
        assertFalse(RouteRecipePreviewMatch.matches(choice(List.of(stack("a",Long.MAX_VALUE),stack("a",1)),out,false),d));
        assertFalse(RouteRecipePreviewMatch.matches(choice(List.of(stack("a",0)),out,false),d));
    }
}
