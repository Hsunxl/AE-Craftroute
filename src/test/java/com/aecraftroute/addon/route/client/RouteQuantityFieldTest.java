package com.aecraftroute.addon.route.client;
import com.aecraftroute.addon.route.RouteGraphMath;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class RouteQuantityFieldTest {
    @Test void onlyFinalProductCanBeEditedByAnyEntryPoint() {
        assertTrue(RouteQuantityField.editable(true,false));
        assertFalse(RouteQuantityField.editable(false,false));
        assertFalse(RouteQuantityField.editable(false,true));
        assertFalse(RouteQuantityField.editable(true,true));
    }
    @Test void readonlyDerivedFieldsNeverQualifyForWheelEditing() {
        for(boolean ctrl:new boolean[]{false,true})for(boolean horizontal:new boolean[]{false,true})
            assertFalse(RouteQuantityField.wheelEdits(RouteQuantityField.editable(false,false),ctrl,horizontal,false));
        assertTrue(RouteQuantityField.wheelEdits(RouteQuantityField.editable(true,false),false,false,false));
    }
    @Test void independentRowCannotCoverItemIconMachineOrLabels() {
        for(int width:new int[]{86,100,140}) {
            var field=RouteQuantityField.of(0,0,width);var machine=RouteMaterialPort.of(0,0,width);
            assertTrue(field.y()>=4+28+4);assertTrue(field.y()>=machine.y()+18);
            assertTrue(field.y()+field.height()<=RouteQuantityField.NAME_Y);
            assertTrue(RouteQuantityField.NAME_Y+9<=RouteQuantityField.STATE_Y);
            assertTrue(RouteQuantityField.STATE_Y+9<RouteQuantityField.NODE_HEIGHT);
            assertTrue(field.x()+field.width()<=width-4);
        }
    }
    @Test void rowTracksItsParentAndHitTestingHasExclusiveEdges() {
        var field=RouteQuantityField.of(40,80,86);
        assertTrue(field.contains(field.x(),field.y()));assertFalse(field.contains(field.x()+field.width(),field.y()));
        assertFalse(field.contains(field.x(),field.y()+field.height()));assertFalse(field.contains(80,84));
        var moved=RouteQuantityField.of(140,280,86);assertEquals(field.x()+100,moved.x());assertEquals(field.y()+200,moved.y());
    }
    @Test void wheelOnNumericRowEditsButControlOrHorizontalMotionEscapes() {
        assertTrue(RouteQuantityField.wheelEdits(true,false,false,false));
        assertFalse(RouteQuantityField.wheelEdits(false,false,false,false));
        assertFalse(RouteQuantityField.wheelEdits(true,true,false,false));
        assertFalse(RouteQuantityField.wheelEdits(true,false,true,false));
        assertFalse(RouteQuantityField.wheelEdits(true,false,false,true));
    }
    @Test void wheelContinuesFromTypedNumberInsteadOfOldDemand() {
        assertEquals("564",RouteQuantityField.stepText("500",1137,64,1,false));
        assertEquals("436",RouteQuantityField.stepText("500",1137,64,-1,false));
    }
    @Test void shiftIsFineAndEmptyInputUsesCurrentDerivedDemand() {
        assertEquals("501",RouteQuantityField.stepText("500",1137,64,1,true));
        assertEquals("499",RouteQuantityField.stepText("500",1137,64,-1,true));
        assertEquals("1201",RouteQuantityField.stepText("",1137,64,1,false));
    }
    @Test void scrollingCannotCreateZeroNegativeOrOverflowingAmounts() {
        assertEquals("1",RouteQuantityField.stepText("1",10,64,-1,false));
        assertEquals("1000000000000063",RouteQuantityField.stepText("999999999999999",10,64,1,false));
        assertEquals("8",RouteQuantityField.stepText("7",10,0,1,false));
    }
    @Test void combinedTypingAndWheelPreservesTheModelUntilCommit() {
        long model=1137;String pending="500";
        pending=RouteQuantityField.stepText(pending,model,64,1,false);
        pending=RouteQuantityField.stepText(pending,model,64,-1,true);
        assertEquals(1137,model);assertEquals(563L,RouteGraphMath.parseDemand(pending));
        // Escape discards pending, whereas Enter can apply exactly the parsed pending number.
    }
    @Test void tallerCardsStillRespectParentAndSiblingGeometry() {
        int h=RouteQuantityField.NODE_HEIGHT;
        var boxes=List.of(new RouteTreeGeometry.Box("root",0,100,0,100,h),
                new RouteTreeGeometry.Box("root/0",1,0,0,86,h),new RouteTreeGeometry.Box("root/1",1,0,0,86,h));
        var solved=RouteTreeGeometry.constrain(boxes);
        assertTrue(solved.get(1).y()>=h+28);assertTrue(solved.get(2).x()>=solved.get(1).x()+86+16);
    }
}
