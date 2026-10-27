package com.aecraftroute.addon;

// import com.aecraftroute.addon.compat.thunderbolt.ThunderboltCompat; // (v1.13.16+ stubbed)
import com.aecraftroute.addon.config.CraftRouteConfig;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

import java.util.Set;

/**
 * AE-Craftroute Crafting Accelerator - Main Mod Class
 *
 * Replaces AE2's recursive crafting calculation with one certified production planner.
 *
 * <p>Planning is a single backward-coverability search over the network's patterns and
 * stock that accounts for inventory, so cyclic recipes, incremental yields and reuse of
 * already-produced items are all part of the same proof. The result carries an executable
 * program; the CPU mixins execute that program in order and reserve feedback items
 * instead of delivering them too early.</p>
 *
 * Architecture:
 * - RouteSnapshot: one consistent pattern/stock snapshot of the grid
 * - NetworkProductionPlanner: certified planner (feasible / unreachable / undecided)
 * - CertifiedCraftingPlan: the plan plus its executable program
 * - Mixin: hooks into ICraftingService.beginCraftingCalculation()
 */
@Mod(CraftRouteAddon.MOD_ID)
public class CraftRouteAddon {
    public static final String MOD_ID = "aecraftroute";
    public static final Logger LOGGER = LogUtils.getLogger();
    
    public CraftRouteAddon(IEventBus modEventBus) {
        // Always logged (not behind debug): this is how a player tells two builds apart.
        BuildInfo.log(LOGGER);
        // Carry a pre-rename configuration over before NeoForge creates the new one, so
        // settings such as proxy.enabled are not silently reset to their defaults.
        migrateLegacyConfig();
        // 注册 COMMON 配置（TOML）：config/aecraftroute-common.toml，Configured 可游戏内编辑
        ModList.get().getModContainerById(MOD_ID).ifPresent(container ->
                container.registerConfig(ModConfig.Type.COMMON, CraftRouteConfig.COMMON_SPEC));
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(com.aecraftroute.addon.route.RouteNetwork::register);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) ->
                        com.aecraftroute.addon.route.RouteSessions.remove(e.getEntity().getUUID()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStoppedEvent e) ->
                        com.aecraftroute.addon.route.RouteSessions.clear());
        
        
        // 不向 Thunderbolt 的多引擎规划体系注册我们的 planner；第三方未注册的
        // requester 保持自己的规划和执行链。下方只注册 batch 资格兼容规则。

        if (CraftRouteConfig.isDebugLogging()) {
            LOGGER.info("[AE-Craftroute] Certified production planner active; ordered CPU dispatch, feedback reuse and exact plan cache enabled");
        }
    }
    /**
     * Copies {@code config/aecraftroute-common.toml} from the pre-rename file once. Must run
     * before the configuration is registered, because registration creates the new file and a
     * default file would otherwise win. Failures are logged, never fatal: a mod that cannot
     * copy a config file must still start.
     */
    private static void migrateLegacyConfig() {
        try {
            var dir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
            var target = dir.resolve("aecraftroute-common.toml");
            if (java.nio.file.Files.exists(target)) return;
            var legacy = dir.resolve("ae2vm-common.toml");
            if (!java.nio.file.Files.exists(legacy)) return;
            java.nio.file.Files.copy(legacy, target);
            java.nio.file.Files.move(legacy, dir.resolve("ae2vm-common.toml.migrated"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("[AE-Craftroute] carried over the previous configuration file");
        } catch (Throwable failure) {
            LOGGER.warn("[AE-Craftroute] could not carry over the previous configuration: {}", failure.toString());
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        if (com.aecraftroute.addon.config.CraftRouteConfig.isDebugLogging()) {
        CraftRouteAddon.LOGGER.info(
                "[AE-Craftroute] Config loaded from config/aecraftroute-common.toml (proxy.enabled={}) — in-game editing via Configured (if installed)",
                CraftRouteConfig.isProxyEnabled());
        }

        // 只注册 Thunderbolt batch 资格规则，不接管它的规划引擎。
        event.enqueueWork(() -> {
            com.aecraftroute.addon.compat.ThunderboltBatchCompat.registerIfPresent();
            // 兼容确认仅描述已注册请求；未注册第三方 requester 保持自己的执行链。
            com.aecraftroute.addon.compat.advancedae.AdvancedAECompat.logCompatibilityIfPresent();
        });

        if (com.aecraftroute.addon.config.CraftRouteConfig.isDebugLogging()) {
        LOGGER.info("[AE-Craftroute] Common setup complete - certified production planner active");
        }
        if (com.aecraftroute.addon.config.CraftRouteConfig.isDebugLogging()) {
        LOGGER.info("[AE-Craftroute] All crafting calculations will be logged with timing information");
        }
    }
}

