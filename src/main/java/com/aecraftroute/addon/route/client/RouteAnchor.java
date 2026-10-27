package com.aecraftroute.addon.route.client;

/** World-space anchor; pan is also measured in world units, independent of zoom. */
public record RouteAnchor(String path, String key, int x, int y) {
    public record Pan(int x, int y) {}
    public Pan restore(String newKey, int newX, int newY, int panX, int panY) {
        return key.equals(newKey) ? new Pan(panX + x - newX, panY + y - newY) : new Pan(panX, panY);
    }
}
