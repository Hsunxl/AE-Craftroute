package com.aecraftroute.addon.route;

import appeng.api.stacks.AEItemKey;
import appeng.helpers.patternprovider.*;
import appeng.parts.AEBasePart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Shared mapping for native AE, custom PatternContainer hosts, blocks and cable parts. */
public record RouteProviderPresentation(String name,AEItemKey icon,AEItemKey providerIcon,String location) {
    public static RouteProviderPresentation of(PatternContainer provider) {
        String name=provider.getClass().getSimpleName(),location="";AEItemKey icon=null,own=null;
        try {
            var group=provider.getTerminalGroup();
            if(group!=null) {if(group.name()!=null)name=group.name().getString();icon=group.icon();}
        } catch(RuntimeException e) {RouteProviders.issue(provider,"terminal group",e);}
        try {
            BlockEntity be=null;
            if(provider instanceof BlockEntity block) {be=block;own=item(new ItemStack(block.getBlockState().getBlock()));}
            else if(provider instanceof AEBasePart part) {be=part.getBlockEntity();own=item(new ItemStack(part.getPartItem()));}
            if(provider instanceof PatternProviderLogicHost host) {
                if(be==null)be=host.getBlockEntity();
                var hostIcon=host.getTerminalIcon();if(hostIcon!=null)own=hostIcon;
            }
            if(be!=null && be.getLevel()!=null) {
                var pos=be.getBlockPos();location=be.getLevel().dimension().location()+" "+pos.getX()+", "+pos.getY()+", "+pos.getZ();
            }
        } catch(RuntimeException e) {RouteProviders.issue(provider,"provider identity",e);}
        return new RouteProviderPresentation(name,icon==null?own:icon,own,location);
    }
    private static AEItemKey item(ItemStack stack) {return stack.isEmpty()?null:AEItemKey.of(stack);}
}
