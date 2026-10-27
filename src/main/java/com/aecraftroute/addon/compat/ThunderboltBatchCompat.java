package com.aecraftroute.addon.compat;

import appeng.api.crafting.IPatternDetails;
import java.util.function.Predicate;
import net.neoforged.fml.ModList;

/** Uses the installed dispatcher's PUBLIC batch eligibility API. Normal per-pattern
 * dispatch remains enabled: no global optimizer switch or pattern blacklist. */
public final class ThunderboltBatchCompat {
    private static boolean registered;
    private ThunderboltBatchCompat() {}
    public static boolean allowsBatch(IPatternDetails ignored){return !CertifiedExecutionScope.active();}
    public static synchronized void registerIfPresent() {
        if(registered || !ModList.get().isLoaded("thunderbolt"))return;
        try {
            Class<?> dispatcher=Class.forName("com.moakiee.thunderbolt.core.crafting.batch.BatchExecutor");
            dispatcher.getMethod("registerBatchEligibilityRule",Predicate.class)
                    .invoke(null,(Predicate<IPatternDetails>)ThunderboltBatchCompat::allowsBatch);
            registered=true;
            com.aecraftroute.addon.CraftRouteAddon.LOGGER.info("[AE-Craftroute] certified execution ownership registered with the external batch dispatcher");
        } catch(ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot protect certified execution through the installed batch eligibility API",failure);
        }
    }
}
