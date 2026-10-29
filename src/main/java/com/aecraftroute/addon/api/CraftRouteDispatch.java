package com.aecraftroute.addon.api;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;
import com.aecraftroute.addon.compat.CertifiedDispatchScope;

/** Optional provider receipt for the current certified native pushPattern call.
 * Does not enable external batching or supply a new extraction budget. */
public final class CraftRouteDispatch {
    /** Confirm exactly once after durable input ownership, before cleanup that
     * might throw. Use the original pattern and holder-array identities on the
     * scheduling thread. Returns false outside a matching dispatch attempt. */
    public static boolean confirmAccepted(IPatternDetails pattern,KeyCounter[] inputs) {
        if(pattern==null || inputs==null)return false;
        return CertifiedDispatchScope.confirmAccepted(pattern,inputs);
    }
    private CraftRouteDispatch() {}
}
