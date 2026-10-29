package com.aecraftroute.addon.planner;

import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductionGraphTopologyTest {
    @Test void componentsAreInDemandOrderAndIgnoreDisconnectedRegions() {
        int[][] edges={{1,3},{2},{1},{4},{3},{5}};
        var topology=ProductionGraphTopology.analyze(edges,new boolean[]{true,true,true,true,true,false},()->false);
        assertNotNull(topology);assertTrue(topology.cyclic());
        assertEquals(3,topology.demandOrder().size());
        assertEquals(topology.componentOf()[1],topology.componentOf()[2]);
        assertEquals(topology.componentOf()[3],topology.componentOf()[4]);
        assertTrue(topology.componentOf()[0]<topology.componentOf()[1]);
        assertTrue(topology.componentOf()[0]<topology.componentOf()[3]);
        assertEquals(-1,topology.componentOf()[5]);
    }
    @Test void deepGraphsDoNotUseTheCallStack() {
        int size=20000;int[][] edges=new int[size][];var relevant=new boolean[size];Arrays.fill(relevant,true);
        for(int i=0;i<size;i++)edges[i]=i+1<size?new int[]{i+1}:new int[]{size-1};
        var result=ProductionGraphTopology.analyze(edges,relevant,()->false);
        assertEquals(size,result.demandOrder().size());assertTrue(result.cyclic());
    }
    @Test void interruptedTopologyNeverReturnsPartialComponents() {
        assertNull(ProductionGraphTopology.analyze(new int[][]{{0}},new boolean[]{true},()->true));
        assertThrows(CancellationException.class,()->ProductionGraphTopology.analyze(
                new int[][]{{0}},new boolean[]{true},()->{throw new CancellationException();}));
    }
}
