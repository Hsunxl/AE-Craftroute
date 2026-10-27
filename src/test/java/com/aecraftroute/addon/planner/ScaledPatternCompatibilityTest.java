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
    @Test void strictWrapperMetadataMustNotInventMissingFlagsOrMultiplier() {
        var tag=ScaledPatternCompat.encode(new ScaledBenchPatternDetails(base(),1),this::leaf);
        var layer=tag.getList("aecraftrouteScaledWrappers",Tag.TAG_COMPOUND).getCompound(0);
        layer.putString("class","com.extendedae_plus.util.crafting.StrictMolecularAssemblerPattern");
        layer.putBoolean("strict",true);
        assertThrows(IllegalArgumentException.class,()->ScaledPatternCompat.decode(tag,t->base(),null));
        layer.putBoolean("allowScaling",true);
        assertThrows(IllegalArgumentException.class,()->ScaledPatternCompat.decode(tag,t->base(),null));
        layer.putInt("multiplierLimit",17);layer.putLong("operations",2);
        assertThrows(IllegalArgumentException.class,()->ScaledPatternCompat.decode(tag,t->base(),null));
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
    @Test void absentLevelUsesTwoArgumentConstructorAndStoredOverflowFailsBeforeInstantiation() {
        var pattern = new ScaledLevelPattern(base(),3);
        var encoded = ScaledPatternCompat.encode(pattern,this::leaf);
        var restored = (ScaledLevelPattern)ScaledPatternCompat.decode(encoded,t->base(),null);
        assertFalse(restored.levelAware,"No null Level may enter the level-aware constructor");
        var onlyLevel = ScaledPatternCompat.encode(new ScaledOnlyLevelPattern(base(),3,null),this::leaf);
        assertThrows(IllegalArgumentException.class,()->ScaledPatternCompat.decode(onlyLevel,t->base(),null));
        var nested = ScaledPatternCompat.encode(new ScaledBenchPatternDetails(new ScaledBenchPatternDetails(base(),2),3),this::leaf);
        var layers = nested.getList("aecraftrouteScaledWrappers",Tag.TAG_COMPOUND);
        layers.getCompound(0).putLong("operations",Long.MAX_VALUE);
        assertThrows(ArithmeticException.class,()->ScaledPatternCompat.decode(nested,t->base(),null));
    }
    @Test void integralNumberGetterDoesNotBreakAnAddonsOwnScaledPattern() {
        var tag=ScaledPatternCompat.encode(new ScaledIntPattern(base(),3),this::leaf);
        var layers=tag.getList("aecraftrouteScaledWrappers",Tag.TAG_COMPOUND);
        assertEquals(3L,layers.getCompound(0).getLong("operations"));
        assertInstanceOf(ScaledIntPattern.class,ScaledPatternCompat.decode(tag,t->base(),null));
    }
    @Test void publicMultiplierGetterRoundTrips() {
        IPatternDetails wrapper=new ScaledGetterPattern(base(),3);
        var tag=ScaledPatternCompat.encode(wrapper,this::leaf);
        assertEquals(3L,tag.getList("aecraftrouteScaledWrappers",Tag.TAG_COMPOUND)
                .getCompound(0).getLong("operations"));
        assertEquals(wrapper.getClass(),ScaledPatternCompat.decode(tag,t->base(),null).getClass());
    }
    @Test void unrelatedPrivateFieldDoesNotOptAnAddonIntoScaledExecution() {
        assertThrows(IllegalArgumentException.class,()->ScaledPatternCompat.encode(
                new ScaledInheritedFieldPattern(base(),4),this::leaf));
    }
    @Test void installedExtendedAePlusProcessingWrapperRoundTrips() throws Exception {
        var type=Class.forName("com.extendedae_plus.api.crafting.ScaledProcessingPattern");
        var wrapper=(IPatternDetails)type.getConstructor(IPatternDetails.class,long.class)
                .newInstance(base(),3L);
        var tag=ScaledPatternCompat.encode(wrapper,this::leaf);
        assertEquals(3L,tag.getList("aecraftrouteScaledWrappers",Tag.TAG_COMPOUND)
                .getCompound(0).getLong("operations"));
        assertEquals(type,ScaledPatternCompat.decode(tag,t->base(),null).getClass());
    }
    @Test void installedExtendedAePlusMolecularWrapperRoundTrips() throws Exception {
        var contract=appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern.class;
        var original=(IPatternDetails)java.lang.reflect.Proxy.newProxyInstance(
                contract.getClassLoader(),new Class<?>[]{contract},(proxy,method,args)->{
                    if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                    if(method.getName().equals("equals"))return proxy==args[0];
                    return null;
                });
        var type=Class.forName("com.extendedae_plus.api.crafting.ScaledMolecularAssemblerPattern");
        var wrapper=(IPatternDetails)type.getConstructor(contract,long.class).newInstance(original,5L);
        var tag=ScaledPatternCompat.encode(wrapper,p->{assertSame(original,p);return new CompoundTag();});
        assertEquals(5L,tag.getList("aecraftrouteScaledWrappers",Tag.TAG_COMPOUND)
                .getCompound(0).getLong("operations"));
        var restored=ScaledPatternCompat.decode(tag,t->original,null);
        assertEquals(type,restored.getClass());
        assertSame(original,type.getMethod("getOriginal").invoke(restored));
    }
    @Test void missingOptionalDependencyDuringWrapperRestoreFailsClosed() {
        var tag=ScaledPatternCompat.encode(new ScaledLinkagePattern(base(),2,true),this::leaf);
        var failure=assertThrows(IllegalArgumentException.class,()->ScaledPatternCompat.decode(tag,t->base(),null));
        assertInstanceOf(NoClassDefFoundError.class,failure.getCause());
        var saved=new ProductionJob(plan(new ScaledBenchPatternDetails(base(),2),2)).save(this::leaf,this::stack);
        var entry=saved.getList("blocks",Tag.TAG_COMPOUND).getCompound(0)
                .getList("steps",Tag.TAG_COMPOUND).getCompound(0).getCompound("pattern");
        entry.getList("aecraftrouteScaledWrappers",Tag.TAG_COMPOUND).getCompound(0)
                .putString("class",ScaledLinkagePattern.class.getName());
        var restored=ProductionJob.load(saved,t->base(),this::stack,this::stack);
        assertTrue(restored.invalid());
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
    public static class ScaledOnlyLevelPattern extends ScaledContractPattern {
        public ScaledOnlyLevelPattern(IPatternDetails original,long count,Level level) {super(original,count);}
    }
    public static class ScaledLinkagePattern extends ScaledContractPattern {
        public ScaledLinkagePattern(IPatternDetails original,long count) {
            super(original,count);
            throw new NoClassDefFoundError("removed optional addon");
        }
        public ScaledLinkagePattern(IPatternDetails original,long count,boolean fixture) {super(original,count);}
    }
    public static class ScaledIntPattern implements IPatternDetails {
        final IPatternDetails original; final int count;
        public ScaledIntPattern(IPatternDetails original,long count) {this.original=original;this.count=Math.toIntExact(count);}
        public IPatternDetails getOriginal() {return original;}
        public int getOperationsPerPush() {return count;}
        public AEItemKey getDefinition() {throw new AssertionError();}
        public IInput[] getInputs() {return original.getInputs();}
        public List<GenericStack> getOutputs() {return original.getOutputs();}
    }
    public static class ScaledGetterPattern implements IPatternDetails {
        private final IPatternDetails original;
        private final long count;
        public ScaledGetterPattern(IPatternDetails original,long count) {this.original=original;this.count=count;}
        public IPatternDetails getOriginal() {return original;}
        public long getMultiplier() {return count;}
        public AEItemKey getDefinition() {return original.getDefinition();}
        public IInput[] getInputs() {return original.getInputs();}
        public List<GenericStack> getOutputs() {return original.getOutputs();}
    }
    public static class ScaledFieldBasePattern implements IPatternDetails {
        protected final long multiplier;
        private final IPatternDetails original;
        public ScaledFieldBasePattern(IPatternDetails original,long multiplier) {
            this.original=original;this.multiplier=multiplier;
        }
        public IPatternDetails getOriginal() {return original;}
        public AEItemKey getDefinition() {return original.getDefinition();}
        public IInput[] getInputs() {return original.getInputs();}
        public List<GenericStack> getOutputs() {return original.getOutputs();}
    }
    public static class ScaledInheritedFieldPattern extends ScaledFieldBasePattern {
        public ScaledInheritedFieldPattern(IPatternDetails original,long multiplier) {super(original,multiplier);}
    }
}
