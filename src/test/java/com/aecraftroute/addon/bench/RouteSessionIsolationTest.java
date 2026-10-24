package com.aecraftroute.addon.bench;

import appeng.api.stacks.AEKey;
import com.aecraftroute.addon.route.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RouteSessionIsolationTest {
    final FakeBenchGrid a=new FakeBenchGrid(Map.of()),b=new FakeBenchGrid(Map.of());
    final UUID first=UUID.randomUUID(),second=UUID.randomUUID();
    RoutePolicy<AEKey> policy(){return new RoutePolicy<AEKey>(Map.of()).change(BenchAEKey.of("dust"),"lock","external");}
    @AfterEach void cleanup(){RouteSessions.clear();}
    @Test void playerAndNetworkAreIndependent(){RouteSessions.put(a,first,policy());assertFalse(RouteSessions.get(a,first).rules().isEmpty());assertTrue(RouteSessions.get(a,second).rules().isEmpty());assertTrue(RouteSessions.get(b,first).rules().isEmpty());}
    @Test void logoutClearsOnlyThatPlayersRules(){RouteSessions.put(a,first,policy());RouteSessions.put(b,first,policy());RouteSessions.put(a,second,policy());RouteSessions.remove(first);assertTrue(RouteSessions.get(a,first).rules().isEmpty());assertTrue(RouteSessions.get(b,first).rules().isEmpty());assertFalse(RouteSessions.get(a,second).rules().isEmpty());}
    @Test void clearIsScopedToSelectedNetwork(){RouteSessions.put(a,first,policy());RouteSessions.put(b,first,policy());RouteSessions.put(a,first,new RoutePolicy<>(Map.of()));assertTrue(RouteSessions.get(a,first).rules().isEmpty());assertFalse(RouteSessions.get(b,first).rules().isEmpty());}
    @Test void noRequesterDoesNotInheritPlayerPolicy(){RouteSessions.put(a,first,policy());assertTrue(RouteSessions.get(a,(appeng.api.networking.crafting.ICraftingSimulationRequester)null).rules().isEmpty());}
}
