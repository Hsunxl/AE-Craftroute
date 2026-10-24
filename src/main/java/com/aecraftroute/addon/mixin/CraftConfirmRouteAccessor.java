package com.aecraftroute.addon.mixin;

import appeng.api.stacks.AEKey;
import appeng.menu.me.crafting.CraftConfirmMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to the target the confirmation screen is currently planning. */
@Mixin(value = CraftConfirmMenu.class, remap = false)
public interface CraftConfirmRouteAccessor {
    @Accessor("whatToCraft")
    AEKey aecraftroute$routeWhatToCraft();

    @Accessor("amount")
    int aecraftroute$routeAmount();
}
