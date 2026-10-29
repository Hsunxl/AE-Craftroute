package com.aecraftroute.addon.compat;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Attempt-local material ownership. This does not authorize extra native crafts.
 * A false result permits the caller's ordinary refund; an uncertain exception
 * does not. Nested wrappers share the exact owner/pattern/input-array attempt. */
public final class CertifiedDispatchScope {
    private static final ThreadLocal<Attempt> CURRENT=new ThreadLocal<>();
    private static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("aecraftroute-dispatch");
    private static final java.util.concurrent.atomic.AtomicLong LAST_WARNING=
            new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);
    private enum State { PREPARED, ACCEPTED, REJECTED, UNKNOWN }
    private static final class Attempt {
        final Object owner,pattern,inputs;
        final Runnable commit,uncertain;
        State state=State.PREPARED;
        Attempt(Object owner,Object pattern,Object inputs,Runnable commit,Runnable uncertain) {
            this.owner=owner;this.pattern=pattern;this.inputs=inputs;this.commit=commit;this.uncertain=uncertain;
        }
        boolean matches(Object owner,Object pattern,Object inputs) {
            return this.owner==owner && this.pattern==pattern && this.inputs==inputs;
        }
        void accepted() {
            if(state==State.ACCEPTED)return;
            if(state!=State.PREPARED)throw new IllegalStateException("Dispatch ownership is no longer confirmable");
            // Ownership has moved even if a cursor/dirty callback fails. Never retry it.
            state=State.UNKNOWN;
            try {commit.run();state=State.ACCEPTED;}
            catch(RuntimeException | Error failure) {uncertain.run();throw failure;}
        }
    }

    public static boolean dispatch(Object owner,Object pattern,Object inputs,
            Runnable commit,Runnable uncertain,BooleanSupplier provider) {
        Objects.requireNonNull(owner);Objects.requireNonNull(pattern);Objects.requireNonNull(inputs);
        Objects.requireNonNull(commit);Objects.requireNonNull(uncertain);Objects.requireNonNull(provider);
        Attempt previous=CURRENT.get();
        boolean nested=previous!=null && previous.matches(owner,pattern,inputs);
        if(nested && previous.state==State.ACCEPTED)return true;
        if(nested && previous.state==State.UNKNOWN)throw new IllegalStateException("Dispatch ownership is uncertain; no retry permitted");
        Attempt attempt=nested?previous:new Attempt(owner,pattern,inputs,commit,uncertain);
        CURRENT.set(attempt);
        try {
            boolean accepted;
            try {accepted=provider.getAsBoolean();}
            catch(RuntimeException failure) {
                // An explicit durable receipt takes precedence over a cleanup exception.
                // Returning success lets native AE2 register returns and decrement its task.
                if(attempt.state==State.ACCEPTED) {
                    long now=System.nanoTime(),last=LAST_WARNING.get();
                    if((last==Long.MIN_VALUE || now-last>=30_000_000_000L) && LAST_WARNING.compareAndSet(last,now))
                        LOGGER.warn("[AE-Craftroute] Provider cleanup failed after a durable ownership receipt; preserving accepted dispatch (repeated warnings limited to 30s)",failure);
                    return true;
                }
                if(attempt.state!=State.UNKNOWN) {attempt.state=State.UNKNOWN;attempt.uncertain.run();}
                throw failure;
            } catch(Error failure) {
                if(attempt.state!=State.ACCEPTED && attempt.state!=State.UNKNOWN) {
                    attempt.state=State.UNKNOWN;attempt.uncertain.run();
                }
                throw failure;
            }
            if(accepted)attempt.accepted();
            if(attempt.state==State.ACCEPTED)return true;
            if(!nested)attempt.state=State.REJECTED;
            return false;
        } finally {
            if(previous==null)CURRENT.remove();else CURRENT.set(previous);
        }
    }

    /** Called only at the provider's durable ownership point, with the real
     * pattern and the SAME holder array. No active/matching attempt means no-op. */
    public static boolean confirmAccepted(Object pattern,Object inputs) {
        Attempt attempt=CURRENT.get();
        if(attempt==null || attempt.pattern!=pattern || attempt.inputs!=inputs)return false;
        attempt.accepted();return true;
    }
    private CertifiedDispatchScope() {}
}
