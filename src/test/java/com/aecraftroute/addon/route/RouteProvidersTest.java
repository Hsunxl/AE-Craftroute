package com.aecraftroute.addon.route;

import appeng.api.networking.*;
import appeng.api.inventories.InternalInventory;
import appeng.helpers.patternprovider.PatternContainer;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouteProvidersTest {
    @SuppressWarnings("unchecked") static <T> T proxy(Class<T> type,java.util.function.Function<String,Object> calls) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(p,m,a)->{
            if(m.getName().equals("hashCode"))return System.identityHashCode(p);
            if(m.getName().equals("equals"))return p==a[0];
            if(m.getName().equals("toString"))return type.getSimpleName();
            return calls.apply(m.getName());
        });
    }
    final List<IGridNode> nodes=new ArrayList<>();
    final IGrid grid=proxy(IGrid.class,name->{if(name.equals("getNodes"))return nodes;
        throw new AssertionError("must enumerate actual node owners, not machine interface: "+name);});
    PatternContainer provider(IGrid network,boolean visible,int slots,long order) {
        var inventory=proxy(InternalInventory.class,name->name.equals("size")?slots:null);
        return proxy(PatternContainer.class,name->switch(name) {
            case "getGrid"->network;case "isVisibleInTerminal"->visible;
            case "getTerminalPatternInventory"->inventory;case "getTerminalSortOrder"->order;default->null;
        });
    }
    void node(Object owner,boolean active) {nodes.add(proxy(IGridNode.class,name->switch(name) {
        case "getOwner"->owner;case "isActive"->active;default->null;
    }));}
    @Test void actualOwnersWorkWithoutAnInterfaceMachineIndex() {
        var p=provider(grid,true,9,0);node(p,true);
        assertSame(p,RouteProviders.active(grid).getFirst());
    }
    @Test void duplicateNodesDoNotDuplicateProviderAndSortOrderIsPreserved() {
        var last=provider(grid,true,512,20);var first=provider(grid,true,9,1);
        node(last,true);node(first,true);node(last,true);
        var found=RouteProviders.active(grid);assertEquals(2,found.size());assertSame(first,found.getFirst());assertSame(last,found.getLast());
    }
    @Test void offlineHiddenEmptyAndOtherNetworkAreExcluded() {
        node(provider(grid,true,9,0),false);node(provider(grid,false,9,0),true);
        node(provider(grid,true,0,0),true);node(provider(null,true,9,0),true);node(new Object(),true);
        assertTrue(RouteProviders.active(grid).isEmpty());
    }
}
