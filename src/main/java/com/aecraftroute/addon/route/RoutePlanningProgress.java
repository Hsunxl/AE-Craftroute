package com.aecraftroute.addon.route;

/** Immutable progress envelope. Generation is scoped to one menu/session. */
public record RoutePlanningProgress(long generation, long revision, String phase,
        long elapsedMillis, boolean terminal) {
    public RoutePlanningProgress {
        if (generation < 0 || revision < 0 || elapsedMillis < 0 || phase == null)
            throw new IllegalArgumentException("Invalid planning progress");
    }

    /** Old attempts cannot overwrite a newer attempt or resurrect a terminal one. */
    public boolean supersedes(RoutePlanningProgress current) {
        return current == null || generation > current.generation
                || generation == current.generation && !current.terminal && revision > current.revision;
    }
}
