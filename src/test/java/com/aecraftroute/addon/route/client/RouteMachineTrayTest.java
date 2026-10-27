package com.aecraftroute.addon.route.client;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class RouteMachineTrayTest {
    @Test void onlyActualMachineIconsConsumeClicks() {
        var tray=new RouteMachineTray<String>();
        tray.offer(List.of("assembler"),20,20,18,18,320,240);
        var grid=RouteMachineGrid.of(20,20,18,1,320,240);
        var chosen=new AtomicReference<String>();
        assertFalse(tray.click(grid.x()+5,grid.y()+5,0,chosen::set));
        assertFalse(tray.click(grid.x()+25,grid.y()+20,0,chosen::set));
        assertNull(chosen.get());
        assertTrue(tray.click(grid.x()+5,grid.y()+20,0,chosen::set));
        assertEquals("assembler",chosen.get());
        assertTrue(tray.click(grid.x()+5,grid.y()+20,1,chosen::set));
    }
}
