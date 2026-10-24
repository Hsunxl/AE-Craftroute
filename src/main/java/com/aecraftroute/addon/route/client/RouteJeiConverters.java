package com.aecraftroute.addon.route.client;

import appeng.api.stacks.*;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.neoforged.fml.ModList;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import tamaized.ae2jeiintegration.api.integrations.jei.IngredientConverters;

/** Uses the registry populated by AE2 JEI Integration and its addon converters. */
final class RouteJeiConverters {
    private RouteJeiConverters() {}
    static <T> GenericStack from(ITypedIngredient<T> typed) {
        if(ModList.get().isLoaded("ae2jeiintegration")) {
            var converter=IngredientConverters.getConverter(typed.getType());
            if(converter!=null) {
                var stack=converter.getStackFromIngredient(typed.getIngredient());
                if(stack!=null && stack.amount()>0)return stack;
            }
        }
        Object raw=typed.getIngredient();
        if(raw instanceof ItemStack stack && !stack.isEmpty())return new GenericStack(AEItemKey.of(stack),stack.getCount());
        if(raw instanceof FluidStack stack && !stack.isEmpty())return new GenericStack(AEFluidKey.of(stack),stack.getAmount());
        throw new IllegalArgumentException("材料类型未注册 AE2 转换器："+raw.getClass().getSimpleName()+"；未省略该材料");
    }
    static Object ingredient(AEKey key,long amount) {
        if(ModList.get().isLoaded("ae2jeiintegration"))for(var converter:IngredientConverters.getConverters()) {
            Object value=converter.getIngredientFromStack(new GenericStack(key,amount));
            if(value!=null)return value;
        }
        return key instanceof AEItemKey item?item.toStack((int)Math.min(Integer.MAX_VALUE,amount)):
                key instanceof AEFluidKey fluid?fluid.toStack((int)Math.min(Integer.MAX_VALUE,amount)):null;
    }
}
