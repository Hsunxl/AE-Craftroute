package com.aecraftroute.addon.planner;

/** A typed planning failure must never trigger an older, uncertified planner. */
public final class ProductionPlanningException extends RuntimeException {
    public enum Reason { UNREACHABLE, LIMIT, UNSUPPORTED, INVALID }
    private final Reason reason;
    public ProductionPlanningException(Reason reason,String message) { super(message); this.reason=reason; }
    public Reason reason() { return reason; }
}
