package com.ae2vm.addon.mixin;

import appeng.client.gui.AEBaseScreen;
import net.minecraft.client.gui.components.Button;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Adds the route button to AE2's own left toolbar instead of painting over its tabs. */
@Mixin(value = AEBaseScreen.class, remap = false)
public interface RouteToolbarAccessor {
    @Invoker("addToLeftToolbar")
    Button ae2vm$addRouteButton(Button button);
}
