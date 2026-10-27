package com.aecraftroute.addon.route.client;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RouteRecipeGroupsTest {
    RouteRecipeGroups.Slot slot(String key,long amount) {return new RouteRecipeGroups.Slot("INPUT",List.of(new RouteRecipeGroups.Ingredient(key,amount)));}
    RouteRecipeGroups.Key key(String id,String item,long amount) {return new RouteRecipeGroups.Key(id,List.of(slot(item,amount)));}
    @Test void sameRecipeOnThreeMachinesBecomesOneRowWithoutLosingMembers() {
        var groups=RouteRecipeGroups.group(List.of("machineA","machineB","machineC"),m->key("mod:recipe","iron",1));
        assertEquals(List.of(List.of("machineA","machineB","machineC")),groups);
    }
    @Test void sameDisplayTitleOrSameOutputDoesNotMergeDifferentRecipes() {
        assertEquals(2,RouteRecipeGroups.group(List.of("recipeA","recipeB"),id->key(id,"iron",1)).size());
    }
    @Test void quantityComponentsAndAlternativesAreNotDiscarded() {
        assertNotEquals(key("a","iron",1),key("a","iron",2));
        assertNotEquals(key("a","item{components:A}",1),key("a","item{components:B}",1));
        var a=new RouteRecipeGroups.Slot("INPUT",List.of(new RouteRecipeGroups.Ingredient("iron",1),new RouteRecipeGroups.Ingredient("gold",1)));
        assertNotEquals(a,slot("iron",1));
    }
    @Test void alternativeOrderIsCanonicalButSlotOrderRemainsMeaningful() {
        var a=new RouteRecipeGroups.Ingredient("a",1);var b=new RouteRecipeGroups.Ingredient("b",1);
        assertEquals(new RouteRecipeGroups.Slot("INPUT",List.of(a,b)),new RouteRecipeGroups.Slot("INPUT",List.of(b,a,a)));
        assertNotEquals(new RouteRecipeGroups.Key("id",List.of(slot("a",1),slot("b",1))),new RouteRecipeGroups.Key("id",List.of(slot("b",1),slot("a",1))));
    }
    @Test void absentIdentityAndUnsupportedLayoutStaySeparate() {
        assertEquals(3,RouteRecipeGroups.group(List.of(1,2,3),entry->null).size());
        assertEquals(3,RouteRecipeGroups.group(List.of(1,2,3),entry->key("","a",1)).size());
    }
    @Test void groupOrderAndFirstRepresentativeStayStable() {
        assertEquals(List.of(List.of(1,3),List.of(2,4)),RouteRecipeGroups.group(List.of(1,2,3,4),n->key("id"+(n%2),"a",1)));
    }
    @Test void machineUnionUsesIdentityNotTranslatedDisplayName() {
        record Machine(String id,String name) {}
        var a=new Machine("machineA","合成");var b=new Machine("machineB","合成");
        assertEquals(List.of(a,b),RouteRecipeGroups.unique(List.of(a,b,a),Machine::id));
    }
}
