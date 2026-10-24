package com.aecraftroute.addon.mixin;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.*;
import appeng.crafting.inv.*;
import appeng.me.service.CraftingService;
import com.aecraftroute.addon.planner.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import net.pedroksl.advanced_ae.common.logic.ExecutingCraftingJob;
import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU;

/** Certified dispatch, actual-return feedback, then delivery. No ring detector. */
@Pseudo
@Mixin(targets="net.pedroksl.advanced_ae.common.logic.AdvCraftingCPULogic",remap=false)
public abstract class AdvancedProductionCpuMixin {
    @Shadow private ExecutingCraftingJob job;
    @Shadow @Final private ListCraftingInventory inventory;
    @Shadow @Final private AdvCraftingCPU cpu;
    @Shadow private void finishJob(boolean success) { throw new AssertionError(); }
    @Shadow private void postChange(AEKey key) { throw new AssertionError(); }
    @Unique private ProductionJob aecraftroute$production;
    @Unique private final com.aecraftroute.addon.telemetry.LogThrottle aecraftroute$deliveryLogs = new com.aecraftroute.addon.telemetry.LogThrottle();

    @Inject(method="trySubmitJob",at=@At("RETURN"))
    private void aecraftroute$attach(IGrid grid,ICraftingPlan plan,IActionSource source,ICraftingRequester requester,
            CallbackInfoReturnable<ICraftingSubmitResult> cir) {
        if(cir.getReturnValue().successful()) {
            aecraftroute$deliveryLogs.reset();
            if(plan instanceof CertifiedCraftingPlan certified){
                aecraftroute$production=new ProductionJob(certified);
                com.aecraftroute.addon.api.DispatchTrace.certified("advanced_ae");
            }else{
                aecraftroute$production=null;
            }
            cpu.markDirty();
        }
    }

    @WrapOperation(method="executeCrafting",at=@At(value="INVOKE",target="Lappeng/crafting/execution/CraftingCpuHelper;extractPatternInputs(Lappeng/api/crafting/IPatternDetails;Lappeng/crafting/inv/ICraftingInventory;Lnet/minecraft/world/level/Level;Lappeng/api/stacks/KeyCounter;Lappeng/api/stacks/KeyCounter;)[Lappeng/api/stacks/KeyCounter;"))
    private KeyCounter[] aecraftroute$orderedInputs(IPatternDetails pattern,ICraftingInventory inv,Level level,
            KeyCounter expected,KeyCounter containers,Operation<KeyCounter[]> original) {
        return aecraftroute$production==null?original.call(pattern,inv,level,expected,containers)
                :aecraftroute$production.extract(pattern,inv,level,expected,containers);
    }

    @WrapOperation(method="executeCrafting",at=@At(value="INVOKE",target="Lappeng/api/networking/crafting/ICraftingProvider;pushPattern(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z"))
    private boolean aecraftroute$accepted(ICraftingProvider provider,IPatternDetails pattern,KeyCounter[] inputs,Operation<Boolean> original) {
        if(aecraftroute$production!=null && !aecraftroute$production.allows(pattern)) return false;
        boolean accepted=original.call(provider,pattern,inputs);
        if(accepted && aecraftroute$production!=null) { aecraftroute$production.accepted(pattern); cpu.markDirty(); }
        return accepted;
    }

    @WrapMethod(method="insert")
    private long aecraftroute$returnToInventory(AEKey key,long amount,Actionable mode,Operation<Long> original) {
        if(aecraftroute$production==null || job==null) return original.call(key,amount,mode);
        if(key==null || amount<=0) return 0;
        var access=(AdvancedGrowthJobAccessor)(Object)job;
        var waiting=access.aecraftroute$getWaitingFor();
        long accepted=waiting.extract(key,amount,Actionable.SIMULATE);
        if(mode==Actionable.MODULATE && accepted>0) {
            waiting.extract(key,accepted,Actionable.MODULATE);
            inventory.insert(key,accepted,Actionable.MODULATE);
            ((AdvancedGrowthTimeAccessor)(Object)access.aecraftroute$getTimeTracker()).aecraftroute$decrementItems(accepted,key.getType());
            postChange(key); cpu.markDirty();
        }
        // Delivery is deliberately deferred until tick TAIL, never re-entrant in pushPattern.
        return accepted;
    }

    @Inject(method="tickCraftingLogic",at=@At("TAIL"))
    private void aecraftroute$deliver(IEnergyService energy,CraftingService service,CallbackInfo ci) {
        if(aecraftroute$production==null || job==null || !aecraftroute$production.done()) return;
        var access=(AdvancedGrowthJobAccessor)(Object)job;
        if(access.aecraftroute$getLink().isCanceled() || access.aecraftroute$getSuspended() || !cpu.isActive()) return;
        if(!access.aecraftroute$getWaitingFor().list.isEmpty()) {
            if(aecraftroute$deliveryLogs.acquireDelayed("waiting"))
                com.aecraftroute.addon.CraftRouteAddon.LOGGER.warn("[AE-Craftroute] AdvancedAE CPU delivery waiting for {} output keys from accepted dispatches",access.aecraftroute$getWaitingFor().list.size());
            return;
        }
        long remaining=access.aecraftroute$getRemainingAmount();
        AEKey key=access.aecraftroute$getFinalOutput().what();
        long held=inventory.extract(key,remaining,Actionable.SIMULATE);
        if(held<=0) {
            if(aecraftroute$deliveryLogs.acquire("missing"))
                com.aecraftroute.addon.CraftRouteAddon.LOGGER.warn("[AE-Craftroute] AdvancedAE CPU delivery pending: no {} x{} in the CPU inventory yet",key,remaining);
            return;
        }
        long accepted=access.aecraftroute$getLink().insert(key,held,Actionable.MODULATE);
        if(accepted<=0) {
            if(aecraftroute$deliveryLogs.acquire("blocked"))
                com.aecraftroute.addon.CraftRouteAddon.LOGGER.warn("[AE-Craftroute] AdvancedAE CPU delivery blocked: requester accepted nothing, holding {} x{}",key,held);
            return;
        }
        if(com.aecraftroute.addon.config.CraftRouteConfig.isDebugLogging())
            com.aecraftroute.addon.CraftRouteAddon.LOGGER.info("[AE-Craftroute] AdvancedAE CPU delivered {} x{} to the requester, remaining was {}",key,accepted,remaining);
        inventory.extract(key,accepted,Actionable.MODULATE);
        remaining-=accepted; access.aecraftroute$setRemainingAmount(remaining);
        postChange(key); cpu.markDirty();
        if(remaining==0) {
            finishJob(true); cpu.updateOutput(null); aecraftroute$production=null;
        } else cpu.updateOutput(new GenericStack(key,remaining));
    }

    @Inject(method="finishJob",at=@At("TAIL"))
    private void aecraftroute$clear(boolean success,CallbackInfo ci) { aecraftroute$production=null; aecraftroute$deliveryLogs.reset(); }

    @Inject(method="writeToNBT",at=@At("TAIL"))
    private void aecraftroute$save(CompoundTag tag,HolderLookup.Provider registries,CallbackInfo ci) {
        if(aecraftroute$production!=null && job!=null) tag.put(ProductionJob.TAG,aecraftroute$production.save(registries));
        else { tag.remove(ProductionJob.TAG); tag.remove(ProductionJob.LEGACY_TAG); }
    }
    @Inject(method="readFromNBT",at=@At("TAIL"))
    private void aecraftroute$load(CompoundTag tag,HolderLookup.Provider registries,CallbackInfo ci) {
        aecraftroute$deliveryLogs.reset();
        var savedTag=ProductionJob.savedTag(tag);
        aecraftroute$production=job!=null && savedTag!=null
                ?ProductionJob.load(savedTag,registries,cpu.getLevel()):null;
        if(aecraftroute$production!=null) {
            var remaining=new java.util.HashMap<IPatternDetails,Long>();
            ((AdvancedGrowthJobAccessor)(Object)job).aecraftroute$getTasks().forEach((pattern,progress)->
                    remaining.put(pattern,((AdvancedGrowthTaskAccessor)progress).aecraftroute$getValue()));
            aecraftroute$production.validateRemainingTasks(remaining);
        }
    }
}

