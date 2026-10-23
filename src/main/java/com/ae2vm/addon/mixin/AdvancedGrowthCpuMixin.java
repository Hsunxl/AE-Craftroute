package com.ae2vm.addon.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.crafting.inv.ListCraftingInventory;
import com.ae2vm.addon.vm.FeedbackReservation;
import com.ae2vm.addon.vm.NetProduction;
import com.ae2vm.addon.vm.TwoNodeGrowthPlanner;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
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
    @Unique private boolean ae2vm$growthFeedback;
    @Unique private long ae2vm$restoreTargetSeed;

    @Inject(method="trySubmitJob", at=@At("RETURN"))
    private void ae2vm$mark(IGrid grid, ICraftingPlan plan, IActionSource source,
            ICraftingRequester requester, CallbackInfoReturnable<ICraftingSubmitResult> cir) {
        if (job != null && cir.getReturnValue().successful()) {
            ae2vm$growthFeedback = !(plan instanceof com.ae2vm.addon.planner.CertifiedCraftingPlan) && TwoNodeGrowthPlanner.containsRing(plan.patternTimes().keySet());
            ae2vm$restoreTargetSeed = ae2vm$growthFeedback && NetProduction.sufficient(plan)
                    ? plan.usedItems().get(plan.finalOutput().what()) : 0;
            ae2vm$orderFeedback();
        }
    }

    @Unique private void ae2vm$orderFeedback() {
        if (ae2vm$growthFeedback && job != null) {
            AdvancedGrowthJobAccessor access = (AdvancedGrowthJobAccessor)(Object)job;
            access.ae2vm$setTasks(TwoNodeGrowthPlanner.prioritizeFeedback(access.ae2vm$getTasks()));
        }
    }

    @WrapMethod(method="insert")
    private long ae2vm$reserveFeedback(AEKey what, long amount, Actionable mode, Operation<Long> original) {
        if (!ae2vm$growthFeedback || job == null || what == null || amount <= 0)
            return original.call(what, amount, mode);
        AdvancedGrowthJobAccessor access = (AdvancedGrowthJobAccessor)(Object)job;
        if (!what.matches(access.ae2vm$getFinalOutput())) return original.call(what, amount, mode);
        long pending = 0;
        try {
            for (var task : access.ae2vm$getTasks().entrySet()) {
                long times = ((AdvancedGrowthTaskAccessor)task.getValue()).ae2vm$getValue();
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
        var waiting = access.ae2vm$getWaitingFor();
        long expected = waiting.extract(what, amount, Actionable.SIMULATE);
        long reserve = FeedbackReservation.reserve(amount, expected, pending,
                inventory.extract(what, Long.MAX_VALUE, Actionable.SIMULATE));
        long restore = Math.min(ae2vm$restoreTargetSeed,
                Math.min(amount - reserve, Math.max(0, expected - reserve)));
        long retained = reserve + restore;
        if (retained == 0) return original.call(what, amount, mode);
        if (mode == Actionable.MODULATE) {
            waiting.extract(what, retained, Actionable.MODULATE);
            inventory.insert(what, retained, Actionable.MODULATE);
            ae2vm$restoreTargetSeed -= restore;
            ((AdvancedGrowthTimeAccessor)(Object)access.ae2vm$getTimeTracker())
                    .ae2vm$decrementItems(retained, what.getType());
            cpu.markDirty();
        }
        return retained + (amount > retained ? original.call(what, amount - retained, mode) : 0L);
    }

    @Inject(method="writeToNBT", at=@At("TAIL"))
    private void ae2vm$save(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        tag.putBoolean("ae2vmGrowthFeedback", ae2vm$growthFeedback && job != null);
        tag.putLong("ae2vmRestoreTargetSeed", ae2vm$restoreTargetSeed);
    }

    @Inject(method="readFromNBT", at=@At("TAIL"))
    private void ae2vm$load(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        // Missing marker means a pre-patch job: don't guess seed-restoration debt
        // or mutate its inventory. Cancel/resubmit it after installing the fix.
        ae2vm$growthFeedback = job != null && tag.getBoolean("ae2vmGrowthFeedback");
        ae2vm$restoreTargetSeed = ae2vm$growthFeedback
                ? Math.max(0, tag.getLong("ae2vmRestoreTargetSeed")) : 0;
        ae2vm$orderFeedback();
    }
}
