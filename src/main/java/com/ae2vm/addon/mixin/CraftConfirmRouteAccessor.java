package com.ae2vm.addon.mixin;

import appeng.api.stacks.AEKey;
import appeng.menu.me.crafting.CraftConfirmMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to the target the confirmation screen is currently planning. */
@Mixin(value = CraftConfirmMenu.class, remap = false)
public interface CraftConfirmRouteAccessor {
    @Accessor("whatToCraft")
    AEKey ae2vm$routeWhatToCraft();

    @Accessor("amount")
    int ae2vm$routeAmount();
}
