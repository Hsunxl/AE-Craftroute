package com.aecraftroute.addon.route.client;

/** Equal-width cells keep all actions reachable even at the minimum supported GUI size. */
public record RouteToolbar(int cellWidth, int buttonWidth) {
    public static RouteToolbar of(int available, int count) {
        if (count <= 0 || available < count * 2) throw new IllegalArgumentException("toolbar size");
        int cell = Math.min(64, available / count);
        return new RouteToolbar(cell, cell - 2);
    }
}
