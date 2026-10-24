package com.aecraftroute.addon.route;

/** Deliberate failure: native fallback would silently violate a user's hard constraints. */
public final class RoutePlanningException extends RuntimeException {
    public RoutePlanningException(String message) { super(message); }
    public static boolean contains(Throwable error) {
        for (int i = 0; error != null && i < 16; i++, error = error.getCause()) {
            if (error instanceof RoutePlanningException) return true;
        }
        return false;
    }
}
