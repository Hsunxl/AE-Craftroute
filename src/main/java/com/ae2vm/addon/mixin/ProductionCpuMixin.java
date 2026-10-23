package com.ae2vm.addon.mixin;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.*;
import appeng.crafting.inv.*;
import appeng.me.service.CraftingService;
import com.ae2vm.addon.planner.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.me.cluster.implementations.CraftingCPUCluster;

/** Certified dispatch, actual-return feedback, then delivery. No ring detector. */
@Mixin(value=CraftingCpuLogic.class,remap=false)
public abstract class ProductionCpuMixin {
    @Shadow private ExecutingCraftingJob job;
    @Shadow @Final private ListCraftingInventory inventory;
    @Shadow @Final private CraftingCPUCluster cluster;
    @Shadow private void finishJob(boolean success) { throw new AssertionError(); }
    @Shadow private void postChange(AEKey key) { throw new AssertionError(); }
    @Unique private ProductionJob ae2vm$production;

    @Inject(method="trySubmitJob",at=@At("RETURN"))
    private void ae2vm$attach(IGrid grid,ICraftingPlan plan,IActionSource source,ICraftingRequester requester,
            CallbackInfoReturnable<ICraftingSubmitResult> cir) {
        if(cir.getReturnValue().successful()) {
            if(plan instanceof CertifiedCraftingPlan certified){
                ae2vm$production=new ProductionJob(certified);
                com.ae2vm.addon.api.DispatchTrace.certified("ae2");
            }else{
                ae2vm$production=null;
            }
            cluster.markDirty();
        }
    }

    @WrapOperation(method="executeCrafting",at=@At(value="INVOKE",target="Lappeng/crafting/execution/CraftingCpuHelper;extractPatternInputs(Lappeng/api/crafting/IPatternDetails;Lappeng/crafting/inv/ICraftingInventory;Lnet/minecraft/world/level/Level;Lappeng/api/stacks/KeyCounter;Lappeng/api/stacks/KeyCounter;)[Lappeng/api/stacks/KeyCounter;"))
    private KeyCounter[] ae2vm$orderedInputs(IPatternDetails pattern,ICraftingInventory inv,Level level,
            KeyCounter expected,KeyCounter containers,Operation<KeyCounter[]> original) {
        return ae2vm$production==null?original.call(pattern,inv,level,expected,containers)
                :ae2vm$production.extract(pattern,inv,level,expected,containers);
    }

    @WrapOperation(method="executeCrafting",at=@At(value="INVOKE",target="Lappeng/api/networking/crafting/ICraftingProvider;pushPattern(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z"))
    private boolean ae2vm$accepted(ICraftingProvider provider,IPatternDetails pattern,KeyCounter[] inputs,Operation<Boolean> original) {
        if(ae2vm$production!=null && !ae2vm$production.allows(pattern)) return false;
        boolean accepted=original.call(provider,pattern,inputs);
        if(accepted && ae2vm$production!=null) { ae2vm$production.accepted(pattern); cluster.markDirty(); }
        return accepted;
    }

    @WrapMethod(method="insert")
    private long ae2vm$returnToInventory(AEKey key,long amount,Actionable mode,Operation<Long> original) {
        if(ae2vm$production==null || job==null) return original.call(key,amount,mode);
        if(key==null || amount<=0) return 0;
        var access=(GrowthJobAccessor)(Object)job;
        var waiting=access.ae2vm$getWaitingFor();
        long accepted=waiting.extract(key,amount,Actionable.SIMULATE);
        if(mode==Actionable.MODULATE && accepted>0) {
            waiting.extract(key,accepted,Actionable.MODULATE);
            inventory.insert(key,accepted,Actionable.MODULATE);
            ((GrowthTimeAccessor)(Object)access.ae2vm$getTimeTracker()).ae2vm$decrementItems(accepted,key.getType());
            postChange(key); cluster.markDirty();
        }
        // Delivery is deliberately deferred until tick TAIL, never re-entrant in pushPattern.
        return accepted;
    }

    @Inject(method="tickCraftingLogic",at=@At("TAIL"))
    private void ae2vm$deliver(IEnergyService energy,CraftingService service,CallbackInfo ci) {
        if(ae2vm$production==null || job==null || !ae2vm$production.done()) return;
        var access=(GrowthJobAccessor)(Object)job;
        if(!access.ae2vm$getWaitingFor().list.isEmpty() || access.ae2vm$getLink().isCanceled()
                || access.ae2vm$getSuspended() || !cluster.isActive()) return;
        long remaining=access.ae2vm$getRemainingAmount();
        AEKey key=access.ae2vm$getFinalOutput().what();
        long held=inventory.extract(key,remaining,Actionable.SIMULATE);
        if(held<=0) {
            com.ae2vm.addon.AE2VMAddon.LOGGER.warn("[AE2-VM] delivery pending: no {} x{} in the CPU inventory yet",key,remaining);
            return;
        }
        long accepted=access.ae2vm$getLink().insert(key,held,Actionable.MODULATE);
        if(accepted<=0) {
            com.ae2vm.addon.AE2VMAddon.LOGGER.warn("[AE2-VM] delivery blocked: the requester link is not connected, holding {} x{}",key,held);
            return;
        }
        com.ae2vm.addon.AE2VMAddon.LOGGER.info("[AE2-VM] delivered {} x{} to the requester, remaining was {}",key,accepted,remaining);
        inventory.extract(key,accepted,Actionable.MODULATE);
        remaining-=accepted; access.ae2vm$setRemainingAmount(remaining);
        postChange(key); cluster.markDirty();
        if(remaining==0) {
            finishJob(true); cluster.updateOutput(null); ae2vm$production=null;
        } else cluster.updateOutput(new GenericStack(key,remaining));
    }

    @Inject(method="finishJob",at=@At("TAIL"))
    private void ae2vm$clear(boolean success,CallbackInfo ci) { ae2vm$production=null; }

    @Inject(method="writeToNBT",at=@At("TAIL"))
    private void ae2vm$save(CompoundTag tag,HolderLookup.Provider registries,CallbackInfo ci) {
        if(ae2vm$production!=null && job!=null) tag.put(ProductionJob.TAG,ae2vm$production.save(registries));
        else tag.remove(ProductionJob.TAG);
    }
    @Inject(method="readFromNBT",at=@At("TAIL"))
    private void ae2vm$load(CompoundTag tag,HolderLookup.Provider registries,CallbackInfo ci) {
        boolean saved=tag.contains(ProductionJob.TAG);
        ae2vm$production=job!=null && saved
                ?ProductionJob.load(tag.getCompound(ProductionJob.TAG),registries,cluster.getLevel()):null;
        if(ae2vm$production!=null) {
            var remaining=new java.util.HashMap<IPatternDetails,Long>();
            ((GrowthJobAccessor)(Object)job).ae2vm$getTasks().forEach((pattern,progress)->
                    remaining.put(pattern,((GrowthTaskAccessor)progress).ae2vm$getValue()));
            boolean valid=ae2vm$production.validateRemainingTasks(remaining);
            com.ae2vm.addon.AE2VMAddon.LOGGER.info("[AE2-VM] restored production program: saved={} restored=true tasks={} valid={}",
                    saved,remaining.size(),valid);
        } else {
            com.ae2vm.addon.AE2VMAddon.LOGGER.info("[AE2-VM] no production program restored: saved={} job={}",
                    saved,job!=null);
        }
    }
}

