package com.aecraftroute.addon.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.stacks.GenericStack;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.energy.IEnergyService;
import appeng.me.service.CraftingService;
import appeng.crafting.inv.ListCraftingInventory;
import com.aecraftroute.addon.vm.FeedbackReservation;
import com.aecraftroute.addon.vm.DeferredFeedbackReservation;
import com.aecraftroute.addon.vm.NetProduction;
import com.aecraftroute.addon.vm.TwoNodeGrowthPlanner;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU;
import net.pedroksl.advanced_ae.common.logic.ExecutingCraftingJob;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** AdvancedAE has its own CPU/job/tracker classes, not subclasses of AE2's.
 * Keep expected feedback packets in this CPU, without crediting them as delivery.
 * Pseudo targets keep AdvancedAE optional; no items are synthesized or pulled
 * from ME to repair jobs whose working seed was delivered by an older version.
 */
@Pseudo
@Mixin(targets="net.pedroksl.advanced_ae.common.logic.AdvCraftingCPULogic", remap=false)
public abstract class AdvancedGrowthCpuMixin {
    @Shadow private ExecutingCraftingJob job;
    @Shadow @Final private ListCraftingInventory inventory;
    @Shadow @Final private AdvCraftingCPU cpu;
    @Shadow private void finishJob(boolean success){throw new AssertionError();}
    @Shadow public void cancel(){throw new AssertionError();}
    @Shadow private void postChange(AEKey key){throw new AssertionError();}
    @Unique private boolean aecraftroute$growthFeedback;
    @Unique private long aecraftroute$restoreTargetSeed;
    @Unique private DeferredFeedbackReservation aecraftroute$deferredFeedback;

    @Inject(method="trySubmitJob", at=@At("RETURN"))
    private void aecraftroute$mark(IGrid grid, ICraftingPlan plan, IActionSource source,
            ICraftingRequester requester, CallbackInfoReturnable<ICraftingSubmitResult> cir) {
        if (job != null && cir.getReturnValue().successful()) {
            // Do not reshape a third-party requester's own execution contract.
            aecraftroute$growthFeedback = !(plan instanceof com.aecraftroute.addon.planner.CertifiedCraftingPlan)
                    && (requester == null || !com.aecraftroute.addon.api.CraftRouteCraftingRegistry.isUnregisteredThirdParty(requester.getClass().getName()))
                    && TwoNodeGrowthPlanner.containsRing(plan.patternTimes().keySet());
            aecraftroute$restoreTargetSeed = aecraftroute$growthFeedback && NetProduction.sufficient(plan)
                    ? plan.usedItems().get(plan.finalOutput().what()) : 0;
            aecraftroute$deferredFeedback=aecraftroute$growthFeedback?new DeferredFeedbackReservation():null;
            aecraftroute$orderFeedback();
        }
    }

    @Unique private void aecraftroute$orderFeedback() {
        if (aecraftroute$growthFeedback && job != null) {
            AdvancedGrowthJobAccessor access = (AdvancedGrowthJobAccessor)(Object)job;
            access.aecraftroute$setTasks(TwoNodeGrowthPlanner.prioritizeFeedback(access.aecraftroute$getTasks()));
        }
    }

    @WrapMethod(method="insert")
    private long aecraftroute$reserveFeedback(AEKey what, long amount, Actionable mode, Operation<Long> original) {
        if (!aecraftroute$growthFeedback || job == null || what == null || amount <= 0)
            return original.call(what, amount, mode);
        AdvancedGrowthJobAccessor access = (AdvancedGrowthJobAccessor)(Object)job;
        if (!what.matches(access.aecraftroute$getFinalOutput())) return original.call(what, amount, mode);
        if(aecraftroute$deferredFeedback!=null) {
            long accepted=aecraftroute$deferredFeedback.offerBuffer(amount,
                    access.aecraftroute$getWaitingFor().extract(what,Long.MAX_VALUE,Actionable.SIMULATE));
            if(mode==Actionable.MODULATE && accepted>0) {
                inventory.insert(what,accepted,Actionable.MODULATE);
                aecraftroute$deferredFeedback.retain(accepted);cpu.markDirty();
            }
            return accepted;
        }
        long pending = 0;
        try {
            for (var task : access.aecraftroute$getTasks().entrySet()) {
                long times = ((AdvancedGrowthTaskAccessor)task.getValue()).aecraftroute$getValue();
                if (times <= 0) continue;
                for (var input : task.getKey().getInputs()) {
                    var choices = input.getPossibleInputs();
                    if (choices == null || choices.length == 0 || choices[0] == null
                            || !what.equals(choices[0].what())) continue;
                    if (what.equals(input.getRemainingKey(what))) continue;
                    pending = Math.addExact(pending, Math.multiplyExact(times,
                            Math.multiplyExact(input.getMultiplier(), choices[0].amount())));
                }
            }
        } catch (ArithmeticException ex) {
            pending = Long.MAX_VALUE;
        }
        var waiting = access.aecraftroute$getWaitingFor();
        long expected = waiting.extract(what, amount, Actionable.SIMULATE);
        long reserve = FeedbackReservation.reserve(amount, expected, pending,
                inventory.extract(what, Long.MAX_VALUE, Actionable.SIMULATE));
        long restore = Math.min(aecraftroute$restoreTargetSeed,
                Math.min(amount - reserve, Math.max(0, expected - reserve)));
        long retained = reserve + restore;
        if (retained == 0) return original.call(what, amount, mode);
        if (mode == Actionable.MODULATE) {
            waiting.extract(what, retained, Actionable.MODULATE);
            inventory.insert(what, retained, Actionable.MODULATE);
            aecraftroute$restoreTargetSeed -= restore;
            ((AdvancedGrowthTimeAccessor)(Object)access.aecraftroute$getTimeTracker())
                    .aecraftroute$decrementItems(retained, what.getType());
            cpu.markDirty();
        }
        return retained + (amount > retained ? original.call(what, amount - retained, mode) : 0L);
    }

 @WrapOperation(method="executeCrafting",at=@At(value="INVOKE",target="Lappeng/api/networking/crafting/ICraftingProvider;pushPattern(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z"))
 private boolean aecraftroute$settleFeedbackInput(ICraftingProvider provider,IPatternDetails pattern,
         KeyCounter[] inputs,Operation<Boolean> original) {
     var ledger=aecraftroute$deferredFeedback;var submittedJob=job;
     if(ledger==null || submittedJob==null)return original.call(provider,pattern,inputs);
     var access=(AdvancedGrowthJobAccessor)(Object)submittedJob;var key=access.aecraftroute$getFinalOutput().what();
     long snapshot=DeferredFeedbackReservation.consumedInput(pattern,inputs,key);
     boolean accepted=original.call(provider,pattern,inputs);
     if(accepted && job==submittedJob && aecraftroute$deferredFeedback==ledger) {
         long used=ledger.consumed(snapshot);
         access.aecraftroute$getWaitingFor().extract(key,used,Actionable.MODULATE);
         ((AdvancedGrowthTimeAccessor)(Object)access.aecraftroute$getTimeTracker()).aecraftroute$decrementItems(used,key.getType());
         cpu.markDirty();
     }
     return accepted;
 }
 @Inject(method="tickCraftingLogic",at=@At("TAIL"))
 private void aecraftroute$releaseFeedback(IEnergyService energy,CraftingService service,CallbackInfo ci) {
     var ledger=aecraftroute$deferredFeedback;
     if(ledger==null || job==null || !cpu.isActive())return;
     var access=(AdvancedGrowthJobAccessor)(Object)job;
     if(access.aecraftroute$getSuspended() || access.aecraftroute$getLink().isCanceled())return;
     var key=access.aecraftroute$getFinalOutput().what();
     long pending;
     try {
         pending=DeferredFeedbackReservation.potentialInput(access.aecraftroute$getTasks(),
                 value->((AdvancedGrowthTaskAccessor)value).aecraftroute$getValue(),key,cpu.getLevel());
         ledger.restore(ledger.held(),access.aecraftroute$getWaitingFor().extract(key,Long.MAX_VALUE,Actionable.SIMULATE),
                 inventory.extract(key,Long.MAX_VALUE,Actionable.SIMULATE));
     } catch(RuntimeException invalid) {
         com.aecraftroute.addon.CraftRouteAddon.LOGGER.error("[AE-Craftroute] Invalid legacy feedback state; cancelling without synthetic output",invalid);
         cancel();return;
     }
     long remaining=access.aecraftroute$getRemainingAmount();
     var offer=ledger.offerRelease(pending,inventory.extract(key,Long.MAX_VALUE,Actionable.SIMULATE),
             aecraftroute$restoreTargetSeed,remaining);
     if(offer.seed()>0) {
         ledger.settle(offer.seed());aecraftroute$restoreTargetSeed-=offer.seed();
         access.aecraftroute$getWaitingFor().extract(key,offer.seed(),Actionable.MODULATE);
         ((AdvancedGrowthTimeAccessor)(Object)access.aecraftroute$getTimeTracker()).aecraftroute$decrementItems(offer.seed(),key.getType());
         cpu.markDirty();
     }
     if(offer.directLimit()<=0)return;
     var link=access.aecraftroute$getLink();
     long accepted=link.insert(key,offer.directLimit(),Actionable.MODULATE);
     if(accepted<=0 && link.isStandalone() && cpu.getGrid()!=null)
         accepted=cpu.getGrid().getStorageService().getInventory().insert(key,offer.directLimit(),Actionable.MODULATE,cpu.getSrc());
     if(accepted<=0)return;
     if(accepted>offer.directLimit())throw new IllegalStateException("Requester over-accepted deferred feedback");
     inventory.extract(key,accepted,Actionable.MODULATE);ledger.settle(accepted);
     access.aecraftroute$getWaitingFor().extract(key,accepted,Actionable.MODULATE);
     ((AdvancedGrowthTimeAccessor)(Object)access.aecraftroute$getTimeTracker()).aecraftroute$decrementItems(accepted,key.getType());
     remaining-=accepted;access.aecraftroute$setRemainingAmount(remaining);postChange(key);cpu.markDirty();
     if(remaining==0){finishJob(true);cpu.updateOutput(null);}
     else cpu.updateOutput(new GenericStack(key,remaining));
 }
 @Inject(method="finishJob",at=@At("TAIL"))
 private void aecraftroute$clearDeferred(boolean success,CallbackInfo ci) {
     aecraftroute$deferredFeedback=null;
 }

    @Inject(method="writeToNBT", at=@At("TAIL"))
    private void aecraftroute$save(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        tag.putBoolean("aecraftrouteGrowthFeedback", aecraftroute$growthFeedback && job != null);
        tag.putLong("aecraftrouteRestoreTargetSeed", aecraftroute$restoreTargetSeed);
        tag.putBoolean("aecraftrouteDeferredFeedback",job!=null && aecraftroute$deferredFeedback!=null);
        tag.putLong("aecraftrouteDeferredHeld",aecraftroute$deferredFeedback==null?0:aecraftroute$deferredFeedback.held());
    }

    @Inject(method="readFromNBT", at=@At("TAIL"))
    private void aecraftroute$load(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        // Missing marker means a pre-patch job: don't guess seed-restoration debt
        // or mutate its inventory. Cancel/resubmit it after installing the fix.
        aecraftroute$growthFeedback = job != null && feedbackFlag(tag);
        aecraftroute$restoreTargetSeed = aecraftroute$growthFeedback
                ? Math.max(0, restoreSeed(tag)) : 0;
        aecraftroute$deferredFeedback=null;
        if(aecraftroute$growthFeedback && tag.getBoolean("aecraftrouteDeferredFeedback")) {
            var access=(AdvancedGrowthJobAccessor)(Object)job;var key=access.aecraftroute$getFinalOutput().what();
            try {
                var ledger=new DeferredFeedbackReservation();
                ledger.restore(tag.getLong("aecraftrouteDeferredHeld"),
                        access.aecraftroute$getWaitingFor().extract(key,Long.MAX_VALUE,Actionable.SIMULATE),
                        inventory.extract(key,Long.MAX_VALUE,Actionable.SIMULATE));
                aecraftroute$deferredFeedback=ledger;
            } catch(RuntimeException invalid) {
                com.aecraftroute.addon.CraftRouteAddon.LOGGER.error("[AE-Craftroute] Invalid saved AdvancedAE deferred feedback; cancelling",invalid);
                cancel();return;
            }
        }
        aecraftroute$orderFeedback();
    }

    /** Feedback flags predate the rename; a saved CPU keeps its old key. */
    private static boolean feedbackFlag(net.minecraft.nbt.CompoundTag tag) {
        return tag.contains("aecraftrouteGrowthFeedback")?tag.getBoolean("aecraftrouteGrowthFeedback")
                :tag.getBoolean("ae2vmGrowthFeedback");
    }
    private static long restoreSeed(net.minecraft.nbt.CompoundTag tag) {
        if(tag.contains("aecraftrouteRestoreTargetSeed"))return tag.getLong("aecraftrouteRestoreTargetSeed");
        return tag.contains("ae2vmRestoreTargetSeed")?tag.getLong("ae2vmRestoreTargetSeed"):0;
    }
}
