package com.aecraftroute.addon.mixin;

import appeng.api.networking.energy.IEnergyService;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.service.CraftingService;
import com.aecraftroute.addon.compat.CertifiedExecutionOwner;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** NeoECO's external batch dispatcher runs before executeCrafting and changes native
 * task counts without traversing our extraction/acceptance hooks. It cannot execute
 * an ordered certificate. Returning zero means "no fast path", so the caller falls
 * through to the normal CPU dispatcher; it does NOT cancel or finish the job. */
@Pseudo
@Mixin(targets="cn.dancingsnow.neoecoae.crafting.execution.ECOExternalCpuFastPath",remap=false)
public abstract class NeoEcoExternalCpuFastPathMixin {
    @Inject(method="execute(Ljava/lang/Object;Ljava/lang/Object;Lappeng/crafting/inv/ListCraftingInventory;ILappeng/me/service/CraftingService;Lappeng/api/networking/energy/IEnergyService;Lnet/minecraft/world/level/Level;)I",
            at=@At("HEAD"),cancellable=true)
    private void aecraftroute$keepCertifiedDispatch(Object owner,Object job,ListCraftingInventory inventory,
            int maxPatterns,CraftingService crafting,IEnergyService power,Level level,CallbackInfoReturnable<Integer> cir) {
        if(CertifiedExecutionOwner.blocksExternalBatch(owner))cir.setReturnValue(0);
    }
}
