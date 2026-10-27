package com.aecraftroute.addon.route.client;

/** Verdicts are not inventory snapshots. Startup capital must never be subtracted from demand. */
public record RouteNodeVisual(int frame, int amountColor, int badgeBackground, String label) {
    public static RouteNodeVisual of(boolean proven, boolean stockOnly, boolean localEdit) {
        if (localEdit) return new RouteNodeVisual(0xFF7A5A2E, 0xFFFFFFFF, 0xE05A4525, "本地编辑·未证明");
        if (!proven) return new RouteNodeVisual(0xFF696D88, 0xFFE0E0E0, 0xE04B4F63,
                stockOnly ? "仅用库存·未证明" : "未证明");
        return new RouteNodeVisual(0xFF39734C, 0xFF99FF99, 0xE0234530,
                stockOnly ? "仅用库存·计划可行" : "计划已证明");
    }
}
