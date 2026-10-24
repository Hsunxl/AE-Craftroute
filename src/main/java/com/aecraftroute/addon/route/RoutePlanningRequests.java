package com.aecraftroute.addon.route;

import java.util.*;
import java.util.concurrent.Future;

/** Server-thread request leases. Removing the old lease precedes cancellation, so
 * a synchronous cancellation callback cannot commit or remove its replacement. */
final class RoutePlanningRequests<K> {
    static final class Ticket {
        final int sequence;
        private Future<?> future;
        Ticket(int sequence) {this.sequence=sequence;}
    }
    private final Map<K,Ticket> requests=new HashMap<>();
    Ticket replace(K owner,int sequence) {
        var old=requests.get(owner);
        if(old!=null && sequence<old.sequence)return null;
        requests.remove(owner);
        if(old!=null && old.future!=null)old.future.cancel(true);
        var next=new Ticket(sequence);requests.put(owner,next);return next;
    }
    void bind(K owner,Ticket ticket,Future<?> future) {
        ticket.future=future;
        if(!current(owner,ticket))future.cancel(true);
    }
    boolean current(K owner,Ticket ticket) {return requests.get(owner)==ticket;}
    void forget(K owner,Ticket ticket) {requests.remove(owner,ticket);}
    void cancel(K owner) {
        var old=requests.remove(owner);if(old!=null && old.future!=null)old.future.cancel(true);
    }
    /** A new canvas handshake resets sequence comparison even when the menu ID is reused. */
    void begin(K owner) {cancel(owner);}
    void clear() {
        var old=new ArrayList<>(requests.values());requests.clear();
        for(var ticket:old)if(ticket.future!=null)ticket.future.cancel(true);
    }
    int size() {return requests.size();}
}
