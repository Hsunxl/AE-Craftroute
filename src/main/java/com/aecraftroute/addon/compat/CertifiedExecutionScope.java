package com.aecraftroute.addon.compat;

/** Synchronous CPU tick ownership, independent of item IDs or provider classes.
 * Nested ordinary CPU ticks do not inherit another job's restriction. */
public final class CertifiedExecutionScope {
    private static final ThreadLocal<Boolean> ACTIVE=new ThreadLocal<>();
    private CertifiedExecutionScope() {}
    public static boolean active(){return Boolean.TRUE.equals(ACTIVE.get());}
    public static void run(Object owner,Runnable tick) {
        Boolean previous=ACTIVE.get();ACTIVE.set(CertifiedExecutionOwner.blocksExternalBatch(owner));
        try {tick.run();} finally {if(previous==null)ACTIVE.remove();else ACTIVE.set(previous);}
    }
}
