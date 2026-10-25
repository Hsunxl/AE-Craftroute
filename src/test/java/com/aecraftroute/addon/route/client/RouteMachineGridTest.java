package com.aecraftroute.addon.route.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RouteMachineGridTest {
    @Test void ordinaryMachineSetsAllFitAtOnceWithinWindow() {
        for(int count:new int[]{1,3,10,24,64}) {
            var grid=RouteMachineGrid.of(180,50,18,count,320,240);
            assertTrue(grid.capacity()>=count);assertTrue(grid.width()>=48);assertTrue(grid.x()>=4);assertTrue(grid.y()>=4);
            assertTrue(grid.x()+grid.width()<=316);assertTrue(grid.y()+grid.height()<=236);
        }
    }
    @Test void rightAndBottomEdgesUseLeftAndUpPlacement() {
        var grid=RouteMachineGrid.of(300,220,18,8,320,240);
        assertTrue(grid.x()+grid.width()<=300);assertTrue(grid.y()+grid.height()<=236);
    }
    @Test void everyIconHasItsOwnClickTargetAndHeaderIsNotClickable() {
        var grid=RouteMachineGrid.of(20,20,18,13,320,240);
        for(int i=0;i<13;i++)assertEquals(i,grid.hit(grid.x()+5+i%grid.columns()*20,grid.y()+19+i/grid.columns()*20,0));
        assertEquals(-1,grid.hit(grid.x()+5,grid.y()+5,0));
        assertEquals(-1,grid.hit(grid.x()-1,grid.y()+19,0));
        assertEquals(-1,grid.hit(grid.x()+5+9*20,grid.y()+19+20,0));
    }
    @Test void largeSetsAreBoundedAndLaterRowsRemainAddressable() {
        var grid=RouteMachineGrid.of(8,8,18,512,320,200);
        assertTrue(grid.height()<=192);assertTrue(grid.capacity()<512);
        assertEquals(500,grid.hit(grid.x()+5,grid.y()+19,500));
    }
}
