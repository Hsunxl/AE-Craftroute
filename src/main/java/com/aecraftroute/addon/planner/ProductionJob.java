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

/** Shared execution protocol for native and AdvancedAE CPUs. All expected output
 * is real CPU inventory (including the target), so it can feed the next step.
 * Delivery is a separate phase, after dispatch AND every outstanding return.
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
    public ProductionJob(CertifiedCraftingPlan plan) { this(new ProductionCursor<>(plan.program()),false); }
    private ProductionJob(ProductionCursor<PatternVariant> cursor,boolean invalid) { this.cursor=cursor; this.invalid=invalid; }
    public boolean done() { return !invalid && cursor.done(); }
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
