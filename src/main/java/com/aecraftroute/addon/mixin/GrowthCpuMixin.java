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
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.aecraftroute.addon.vm.TwoNodeGrowthPlanner;
import com.aecraftroute.addon.vm.FeedbackReservation;
import com.aecraftroute.addon.vm.DeferredFeedbackReservation;
import com.aecraftroute.addon.vm.LegacyFeedbackSave;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** Keep feedback inputs inside the CPU instead of delivering them as final output.
 * Uses the REAL remaining task counts, waiting-for gate and CPU inventory; pending
 * operations already dispatched have consumed their inputs and need no reservation.
 * Only activated on jobs whose actual pattern set contains the supported growth pair.
 */
@Mixin(value=CraftingCpuLogic.class,remap=false)
public abstract class GrowthCpuMixin {
 @Shadow private ExecutingCraftingJob job;
 @Shadow @Final private ListCraftingInventory inventory;
 @Shadow @Final private CraftingCPUCluster cluster;
 @Shadow private void finishJob(boolean success){throw new AssertionError();}
 @Shadow public void cancel(){throw new AssertionError();}
 @Shadow private void postChange(AEKey key){throw new AssertionError();}
 @Unique private boolean aecraftroute$growthFeedback;
 @Unique private long aecraftroute$restoreTargetSeed;
 @Unique private DeferredFeedbackReservation aecraftroute$deferredFeedback;
 @Inject(method="trySubmitJob",at=@At("RETURN"))
 private void aecraftroute$mark(IGrid grid,ICraftingPlan plan,IActionSource src,ICraftingRequester requester,
        CallbackInfoReturnable<ICraftingSubmitResult> cir) {
     if(job!=null && cir.getReturnValue().successful())
     {
         // An unregistered add-on requester owns its execution contract; do not
         // reorder or retain its native/foreign job's feedback on our behalf.
         aecraftroute$growthFeedback=!(plan instanceof com.aecraftroute.addon.planner.CertifiedCraftingPlan)
                 && (requester==null || !com.aecraftroute.addon.api.CraftRouteCraftingRegistry.isUnregisteredThirdParty(requester.getClass().getName()))
                 && TwoNodeGrowthPlanner.containsRing(plan.patternTimes().keySet());
         aecraftroute$restoreTargetSeed=aecraftroute$growthFeedback && com.aecraftroute.addon.vm.NetProduction.sufficient(plan)
                 ?plan.usedItems().get(plan.finalOutput().what()):0;
         aecraftroute$deferredFeedback=aecraftroute$growthFeedback?new DeferredFeedbackReservation():null;
         aecraftroute$orderFeedback();
     }
 }
 @Unique private void aecraftroute$orderFeedback() {
     if(aecraftroute$growthFeedback && job!=null) {
         GrowthJobAccessor a=(GrowthJobAccessor)(Object)job;
         a.aecraftroute$setTasks(TwoNodeGrowthPlanner.prioritizeFeedback(a.aecraftroute$getTasks()));
     }
 }
 @WrapMethod(method="insert")
 private long aecraftroute$reserveFeedback(AEKey what,long amount,Actionable mode,Operation<Long> original) {
     if(!aecraftroute$growthFeedback || job==null || what==null || amount<=0)
         return original.call(what,amount,mode);
     GrowthJobAccessor access=(GrowthJobAccessor)(Object)job;
     if(!what.matches(access.aecraftroute$getFinalOutput()))return original.call(what,amount,mode);
     if(aecraftroute$deferredFeedback!=null) {
         long accepted=aecraftroute$deferredFeedback.offerBuffer(amount,
                 access.aecraftroute$getWaitingFor().extract(what,Long.MAX_VALUE,Actionable.SIMULATE));
         if(mode==Actionable.MODULATE && accepted>0) {
             inventory.insert(what,accepted,Actionable.MODULATE);
             aecraftroute$deferredFeedback.retain(accepted);cluster.markDirty();
         }
         return accepted;
     }
     long pending=0;
     try {
         for(var task:access.aecraftroute$getTasks().entrySet()) {
             long times=((GrowthTaskAccessor)task.getValue()).aecraftroute$getValue();
             if(times<=0)continue;
             // A foreign pattern may expose no resolvable slots. That proves nothing
             // about this key, so skip it: throwing here would escape our hook into the
             // provider's insert, and saturating would reserve on unproven grounds.
             var inputs=task.getKey().getInputs();
             if(inputs==null)continue;
             for(var in:inputs) {
                 if(in==null)continue;
                 var choices=in.getPossibleInputs();
                 if(choices==null || choices.length==0 || choices[0]==null || !what.equals(choices[0].what()))continue;
                 if(what.equals(in.getRemainingKey(what)))continue;
                 pending=Math.addExact(pending,Math.multiplyExact(times,
                         Math.multiplyExact(in.getMultiplier(),choices[0].amount())));
             }
         }
     }catch(ArithmeticException ex){pending=Long.MAX_VALUE;}
     var waiting=access.aecraftroute$getWaitingFor();
     long reserve=FeedbackReservation.reserve(amount,waiting.extract(what,amount,Actionable.SIMULATE),
             pending,inventory.extract(what,Long.MAX_VALUE,Actionable.SIMULATE));
     // Seed replacement is not order delivery. Keep it as CPU surplus, which
     // native storeItems returns to ME after completion. This guarantees a NET
     // increase even for one-item output packets (e.g. requesting dust itself).
     long expected=waiting.extract(what,amount,Actionable.SIMULATE);
     long restore=Math.min(aecraftroute$restoreTargetSeed,Math.min(amount-reserve,Math.max(0,expected-reserve)));
     long retained=reserve+restore;
     if(retained==0)return original.call(what,amount,mode);
     if(mode==Actionable.MODULATE) {
         waiting.extract(what,retained,Actionable.MODULATE);
         inventory.insert(what,retained,Actionable.MODULATE);
         aecraftroute$restoreTargetSeed-=restore;
         ((GrowthTimeAccessor)(Object)access.aecraftroute$getTimeTracker()).aecraftroute$decrementItems(retained,what.getType());
         cluster.markDirty();
     }
     return retained+(amount>retained?original.call(what,amount-retained,mode):0L);
 }
 @WrapOperation(method="executeCrafting",at=@At(value="INVOKE",target="Lappeng/api/networking/crafting/ICraftingProvider;pushPattern(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z"))
 private boolean aecraftroute$settleFeedbackInput(ICraftingProvider provider,IPatternDetails pattern,
         KeyCounter[] inputs,Operation<Boolean> original) {
     var ledger=aecraftroute$deferredFeedback;var submittedJob=job;
     if(ledger==null || submittedJob==null)return original.call(provider,pattern,inputs);
     var access=(GrowthJobAccessor)(Object)submittedJob;var key=access.aecraftroute$getFinalOutput().what();
     long snapshot=DeferredFeedbackReservation.consumedInput(pattern,inputs,key);
     boolean accepted=original.call(provider,pattern,inputs);
     if(accepted && job==submittedJob && aecraftroute$deferredFeedback==ledger) {
         long used=ledger.consumed(snapshot);
         access.aecraftroute$getWaitingFor().extract(key,used,Actionable.MODULATE);
         ((GrowthTimeAccessor)(Object)access.aecraftroute$getTimeTracker()).aecraftroute$decrementItems(used,key.getType());
         cluster.markDirty();
     }
     return accepted;
 }
 @Inject(method="tickCraftingLogic",at=@At("TAIL"))
 private void aecraftroute$releaseFeedback(IEnergyService energy,CraftingService service,CallbackInfo ci) {
     var ledger=aecraftroute$deferredFeedback;
     if(ledger==null || job==null || !cluster.isActive())return;
     var access=(GrowthJobAccessor)(Object)job;
     if(access.aecraftroute$getSuspended() || access.aecraftroute$getLink().isCanceled())return;
     var key=access.aecraftroute$getFinalOutput().what();
     long pending;
     try {
         // Unresolvable candidate slots are not a corrupt save: hold, never cancel.
          pending=DeferredFeedbackReservation.pendingInputOrUnknown(access.aecraftroute$getTasks(),
                 value->((GrowthTaskAccessor)value).aecraftroute$getValue(),key,cluster.getLevel());
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
         ((GrowthTimeAccessor)(Object)access.aecraftroute$getTimeTracker()).aecraftroute$decrementItems(offer.seed(),key.getType());
         cluster.markDirty();
     }
     if(offer.directLimit()<=0)return;
     var link=access.aecraftroute$getLink();
     long accepted=link.insert(key,offer.directLimit(),Actionable.MODULATE);
     if(accepted<=0 && link.isStandalone() && cluster.getGrid()!=null)
         accepted=cluster.getGrid().getStorageService().getInventory().insert(key,offer.directLimit(),Actionable.MODULATE,cluster.getSrc());
     if(accepted<=0)return;
     if(accepted>offer.directLimit())throw new IllegalStateException("Requester over-accepted deferred feedback");
     inventory.extract(key,accepted,Actionable.MODULATE);ledger.settle(accepted);
     access.aecraftroute$getWaitingFor().extract(key,accepted,Actionable.MODULATE);
     ((GrowthTimeAccessor)(Object)access.aecraftroute$getTimeTracker()).aecraftroute$decrementItems(accepted,key.getType());
     remaining-=accepted;access.aecraftroute$setRemainingAmount(remaining);postChange(key);cluster.markDirty();
     if(remaining==0){finishJob(true);cluster.updateOutput(null);}
     else cluster.updateOutput(new GenericStack(key,remaining));
 }
 @Inject(method="finishJob",at=@At("TAIL"))
 private void aecraftroute$clearDeferred(boolean success,CallbackInfo ci) {
     aecraftroute$deferredFeedback=null;
 }
 @Inject(method="writeToNBT",at=@At("TAIL"))
 private void aecraftroute$save(CompoundTag tag,HolderLookup.Provider registries,CallbackInfo ci) {
     tag.putBoolean("aecraftrouteGrowthFeedback",aecraftroute$growthFeedback && job!=null);
     tag.putLong("aecraftrouteRestoreTargetSeed",aecraftroute$restoreTargetSeed);
     tag.putBoolean("aecraftrouteDeferredFeedback",job!=null && aecraftroute$deferredFeedback!=null);
     tag.putLong("aecraftrouteDeferredHeld",aecraftroute$deferredFeedback==null?0:aecraftroute$deferredFeedback.held());
 }
 @Inject(method="readFromNBT",at=@At("TAIL"))
 private void aecraftroute$load(CompoundTag tag,HolderLookup.Provider registries,CallbackInfo ci) {
     LegacyFeedbackSave.State saved;
     try {saved=LegacyFeedbackSave.read(tag);}
     catch(RuntimeException invalid) {
         com.aecraftroute.addon.CraftRouteAddon.LOGGER.error("[AE-Craftroute] Invalid saved feedback metadata; cancelling",invalid);
         cancel();return;
     }
     aecraftroute$growthFeedback=job!=null && saved.enabled();
     aecraftroute$restoreTargetSeed=aecraftroute$growthFeedback?saved.restoreSeed():0;
     aecraftroute$deferredFeedback=null;
     if(aecraftroute$growthFeedback && saved.deferred()) {
         var access=(GrowthJobAccessor)(Object)job;var key=access.aecraftroute$getFinalOutput().what();
         try {
             // A job whose own counts cannot drain would pin held credit forever.
             DeferredFeedbackReservation.requireSaneRuns(access.aecraftroute$getTasks(),
                     value->((GrowthTaskAccessor)value).aecraftroute$getValue());
             var ledger=new DeferredFeedbackReservation();
             ledger.restore(saved.held(),
                     access.aecraftroute$getWaitingFor().extract(key,Long.MAX_VALUE,Actionable.SIMULATE),
                     inventory.extract(key,Long.MAX_VALUE,Actionable.SIMULATE));
             aecraftroute$deferredFeedback=ledger;
         } catch(RuntimeException invalid) {
             com.aecraftroute.addon.CraftRouteAddon.LOGGER.error("[AE-Craftroute] Invalid saved deferred feedback; cancelling",invalid);
             cancel();return;
         }
     }
     // Native readFromNBT drops a job whose final output is unreadable. Stock is
     // refunded by finishJob, but the held credit disappears; never do that silently.
     if(saved.deferred() && job==null)
         com.aecraftroute.addon.CraftRouteAddon.LOGGER.error("[AE-Craftroute] Saved deferred feedback discarded: native CPU job did not load (held={})",saved.held());
     aecraftroute$orderFeedback();
 }

    /** Feedback metadata is decoded by LegacyFeedbackSave before any CPU state is changed. */
}
