package com.aecraftroute.addon.planner;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import com.aecraftroute.addon.bench.*;
import com.aecraftroute.addon.compat.ScaledPatternCompat;
import net.minecraft.nbt.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.aecraftroute.addon.bench.BenchPatternDetails.InputSpec.of;

class ScaledPatternCompatibilityTest {
    final BenchAEKey target = BenchAEKey.of("scaled_target"), fuel = BenchAEKey.of("scaled_fuel");
    IPatternDetails base() { return new BenchPatternDetails(target,1,List.of(of(fuel,1))); }
    CompoundTag leaf(IPatternDetails pattern) {
        assertFalse(pattern instanceof ScaledBenchPatternDetails,"Only the unscaled encoded definition is stored");
        var tag = new CompoundTag(); tag.putString("id","base"); return tag;
    }
    CompoundTag stack(GenericStack value) {
        var tag = new CompoundTag(); tag.putString("id",((BenchAEKey)value.what()).itemId()); tag.putLong("amount",value.amount()); return tag;
    }
    GenericStack stack(CompoundTag tag) { return new GenericStack(BenchAEKey.of(tag.getString("id")),tag.getLong("amount")); }
    CertifiedCraftingPlan plan(IPatternDetails pattern,long amount) {
        return NetworkProductionPlanner.plan(target,amount,k->k.equals(target)?List.of(pattern):List.of(),k->k.equals(fuel)?100L:0L);
    }

    @Test void nonMultipleRequestRetainsOuterIdentityAndCountsPushesNotInnerOperations() {
        var original = base(); var scaled = new ScaledBenchPatternDetails(original,4);
        var plan = plan(scaled,5);
        assertEquals(Map.of(scaled,2L),plan.patternTimes());
        assertSame(scaled,plan.patternTimes().keySet().iterator().next());
        assertEquals(8,plan.usedItems().get(fuel));
        var job = new ProductionJob(plan);
        assertFalse(job.allows(original)); assertTrue(job.allows(scaled));
        job.accepted(scaled); assertFalse(job.done()); job.accepted(scaled); assertTrue(job.done());
    }
    @Test void nestedVirtualWrappersAndPartialCursorResumeWithSameOuterDispatchContract() {
        var original = base();
        var scaled = new ScaledBenchPatternDetails(new ScaledBenchPatternDetails(original,2),3);
        var plan = plan(scaled,7); assertEquals(2L,plan.patternTimes().get(scaled));
        var job = new ProductionJob(plan); job.accepted(scaled);
        var encoded = job.save(this::leaf,this::stack);
        var restored = ProductionJob.load(encoded,t->base(),this::stack,this::stack);
        var fresh = new ScaledBenchPatternDetails(new ScaledBenchPatternDetails(base(),2),3);
        assertTrue(restored.validateRemainingTasks(Map.of(fresh,1L)));
        assertFalse(restored.allows(original)); assertTrue(restored.allows(fresh));
        restored.accepted(fresh); assertTrue(restored.done());
    }
    @Test void ordinaryPatternTagsStayBackwardCompatible() {
        var original = base(); var tag = ScaledPatternCompat.encode(original,this::leaf);
        assertEquals(Set.of("id"),tag.getAllKeys());
        assertSame(original,ScaledPatternCompat.decode(tag,t->original,null));
    }
    @Test void corruptedMultiplierMissingWrapperOrChangedOutputFailClosed() {
        var scaled = new ScaledBenchPatternDetails(base(),4);
        var tag = ScaledPatternCompat.encode(scaled,this::leaf);
        tag.getList("aecraftrouteScaledWrappers",Tag.TAG_COMPOUND).getCompound(0).putLong("operations",0);
        assertThrows(IllegalArgumentException.class,()->ScaledPatternCompat.decode(tag,t->base(),null));
        var missing = ScaledPatternCompat.encode(scaled,this::leaf);
        missing.getList("aecraftrouteScaledWrappers",Tag.TAG_COMPOUND).getCompound(0).putString("class","missing.ScaledPattern");
        assertThrows(IllegalArgumentException.class,()->ScaledPatternCompat.decode(missing,t->base(),null));
        var saved = new ProductionJob(plan(scaled,5)).save(this::leaf,this::stack);
        var changed = ProductionJob.load(saved,t->new BenchPatternDetails(target,2,List.of(of(fuel,1))),this::stack,this::stack);
        assertFalse(changed.done()); assertFalse(changed.allows(scaled));
    }
    @Test void cyclesExcessiveDepthAndCumulativeOverflowAreRejected() {
        var cycle = new ScaledContractPattern(null,1); cycle.original=cycle;
        assertThrows(IllegalArgumentException.class,()->ScaledPatternCompat.encode(cycle,this::leaf));
        IPatternDetails deep=base(); for(int n=0;n<17;n++) deep=new ScaledContractPattern(deep,1);
        var deepest=deep;
        assertThrows(IllegalArgumentException.class,()->ScaledPatternCompat.encode(deepest,this::leaf));
        var overflow=new ScaledContractPattern(new ScaledContractPattern(base(),Long.MAX_VALUE),2);
        assertThrows(ArithmeticException.class,()->ScaledPatternCompat.encode(overflow,this::leaf));
    }
    @Test void levelAwareConstructorIsPreferredAndStoredOverflowFailsBeforeInstantiation() {
        var pattern = new ScaledLevelPattern(base(),3);
        var encoded = ScaledPatternCompat.encode(pattern,this::leaf);
        var restored = (ScaledLevelPattern)ScaledPatternCompat.decode(encoded,t->base(),null);
        assertTrue(restored.levelAware,"Even a null-level offline test must choose the Level overload");
        var nested = ScaledPatternCompat.encode(new ScaledBenchPatternDetails(new ScaledBenchPatternDetails(base(),2),3),this::leaf);
        var layers = nested.getList("aecraftrouteScaledWrappers",Tag.TAG_COMPOUND);
        layers.getCompound(0).putLong("operations",Long.MAX_VALUE);
        assertThrows(ArithmeticException.class,()->ScaledPatternCompat.decode(nested,t->base(),null));
    }
    /** Getter contract fixture only; malformed graphs must fail before any recipe access. */
    public static class ScaledContractPattern implements IPatternDetails {
        IPatternDetails original; final long count;
        public ScaledContractPattern(IPatternDetails original,long count) { this.original=original; this.count=count; }
        public IPatternDetails getOriginal() { return original; }
        public long getOperationsPerPush() { return count; }
        public AEItemKey getDefinition() { throw new AssertionError(); }
        public IInput[] getInputs() { throw new AssertionError(); }
        public List<GenericStack> getOutputs() { throw new AssertionError(); }
    }
    public static class ScaledLevelPattern extends ScaledContractPattern {
        final boolean levelAware;
        public ScaledLevelPattern(IPatternDetails original,long count) { super(original,count); levelAware=false; }
        public ScaledLevelPattern(IPatternDetails original,long count,Level level) { super(original,count); levelAware=true; }
    }
}
