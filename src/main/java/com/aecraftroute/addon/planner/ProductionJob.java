package com.aecraftroute.addon.planner;

import appeng.api.config.Actionable;
import appeng.api.crafting.*;
import appeng.api.stacks.*;
import appeng.crafting.inv.ICraftingInventory;
import appeng.crafting.execution.CraftingCpuHelper;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.world.level.Level;
import java.util.*;
import java.math.BigInteger;

/** Shared execution protocol for native and AdvancedAE CPUs. All expected output
 * is real CPU inventory (including the target), so it can feed the next step.
 * Delivery is a separate phase after dispatch, based on real terminal surplus
 * rather than estimated outstanding returns.
 */
public final class ProductionJob {
    public static final String TAG="aecraftrouteProductionV1";
    /**
     * Pre-rename key. Stored production programs outlive a mod rename, so an existing
     * CPU job is read from here when the new key is absent. Without it a rename silently
     * discards the work of every running CPU while its materials stay consumed.
     */
    public static final String LEGACY_TAG="ae2vmProductionV1";

    /** The saved program under either key, or null when this CPU holds none. */
    public static CompoundTag savedTag(net.minecraft.nbt.CompoundTag tag) {
        if(tag.contains(TAG))return tag.getCompound(TAG);
        return tag.contains(LEGACY_TAG)?tag.getCompound(LEGACY_TAG):null;
    }
    private final ProductionCursor<PatternVariant> cursor;
    private boolean invalid;
    private final Map<AEKey,Long> capital=new HashMap<>();
    public ProductionJob(CertifiedCraftingPlan plan) {
        this(new ProductionCursor<>(plan.program()),false);
        capital.put(plan.finalOutput().what(),Math.max(0,plan.usedItems().get(plan.finalOutput().what())));
    }
    private ProductionJob(ProductionCursor<PatternVariant> cursor,boolean invalid) { this.cursor=cursor; this.invalid=invalid; }
    public boolean done() { return !invalid && cursor.done(); }
    /** Legacy conservative gate retained for callers of older versions. Internal
     * delivery uses deliverableAmount: estimates are not a completion condition.
     * Delivery may start once the program is finished AND no expected return of the
     * target itself is still in flight. Returns of any other key - byproducts, catalysts
     * the machine consumed, containers a provider voided - must NOT be waited for: a
     * single return that never arrives would otherwise pin the CPU forever. Those
     * leftovers simply stay in the CPU inventory and native storeItems returns them to
     * the network when the job ends.
     */
    public boolean readyToDeliver(KeyCounter waitingFor,AEKey target) {
        return target!=null && done() && waitingFor.get(target)==0L;
    }
    /** Actual terminal surplus, never an estimated-output or progress-percentage
     * shortcut. Preserve the target's original working seed even if a machine
     * underproduces. Partial acceptance can retry without losing seed or output. */
    public long deliverableAmount(KeyCounter inventory,AEKey target,long remaining) {
        if(target==null || remaining<=0 || !done())return 0;
        long held=Math.max(0,inventory.get(target));if(held==0)return 0;
        long seed;
        try {seed=capital.computeIfAbsent(target,this::requiredCapital);}
        catch(ArithmeticException | IllegalArgumentException malformed) {
            invalid=true;
            com.aecraftroute.addon.CraftRouteAddon.LOGGER.error("[AE-Craftroute] Cannot recover delivery capital from saved program; no items changed",malformed);
            return 0;
        }
        return Math.min(remaining,Math.max(0,held-seed));
    }
    /** Recover the same prefix capital from an old saved program without changing
     * the NBT format, advancing its cursor or inferring progress from stock. */
    private long requiredCapital(AEKey target) {
        BigInteger required=BigInteger.ZERO,change=BigInteger.ZERO;
        for(var block:cursor.program()) {
            BigInteger localRequired=BigInteger.ZERO,localChange=BigInteger.ZERO;
            for(var step:block.word()) {
                var flow=step.recipe().recipe();
                var input=BigInteger.valueOf(flow.inputs().getOrDefault(target,0L));
                var delta=BigInteger.valueOf(flow.outputs().getOrDefault(target,0L)).subtract(input);
                var seed=input.add(delta.negate().max(BigInteger.ZERO).multiply(BigInteger.valueOf(step.count()-1)));
                localRequired=localRequired.max(seed.subtract(localChange));
                localChange=localChange.add(delta.multiply(BigInteger.valueOf(step.count())));
            }
            var seed=localRequired.add(localChange.negate().max(BigInteger.ZERO).multiply(BigInteger.valueOf(block.repetitions()-1)));
            required=required.max(seed.subtract(change));change=change.add(localChange.multiply(BigInteger.valueOf(block.repetitions())));
        }
        return required.max(BigInteger.ZERO).longValueExact();
    }
    /** Native tasks and the certified cursor must describe the same future work.
     * waitingFor may still contain outputs from already accepted dispatches. */
    public boolean validateRemainingTasks(Map<IPatternDetails,Long> tasks) {
        if(invalid) return false;
        try {
            var certified=new HashMap<IPatternDetails,Long>();
            cursor.remainingCounts().forEach((variant,count)->certified.merge(variant.pattern(),count,Math::addExact));
            var actual=new HashMap<IPatternDetails,Long>();
            tasks.forEach((pattern,count)-> {
                if(pattern==null || count==null || count<0) throw new IllegalArgumentException("Invalid native task");
                if(count>0) actual.put(pattern,count);
            });
            if(certified.equals(actual)) return true;
        } catch(RuntimeException malformed) { }
        invalid=true;
        com.aecraftroute.addon.CraftRouteAddon.LOGGER.error("[AE-Craftroute] Native tasks disagree with production cursor; cancel and resubmit. No inventory mutation.");
        return false;
    }
    /** Execution position, for stall diagnostics: unchanged means nothing dispatched. */
    public ProductionCursor.Position position() { return cursor.position(); }
    /** Undispatched counts merged by pattern, for stall diagnostics. */
    public Map<IPatternDetails,Long> remainingCounts() {
        var merged=new HashMap<IPatternDetails,Long>();
        cursor.remainingCounts().forEach((variant,count)->merged.merge(variant.pattern(),count,Math::addExact));
        return merged;
    }
    public boolean allows(IPatternDetails p) { return !invalid && cursor.current()!=null && cursor.current().pattern().equals(p); }
    public void accepted(IPatternDetails p) {
        if(!allows(p)) throw new IllegalStateException("Uncertified dispatch");
        cursor.accepted(cursor.current());
    }
    public KeyCounter[] extract(IPatternDetails p,ICraftingInventory inventory,Level level,KeyCounter outputs,KeyCounter containers) {
        if(!allows(p)) return null;
        var variant=cursor.current(); var slots=variant.slots();
        var extracted=new KeyCounter[slots.size()];
        for(int i=0;i<slots.size();i++) {
            extracted[i]=new KeyCounter(); var input=p.getInputs()[i];
            for(var portion:slots.get(i).portions()) {
                var stack=portion.template(); long count=Math.multiplyExact(stack.amount(),portion.multiplier());
                if(!input.isValid(stack.what(),level) || inventory.extract(stack.what(),count,Actionable.SIMULATE)<count) {
                    CraftingCpuHelper.reinjectPatternInputs(inventory,extracted); return null;
                }
                long got=inventory.extract(stack.what(),count,Actionable.MODULATE);
                extracted[i].add(stack.what(),got);
                if(got!=count) { CraftingCpuHelper.reinjectPatternInputs(inventory,extracted); return null; }
            }
        }
        for(var out:p.getOutputs()) outputs.add(out.what(),out.amount());
        for(int i=0;i<slots.size();i++) for(var portion:slots.get(i).portions()) {
            var remaining=p.getInputs()[i].getRemainingKey(portion.template().what());
            if(remaining!=null) containers.add(remaining,portion.multiplier());
        }
        return extracted;
    }
    public CompoundTag save(HolderLookup.Provider registries) {
        return save(pattern->pattern.getDefinition().toTag(registries),stack->GenericStack.writeTag(registries,stack));
    }
    CompoundTag save(java.util.function.Function<IPatternDetails,CompoundTag> encodePattern,
            java.util.function.Function<GenericStack,CompoundTag> encodeStack) {
        var tag=new CompoundTag(); tag.putBoolean("invalid",invalid);
        var blocks=new ListTag();
        for(var block:cursor.program()) {
            var b=new CompoundTag(); b.putLong("repeat",block.repetitions()); var steps=new ListTag();
            for(var step:block.word()) {
                var s=new CompoundTag(); s.putLong("count",step.count());
                s.put("pattern",com.aecraftroute.addon.compat.ScaledPatternCompat.encode(step.recipe().pattern(),encodePattern));
                var slots=new ListTag();
                for(var slot:step.recipe().slots()) {
                    var portions=new ListTag();
                    for(var portion:slot.portions()) {
                        var item=encodeStack.apply(portion.template()); item.putLong("allocation",portion.multiplier()); portions.add(item);
                    }
                    var encodedSlot=new CompoundTag(); encodedSlot.put("portions",portions); slots.add(encodedSlot);
                }
                var fingerprint=new ListTag();
                for(var output:step.recipe().pattern().getOutputs()) fingerprint.add(encodeStack.apply(output));
                s.put("outputs",fingerprint);
                s.put("flow",flow(step.recipe(),encodeStack));
                s.put("slots",slots); steps.add(s);
            }
            b.put("steps",steps); blocks.add(b);
        }
        tag.put("blocks",blocks); var p=cursor.position();
        tag.putInt("block",p.block()); tag.putInt("step",p.step()); tag.putLong("round",p.round()); tag.putLong("completed",p.completed());
        return tag;
    }
    public static ProductionJob load(CompoundTag tag,HolderLookup.Provider registries,Level level) {
        return load(tag,encoded->PatternDetailsHelper.decodePattern(AEItemKey.fromTag(registries,encoded),level),
                encoded->GenericStack.readTag(registries,encoded),stack->GenericStack.writeTag(registries,stack),level);
    }
    static ProductionJob load(CompoundTag tag,java.util.function.Function<CompoundTag,IPatternDetails> decodePattern,
            java.util.function.Function<CompoundTag,GenericStack> decodeStack,
            java.util.function.Function<GenericStack,CompoundTag> encodeStack) {
        return load(tag,decodePattern,decodeStack,encodeStack,null);
    }
    private static ProductionJob load(CompoundTag tag,java.util.function.Function<CompoundTag,IPatternDetails> decodePattern,
            java.util.function.Function<CompoundTag,GenericStack> decodeStack,
            java.util.function.Function<GenericStack,CompoundTag> encodeStack,Level level) {
        try {
            if(tag.getBoolean("invalid")) throw new IllegalArgumentException("Previously invalid schedule");
            var program=new ArrayList<ProductionPlanner.Block<PatternVariant>>();
            var blocks=tag.getList("blocks",Tag.TAG_COMPOUND);
            if(blocks.isEmpty() || blocks.size()>120_000) throw new IllegalArgumentException("Invalid program size");
            for(int b=0;b<blocks.size();b++) {
                var data=blocks.getCompound(b); var steps=data.getList("steps",Tag.TAG_COMPOUND);
                if(steps.isEmpty() || steps.size()>16) throw new IllegalArgumentException("Invalid word size");
                var word=new ArrayList<ProductionPlanner.Step<PatternVariant>>();
                for(int s=0;s<steps.size();s++) {
                    var entry=steps.getCompound(s); var pattern=com.aecraftroute.addon.compat.ScaledPatternCompat.decode(entry.getCompound("pattern"),decodePattern,level);
                    if(pattern==null) throw new IllegalArgumentException("Pattern no longer decodes");
                    var encoded=entry.getList("slots",Tag.TAG_COMPOUND); var slots=new ArrayList<PatternVariant.Slot>();
                    if(encoded.size()!=pattern.getInputs().length) throw new IllegalArgumentException("Pattern slots changed");
                    var currentOutputs=new ListTag();
                    for(var output:pattern.getOutputs()) currentOutputs.add(encodeStack.apply(output));
                    if(!currentOutputs.equals(entry.getList("outputs",Tag.TAG_COMPOUND))) throw new IllegalArgumentException("Pattern outputs changed");
                    for(int i=0;i<encoded.size();i++) {
                        var portions=encoded.getCompound(i).getList("portions",Tag.TAG_COMPOUND);
                        var allocation=new ArrayList<PatternVariant.Portion>();
                        for(int j=0;j<portions.size();j++) {
                            var item=portions.getCompound(j); var stack=decodeStack.apply(item);
                            allocation.add(new PatternVariant.Portion(stack,item.getLong("allocation")));
                        }
                        slots.add(new PatternVariant.Slot(allocation));
                    }
                    var variant=new PatternVariant(pattern,slots);
                    variant.recipe(); // Revalidate multiplier and template membership.
                    if(!flow(variant,encodeStack).equals(entry.getCompound("flow")))
                        throw new IllegalArgumentException("Pattern consumption or container returns changed");
                    word.add(new ProductionPlanner.Step<>(new PatternVariant(pattern,slots),entry.getLong("count")));
                }
                program.add(new ProductionPlanner.Block<>(word,data.getLong("repeat")));
            }
            var cursor=new ProductionCursor<>(program);
            cursor.restore(new ProductionCursor.Position(tag.getInt("block"),tag.getInt("step"),tag.getLong("round"),tag.getLong("completed")));
            return new ProductionJob(cursor,false);
        } catch(RuntimeException ex) {
            com.aecraftroute.addon.CraftRouteAddon.LOGGER.error("[AE-Craftroute] Production schedule cannot resume; cancel and resubmit. Inventory untouched.",ex);
            return new ProductionJob(new ProductionCursor<>(List.of()),true);
        }
    }
    private static CompoundTag flow(PatternVariant variant,java.util.function.Function<GenericStack,CompoundTag> encode) {
        var recipe=variant.recipe(); var tag=new CompoundTag(); var inputs=new ListTag(); var outputs=new ListTag();
        recipe.inputs().forEach((key,count)->inputs.add(encode.apply(new GenericStack(key,count))));
        recipe.outputs().forEach((key,count)->outputs.add(encode.apply(new GenericStack(key,count))));
        tag.put("inputs",inputs); tag.put("outputs",outputs); return tag;
    }

}
