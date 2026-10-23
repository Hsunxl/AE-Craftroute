package com.ae2vm.addon.mixin;

import appeng.menu.AEBaseMenu;
import appeng.api.networking.security.IActionHost;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = AEBaseMenu.class, remap = false)
public interface RouteMenuAccessor {
    @Invoker("getActionHost") IActionHost ae2vm$routeHost();
}
