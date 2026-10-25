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
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.me.cluster.implementations.CraftingCPUCluster;

/** Certified dispatch, actual-return feedback, then delivery. No ring detector. */
@Mixin(value=CraftingCpuLogic.class,remap=false)
public abstract class ProductionCpuMixin implements com.aecraftroute.addon.compat.CertifiedExecutionOwner {
    @Shadow private ExecutingCraftingJob job;
    @Shadow @Final private ListCraftingInventory inventory;
    @Shadow @Final private CraftingCPUCluster cluster;
    @Shadow private void finishJob(boolean success) { throw new AssertionError(); }
    @Shadow private void postChange(AEKey key) { throw new AssertionError(); }
    @Unique private ProductionJob aecraftroute$production;
    @Override public boolean aecraftroute$hasCertifiedProgram() {return aecraftroute$production!=null;}
    @Unique private final com.aecraftroute.addon.telemetry.LogThrottle aecraftroute$deliveryLogs = new com.aecraftroute.addon.telemetry.LogThrottle();
    @Unique private Object aecraftroute$lastPosition;
    @Unique private long aecraftroute$stallSince, aecraftroute$lastStallLog;
    @Unique private final com.aecraftroute.addon.telemetry.LogThrottle aecraftroute$dispatchLogs = new com.aecraftroute.addon.telemetry.LogThrottle();

    /** A certified job that dispatches nothing for 30 s is a stall, not slow crafting:
     * print the cursor, the outstanding returns and the CPU inventory so the blocked
     * step is visible instead of guessing. */
    @Unique private void aecraftroute$watchStall() {
        if(aecraftroute$production==null || job==null || aecraftroute$production.done()) return;
        var access=(GrowthJobAccessor)(Object)job;
        var position=aecraftroute$production.position();
        long now=System.nanoTime();
        if(!position.equals(aecraftroute$lastPosition)) { aecraftroute$lastPosition=position; aecraftroute$stallSince=now; return; }
        if(now-aecraftroute$stallSince<30_000_000_000L || now-aecraftroute$lastStallLog<30_000_000_000L) return;
        aecraftroute$lastStallLog=now;
        com.aecraftroute.addon.CraftRouteAddon.LOGGER.warn(
                "[AE-Craftroute] certified job stalled 30s: position={} remaining={} active={} suspended={} canceled={} waitingFor={} inventory={}",
                position, aecraftroute$production.remainingCounts(), cluster.isActive(), access.aecraftroute$getSuspended(),
                access.aecraftroute$getLink().isCanceled(), access.aecraftroute$getWaitingFor().list, inventory.list);
    }

    @Inject(method="trySubmitJob",at=@At("RETURN"))
    private void aecraftroute$attach(IGrid grid,ICraftingPlan plan,IActionSource source,ICraftingRequester requester,
            CallbackInfoReturnable<ICraftingSubmitResult> cir) {
        if(cir.getReturnValue().successful()) {
            aecraftroute$deliveryLogs.reset();
            aecraftroute$dispatchLogs.reset();
            aecraftroute$lastPosition=null; aecraftroute$stallSince=System.nanoTime(); aecraftroute$lastStallLog=0;
            if(plan instanceof CertifiedCraftingPlan certified){
                aecraftroute$production=new ProductionJob(certified);
                com.aecraftroute.addon.api.DispatchTrace.certified("ae2");
            }else{
                aecraftroute$production=null;
            }
            cluster.markDirty();
        }
    }

    @WrapOperation(method="executeCrafting",at=@At(value="INVOKE",target="Lappeng/crafting/execution/CraftingCpuHelper;extractPatternInputs(Lappeng/api/crafting/IPatternDetails;Lappeng/crafting/inv/ICraftingInventory;Lnet/minecraft/world/level/Level;Lappeng/api/stacks/KeyCounter;Lappeng/api/stacks/KeyCounter;)[Lappeng/api/stacks/KeyCounter;"))
    private KeyCounter[] aecraftroute$orderedInputs(IPatternDetails pattern,ICraftingInventory inv,Level level,
            KeyCounter expected,KeyCounter containers,Operation<KeyCounter[]> original) {
        if(aecraftroute$production==null) return original.call(pattern,inv,level,expected,containers);
        var extracted=aecraftroute$production.extract(pattern,inv,level,expected,containers);
        if(extracted==null && aecraftroute$production.allows(pattern) && aecraftroute$dispatchLogs.acquireDelayed("inputs"))
            com.aecraftroute.addon.CraftRouteAddon.LOGGER.warn(
                    "[AE-Craftroute] certified dispatch waiting: current step inputs unavailable or invalid; inventory={}",
                    inventory.list);
        return extracted;
    }

    @WrapOperation(method="executeCrafting",at=@At(value="INVOKE",target="Lappeng/api/networking/crafting/ICraftingProvider;pushPattern(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z"))
    private boolean aecraftroute$accepted(ICraftingProvider provider,IPatternDetails pattern,KeyCounter[] inputs,Operation<Boolean> original) {
        if(aecraftroute$production!=null && !aecraftroute$production.allows(pattern)) return false;
        boolean accepted=original.call(provider,pattern,inputs);
        if(accepted && aecraftroute$production!=null) { aecraftroute$production.accepted(pattern); aecraftroute$dispatchLogs.reset(); cluster.markDirty(); }
        else if(!accepted && aecraftroute$production!=null && aecraftroute$dispatchLogs.acquireDelayed("refused"))
            com.aecraftroute.addon.CraftRouteAddon.LOGGER.warn(
                    "[AE-Craftroute] certified dispatch refused by provider {} for the current step",
                    provider.getClass().getName());
        return accepted;
    }

    @WrapMethod(method="insert")
    private long aecraftroute$returnToInventory(AEKey key,long amount,Actionable mode,Operation<Long> original) {
        if(aecraftroute$production==null || job==null) return original.call(key,amount,mode);
        if(key==null || amount<=0) return 0;
        var access=(GrowthJobAccessor)(Object)job;
        var waiting=access.aecraftroute$getWaitingFor();
        long accepted=waiting.extract(key,amount,Actionable.SIMULATE);
        if(mode==Actionable.MODULATE && accepted>0) {
            waiting.extract(key,accepted,Actionable.MODULATE);
            inventory.insert(key,accepted,Actionable.MODULATE);
            ((GrowthTimeAccessor)(Object)access.aecraftroute$getTimeTracker()).aecraftroute$decrementItems(accepted,key.getType());
            postChange(key); cluster.markDirty();
        }
        // Delivery is deliberately deferred until tick TAIL, never re-entrant in pushPattern.
        return accepted;
    }

    @Inject(method="tickCraftingLogic",at=@At("TAIL"))
    private void aecraftroute$deliver(IEnergyService energy,CraftingService service,CallbackInfo ci) {
        aecraftroute$watchStall();
        if(aecraftroute$production==null || job==null || !aecraftroute$production.done()) return;
        var access=(GrowthJobAccessor)(Object)job;
        if(access.aecraftroute$getLink().isCanceled() || access.aecraftroute$getSuspended() || !cluster.isActive()) return;
        long remaining=access.aecraftroute$getRemainingAmount();
        AEKey key=access.aecraftroute$getFinalOutput().what();
        // Only the target's own outstanding returns may delay delivery. A byproduct or a
        // catalyst the machine voided would otherwise pin this CPU forever; leftovers are
        // stored back by native storeItems when the job ends.
        if(!aecraftroute$production.readyToDeliver(access.aecraftroute$getWaitingFor().list,key)) {
            if(aecraftroute$deliveryLogs.acquireDelayed("waiting"))
                com.aecraftroute.addon.CraftRouteAddon.LOGGER.warn("[AE-Craftroute] AE2 CPU delivery waiting for the last {} x{} from accepted dispatches",
                        key,access.aecraftroute$getWaitingFor().list.get(key));
            return;
        }
        long held=inventory.extract(key,remaining,Actionable.SIMULATE);
        if(held<=0) {
            if(aecraftroute$deliveryLogs.acquire("missing"))
                com.aecraftroute.addon.CraftRouteAddon.LOGGER.warn("[AE-Craftroute] AE2 CPU delivery pending: no {} x{} in the CPU inventory yet",key,remaining);
            return;
        }
        long accepted=access.aecraftroute$getLink().insert(key,held,Actionable.MODULATE);
        boolean toNetwork=false;
        if(accepted<=0) {
            // Player-initiated jobs carry no requester link (native AE2 only builds one for
            // machines); native semantics then return the finished output to the network.
            var grid=cluster.getGrid();
            if(grid!=null) accepted=grid.getStorageService().getInventory().insert(key,held,Actionable.MODULATE,cluster.getSrc());
            toNetwork=true;
        }
        if(accepted<=0) {
            if(aecraftroute$deliveryLogs.acquire("blocked"))
                com.aecraftroute.addon.CraftRouteAddon.LOGGER.warn("[AE-Craftroute] AE2 CPU delivery blocked: neither requester nor network accepted {} x{}",key,held);
            return;
        }
        if(com.aecraftroute.addon.config.CraftRouteConfig.isDebugLogging())
            com.aecraftroute.addon.CraftRouteAddon.LOGGER.info("[AE-Craftroute] AE2 CPU delivered {} x{} to {}, remaining was {}",key,accepted,toNetwork?"the network":"the requester",remaining);
        inventory.extract(key,accepted,Actionable.MODULATE);
        remaining-=accepted; access.aecraftroute$setRemainingAmount(remaining);
        postChange(key); cluster.markDirty();
        if(remaining==0) {
            finishJob(true); cluster.updateOutput(null); aecraftroute$production=null;
        } else cluster.updateOutput(new GenericStack(key,remaining));
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
        boolean saved=savedTag!=null;
        aecraftroute$production=job!=null && savedTag!=null
                ?ProductionJob.load(savedTag,registries,cluster.getLevel()):null;
        if(aecraftroute$production!=null) {
            var remaining=new java.util.HashMap<IPatternDetails,Long>();
            ((GrowthJobAccessor)(Object)job).aecraftroute$getTasks().forEach((pattern,progress)->
                    remaining.put(pattern,((GrowthTaskAccessor)progress).aecraftroute$getValue()));
            boolean valid=aecraftroute$production.validateRemainingTasks(remaining);
            com.aecraftroute.addon.CraftRouteAddon.LOGGER.info("[AE-Craftroute] restored production program: saved={} restored=true tasks={} valid={}",
                    saved,remaining.size(),valid);
        } else {
            com.aecraftroute.addon.CraftRouteAddon.LOGGER.info("[AE-Craftroute] no production program restored: saved={} job={}",
                    saved,job!=null);
        }
    }
}

