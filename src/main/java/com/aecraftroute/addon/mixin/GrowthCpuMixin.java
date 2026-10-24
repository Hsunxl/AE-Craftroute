package com.aecraftroute.addon.mixin;
import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.aecraftroute.addon.vm.TwoNodeGrowthPlanner;
import com.aecraftroute.addon.vm.FeedbackReservation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
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
 @Unique private boolean aecraftroute$growthFeedback;
 @Unique private long aecraftroute$restoreTargetSeed;
 @Inject(method="trySubmitJob",at=@At("RETURN"))
 private void aecraftroute$mark(IGrid grid,ICraftingPlan plan,IActionSource src,ICraftingRequester requester,
        CallbackInfoReturnable<ICraftingSubmitResult> cir) {
     if(job!=null && cir.getReturnValue().successful())
     {
         aecraftroute$growthFeedback=!(plan instanceof com.aecraftroute.addon.planner.CertifiedCraftingPlan) && TwoNodeGrowthPlanner.containsRing(plan.patternTimes().keySet());
         aecraftroute$restoreTargetSeed=aecraftroute$growthFeedback && com.aecraftroute.addon.vm.NetProduction.sufficient(plan)
                 ?plan.usedItems().get(plan.finalOutput().what()):0;
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
     long pending=0;
     try {
         for(var task:access.aecraftroute$getTasks().entrySet()) {
             long times=((GrowthTaskAccessor)task.getValue()).aecraftroute$getValue();
             if(times<=0)continue;
             for(var in:task.getKey().getInputs()) {
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
 @Inject(method="writeToNBT",at=@At("TAIL"))
 private void aecraftroute$save(CompoundTag tag,HolderLookup.Provider registries,CallbackInfo ci) {
     tag.putBoolean("aecraftrouteGrowthFeedback",aecraftroute$growthFeedback && job!=null);
     tag.putLong("aecraftrouteRestoreTargetSeed",aecraftroute$restoreTargetSeed);
 }
 @Inject(method="readFromNBT",at=@At("TAIL"))
 private void aecraftroute$load(CompoundTag tag,HolderLookup.Provider registries,CallbackInfo ci) {
     aecraftroute$growthFeedback=job!=null && feedbackFlag(tag);
     aecraftroute$restoreTargetSeed=aecraftroute$growthFeedback?Math.max(0,restoreSeed(tag)):0;
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
