package com.aecraftroute.addon.compat;

/** CPU execution ownership, not CPU type: ordinary jobs keep third-party batching. */
public interface CertifiedExecutionOwner {
    boolean aecraftroute$hasCertifiedProgram();

    static boolean blocksExternalBatch(Object owner) {
        return owner instanceof CertifiedExecutionOwner certified && certified.aecraftroute$hasCertifiedProgram();
    }
}
