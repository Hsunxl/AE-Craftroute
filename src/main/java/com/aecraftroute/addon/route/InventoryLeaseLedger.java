package com.aecraftroute.addon.route;

import java.util.*;
import java.util.function.LongSupplier;

/** Cooperative, finite-stock reservations. One instance belongs to one grid epoch.
 * Does not extract items or prevent other mods from changing physical storage. */
final class InventoryLeaseLedger<K> {
    private record Entry<K>(Object token,Map<K,Long> amounts,long created,long ttl) {}
    private final Map<Object,Entry<K>> owners=new HashMap<>();
    private final LongSupplier clock;
    InventoryLeaseLedger(LongSupplier clock) {this.clock=Objects.requireNonNull(clock);}
    private void expire() {
        long now=clock.getAsLong();owners.values().removeIf(e->now-e.created>=e.ttl);
    }
    synchronized Object replace(Object owner,Map<K,Long> physical,Map<K,Long> requested,long ttl) {
        Objects.requireNonNull(owner);Objects.requireNonNull(physical);Objects.requireNonNull(requested);
        if(ttl<=0)throw new IllegalArgumentException("Lease TTL must be positive");
        var amounts=new LinkedHashMap<K,Long>();
        requested.forEach((k,v)->{
            if(k==null || v==null || v<=0)throw new IllegalArgumentException("Invalid finite reservation");
            amounts.put(k,v);
        });
        expire();
        for(var e:amounts.entrySet()) {
            long available=Math.max(0,physical.getOrDefault(e.getKey(),0L));
            for(var other:owners.entrySet())if(!other.getKey().equals(owner)) {
                long held=other.getValue().amounts.getOrDefault(e.getKey(),0L);
                available=held>=available?0:available-held;
            }
            // Failed replacement preserves this owner's previous lease.
            if(e.getValue()>available)return null;
        }
        var token=new Object();owners.put(owner,new Entry<>(token,Map.copyOf(amounts),clock.getAsLong(),ttl));
        return token;
    }
    synchronized boolean release(Object owner,Object token) {
        expire();var entry=owners.get(owner);
        if(entry==null || entry.token!=token)return false;
        owners.remove(owner);return true;
    }
    synchronized Map<K,Long> availableTo(Object owner,Map<K,Long> physical) {
        expire();var available=new LinkedHashMap<K,Long>();
        physical.forEach((k,v)->available.put(k,Math.max(0,v)));
        for(var other:owners.entrySet())if(!other.getKey().equals(owner))other.getValue().amounts.forEach((k,v)->{
            long held=available.getOrDefault(k,0L);available.put(k,v>=held?0:held-v);
        });
        return Map.copyOf(available);
    }
    synchronized void clear() {owners.clear();}
}
