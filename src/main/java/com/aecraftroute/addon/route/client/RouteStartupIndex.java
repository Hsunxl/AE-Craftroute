package com.aecraftroute.addon.route.client;

import com.aecraftroute.addon.route.RouteView;
import java.util.*;
import java.util.function.Function;

/** Material identity, never translated display names, indexes startup capital. */
public final class RouteStartupIndex {
    private RouteStartupIndex() {}
    public static Map<String, Long> of(List<RouteView.Stack> stacks) { return of(stacks, RouteView.Stack::key); }
    public static <K> Map<K, Long> of(List<RouteView.Stack> stacks, Function<RouteView.Stack,K> identity) {
        var index = new LinkedHashMap<K, Long>();
        for (var stack : stacks) if (stack.amount() > 0) {
            K key=identity.apply(stack);
            if(key!=null)index.merge(key, stack.amount(), (a,b) -> a > Long.MAX_VALUE-b ? Long.MAX_VALUE : a+b);
        }
        return Map.copyOf(index);
    }
}
