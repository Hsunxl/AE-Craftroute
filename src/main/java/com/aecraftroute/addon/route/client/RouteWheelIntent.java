package com.aecraftroute.addon.route.client;

/** Modifier precedence shared by the canvas and its regression tests. */
public enum RouteWheelIntent {
    ZOOM, PAN, QUANTITY;
    public static RouteWheelIntent of(boolean ctrl, boolean shift, boolean alt,
                                       boolean overNode, boolean horizontal, boolean legacy) {
        if (legacy && ctrl) return ZOOM;
        if (horizontal) return PAN;
        if (alt && overNode) return QUANTITY;
        if (shift && (!legacy || !overNode)) return PAN;
        if (legacy) return overNode ? QUANTITY : PAN;
        return ZOOM;
    }
}
