package com.aecraftroute.addon.compat.advancedae;

import com.aecraftroute.addon.CraftRouteAddon;
import net.neoforged.fml.ModList;

/**
 * AdvancedAE (net.pedroksl.advanced_ae) 弱依赖门面。
 *
 * <p>Only registered AE-Craftroute requests enter our planner. Unregistered
 * third-party requesters retain their own planning and execution layers;
 * certified plans may be submitted to a compatible AdvancedAE CPU bridge.
 */
public final class AdvancedAECompat {

    /** AdvancedAE 的 mod id（字面量，静态初始化不触碰 AdvancedAE 类）。 */
    public static final String MOD_ID = "advanced_ae";

    private static volatile boolean logged = false;

    private AdvancedAECompat() {
    }

    /**
     * AdvancedAE 是否已安装。始终安全调用：使用 ModList 字符串字面量检测。
     */
    public static boolean isLoaded() {
        try {
            return ModList.get() != null && ModList.get().isLoaded(MOD_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 安装确认日志（仅 debug 模式，幂等）：陈述已注册路线的兼容范围。
     * 在 commonSetup 的 enqueueWork 中调用（mod 列表已完整）。
     */
    public static synchronized void logCompatibilityIfPresent() {
        if (!isLoaded() || logged || !com.aecraftroute.addon.config.CraftRouteConfig.isDebugLogging()) {
            return;
        }
        logged = true;
        CraftRouteAddon.LOGGER.info(
                "[AE-Craftroute] AdvancedAE detected ({}): registered route requests can use certified "
                        + "planning and compatible CPU execution; unregistered third-party requesters retain "
                        + "their own planning and execution layers.",
                MOD_ID);
    }
}
