package com.aecraftroute.addon;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Identifies the exact build that is running.
 *
 * <p>A previous build shipped two different jars under the same file name and the same
 * version string, which made "the fix is in but nothing changed" impossible to rule out
 * from the game. Every build now carries a generated timestamp, and both the startup log
 * and {@code /aecraftroute build} print it, so a screenshot or a log line is enough to tell
 * which jar produced the behaviour.</p>
 */
public final class BuildInfo {
    private static final String VERSION;
    private static final String BUILT;

    static {
        String version = "unknown";
        String built = "unknown";
        try (InputStream in = BuildInfo.class.getResourceAsStream("/aecraftroute-build.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                version = props.getProperty("version", version);
                built = props.getProperty("built", built);
            }
        } catch (Exception ignored) {
            // A missing stamp must never break startup; "unknown" is an honest answer.
        }
        VERSION = version;
        BUILT = built;
    }

    public static String version() {
        return VERSION;
    }

    public static String built() {
        return BUILT;
    }

    public static String summary() {
        return "AE-Craftroute " + VERSION + " (built " + BUILT + ")";
    }

    public static void log(Logger logger) {
        logger.info("[AE-Craftroute] {}", summary());
    }

    /** {@code /aecraftroute build} — readable in-game without opening a log file. */
    @EventBusSubscriber(modid = CraftRouteAddon.MOD_ID)
    public static final class Command {
        @SubscribeEvent
        public static void register(RegisterCommandsEvent event) {
            event.getDispatcher().register(Commands.literal("aecraftroute")
                    .then(Commands.literal("build").executes(ctx -> {
                        String text = summary();
                        var player = ctx.getSource().getPlayer();
                        if (player != null) {
                            player.displayClientMessage(Component.literal(text), false);
                        }
                        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
                        return 1;
                    })));
        }
    }

    private BuildInfo() {
    }
}
