package com.aecraftroute.addon.planner;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import appeng.crafting.inv.ListCraftingInventory;
import com.aecraftroute.addon.bench.*;
import com.aecraftroute.addon.compiler.IFiniteUseInput;
import net.minecraft.nbt.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FiniteToolAndFuzzyTest {
    final BenchAEKey target=BenchAEKey.of("damage_target"), fuel=BenchAEKey.of("damage_fuel");
    final BenchAEKey full=BenchAEKey.of("tool_damage_0"), worn=BenchAEKey.of("tool_damage_1"), last=BenchAEKey.of("tool_damage_2");
    final class ToolInput implements IPatternDetails.IInput,IFiniteUseInput {
        final Map<AEKey,Long> ranks;
        final Map<AEKey,AEKey> returns;
        ToolInput(Map<AEKey,Long> ranks,Map<AEKey,AEKey> returns) { this.ranks=ranks; this.returns=returns; }
        public long durabilityUses() { return 3; }
        public long remainingUses(AEKey key) { return ranks.getOrDefault(key,-1L); }
        public GenericStack[] getPossibleInputs() { return new GenericStack[]{new GenericStack(full,1),new GenericStack(worn,1),new GenericStack(last,1)}; }
        public long getMultiplier() { return 1; }
        public boolean isValid(AEKey key,Level level) { return ranks.containsKey(key); }
        public AEKey getRemainingKey(AEKey key) { return returns.get(key); }
    }
    ToolInput tool() { return new ToolInput(Map.of(full,3L,worn,2L,last,1L),Map.of(full,worn,worn,last)); }
    IPatternDetails pattern(ToolInput tool,int slots) {
        return new IPatternDetails() {
            public AEItemKey getDefinition() { return null; }
            public IInput[] getInputs() { var inputs=new IInput[slots]; Arrays.fill(inputs,tool); return inputs; }
            public List<GenericStack> getOutputs() { return List.of(new GenericStack(target,1)); }
        };
    }
    CertifiedCraftingPlan plan(IPatternDetails pattern,Map<AEKey,Long> stock,long amount) {
        return NetworkProductionPlanner.plan(target,amount,k->k.equals(target)?List.of(pattern):List.of(),k->stock.getOrDefault(k,0L));
    }
    CompoundTag encode(GenericStack stack) {
        var tag=new CompoundTag(); tag.putString("key",((BenchAEKey)stack.what()).itemId()); tag.putLong("amount",stack.amount()); return tag;
    }
    GenericStack decode(CompoundTag tag) { return new GenericStack(BenchAEKey.of(tag.getString("key")),tag.getLong("amount")); }
    ProductionJob roundtrip(ProductionJob job,IPatternDetails p) throws Exception {
        var tag=job.save(pattern->new CompoundTag(),this::encode);
        var bytes=new ByteArrayOutputStream(); NbtIo.write(tag,new DataOutputStream(bytes));
        var restored=NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),NbtAccounter.unlimitedHeap());
        return ProductionJob.load(restored,t->p,this::decode,this::encode);
    }
    @Test void exactDamageStatesBreakAndResumeWithDelayedPhysicalReturns() throws Exception {
        var p=pattern(tool(),1); var result=plan(p,Map.of(full,2L),6);
        assertEquals(2,result.usedItems().get(full));
        var inventory=new ListCraftingInventory(k->{}); inventory.insert(full,2,Actionable.MODULATE);
        var job=new ProductionJob(result);
        for(int n=0;n<6;n++) {
            job=roundtrip(job,p); var outputs=new KeyCounter(); var containers=new KeyCounter();
            assertNotNull(job.extract(p,inventory,null,outputs,containers)); job.accepted(p);
            job=roundtrip(job,p);
            // Nothing is credited before the physical provider return arrives.
            for(var entry:outputs) inventory.insert(entry.getKey(),entry.getLongValue(),Actionable.MODULATE);
            for(var entry:containers) inventory.insert(entry.getKey(),entry.getLongValue(),Actionable.MODULATE);
        }
        assertTrue(roundtrip(job,p).done());
        assertEquals(6,inventory.extract(target,100,Actionable.SIMULATE));
        assertEquals(0,inventory.extract(full,100,Actionable.SIMULATE));
        assertEquals(0,inventory.extract(worn,100,Actionable.SIMULATE));
        assertEquals(0,inventory.extract(last,100,Actionable.SIMULATE));
    }
    @Test void damagedInitialToolsAndMultipleToolSlotsUseActualFiniteCapital() {
        var p=pattern(tool(),2); var result=plan(p,Map.of(full,1L,worn,1L,last,1L),3);
        assertEquals(1,result.usedItems().get(full)); assertEquals(1,result.usedItems().get(worn)); assertEquals(1,result.usedItems().get(last));
        assertEquals(ProductionPlanningException.Reason.UNREACHABLE,
                assertThrows(ProductionPlanningException.class,()->plan(p,Map.of(full,1L,worn,1L,last,1L),4)).reason());
    }
    @Test void sameKeyPseudoRemainderAndLastUseReturnAreRejected() {
        var same=new ToolInput(Map.of(full,3L,worn,2L,last,1L),Map.of(full,full,worn,last));
        var immortalLast=new ToolInput(Map.of(full,3L,worn,2L,last,1L),Map.of(full,worn,worn,last,last,last));
        for(var input:List.of(same,immortalLast)) {
            assertFalse(PatternVariants.enumerate(pattern(input,1),k->100L).complete());
            assertEquals(ProductionPlanningException.Reason.UNSUPPORTED,
                    assertThrows(ProductionPlanningException.class,()->plan(pattern(input,1),Map.of(full,10L),1)).reason());
        }
    }
    @Test void changedLifetimeRankInvalidatesPersistedSchedule() throws Exception {
        var p=pattern(tool(),1); var job=new ProductionJob(plan(p,Map.of(full,1L),3));
        var changed=pattern(new ToolInput(Map.of(full,2L,worn,2L,last,1L),Map.of(full,worn,worn,last)),1);
        var restored=roundtrip(job,changed);
        assertFalse(restored.allows(changed)); assertFalse(restored.done());
    }
    @Test void exhaustedToolCannotProduceOneExtraItem() {
        var p=pattern(tool(),1);
        assertEquals(ProductionPlanningException.Reason.UNREACHABLE,
                assertThrows(ProductionPlanningException.class,()->plan(p,Map.of(full,1L),4)).reason());
    }
    @Test void largeFuzzySlotAggregatesStockWithoutEnumeratingCompositions() {
        var keys=new ArrayList<BenchAEKey>(); var stock=new LinkedHashMap<AEKey,Long>();
        for(int n=0;n<80;n++) { var key=BenchAEKey.of("fuzzy_large_"+n); keys.add(key); stock.put(key,1L); }
        var input=new BenchPatternDetails.InputSpec(keys.getFirst(),1,80,keys.subList(1,keys.size()));
        var p=new BenchPatternDetails(target,1,List.of(input));
        var variants=PatternVariants.enumerate(p,k->stock.getOrDefault(k,0L));
        assertFalse(variants.complete()); assertTrue(variants.recipes().size()<100);
        var result=plan(p,stock,1); assertEquals(80,result.usedItems().size());
        var inventory=new ListCraftingInventory(k->{}); stock.forEach((k,v)->inventory.insert(k,v,Actionable.MODULATE));
        assertNotNull(new ProductionJob(result).extract(p,inventory,null,new KeyCounter(),new KeyCounter()));
    }
    @Test void manyFuzzySlotsReserveSharedStockWithoutCartesianExplosion() {
        var keys=new ArrayList<BenchAEKey>(); var stock=new LinkedHashMap<AEKey,Long>();
        for(int n=0;n<20;n++) { var key=BenchAEKey.of("fuzzy_slots_"+n); keys.add(key); stock.put(key,1L); }
        var input=new BenchPatternDetails.InputSpec(keys.getFirst(),1,1,keys.subList(1,keys.size()));
        var p=new BenchPatternDetails(target,1,Collections.nCopies(20,input));
        var variants=PatternVariants.enumerate(p,k->stock.getOrDefault(k,0L));
        assertFalse(variants.complete()); assertTrue(variants.recipes().size()<=33);
        var result=plan(p,stock,1); assertEquals(20,result.usedItems().size());
    }
    @Test void boundedFuzzyFailureDoesNotClaimMissingIngredients() {
        var keys=new ArrayList<BenchAEKey>(); for(int n=0;n<30;n++) keys.add(BenchAEKey.of("fuzzy_unknown_"+n));
        var p=new BenchPatternDetails(target,1,List.of(new BenchPatternDetails.InputSpec(keys.getFirst(),1,100,keys.subList(1,keys.size()))));
        assertEquals(ProductionPlanningException.Reason.UNSUPPORTED,
                assertThrows(ProductionPlanningException.class,()->plan(p,Map.of(),1)).reason());
    }
    @Test void largeFuzzyMixedCandidateCanUseVariantsProducedUpstream() {
        var keys=new ArrayList<BenchAEKey>(); for(int n=0;n<24;n++) keys.add(BenchAEKey.of("fuzzy_produced_"+n));
        var p=new BenchPatternDetails(target,1,List.of(new BenchPatternDetails.InputSpec(keys.getFirst(),1,24,keys.subList(1,keys.size()))));
        var upstream=new BenchPatternDetails(keys.getFirst(),1,List.of(BenchPatternDetails.InputSpec.of(fuel,1)),
                keys.subList(1,keys.size()).stream().map(k->new BenchPatternDetails.OutputSpec(k,1)).toList());
        List<IPatternDetails> patterns=List.of(p,upstream);
        var result=NetworkProductionPlanner.plan(target,1,
                k->patterns.stream().filter(candidate->candidate.getOutputs().stream().anyMatch(out->out.what().equals(k))).toList(),
                k->k.equals(fuel)?1L:0L);
        assertEquals(1,result.usedItems().get(fuel)); assertEquals(2,result.patternTimes().size());
    }
    @Test void expiredEnumerationDeadlineIsLimitNotMissingStock() {
        var error=assertThrows(ProductionPlanningException.class,
                ()->PatternVariants.enumerate(pattern(tool(),1),k->100L,System.nanoTime()-1));
        assertEquals(ProductionPlanningException.Reason.LIMIT,error.reason());
    }
    @Test void familyFlowDoesNotSpendScarceKeyOnFlexibleSlot() {
        var x=BenchAEKey.of("family_scarce_x"); var y=BenchAEKey.of("family_flexible_y");
        var p=new BenchPatternDetails(target,1,List.of(
                new BenchPatternDetails.InputSpec(x,1,1,List.of(y)),BenchPatternDetails.InputSpec.of(x,1)));
        var candidate=FuzzyFamilyAllocator.allocate(p,k->1L,Long.MAX_VALUE).orElseThrow();
        assertEquals(y,candidate.slots().get(0).portions().getFirst().template().what());
        assertEquals(x,candidate.slots().get(1).portions().getFirst().template().what());
        assertEquals(Map.of(x,1L,y,1L),candidate.recipe().inputs());
    }
    @Test void eightyVariantsAndTwentyDistinctFamiliesMatchByResidualRerouting() {
        var keys=new ArrayList<BenchAEKey>(); for(int n=0;n<80;n++) keys.add(BenchAEKey.of("family_eighty_"+n));
        var specs=new ArrayList<BenchPatternDetails.InputSpec>();
        // First, the broad family appears to own every scarce key. Nineteen
        // narrower families force residual augmenting paths to reassign it.
        specs.add(new BenchPatternDetails.InputSpec(keys.getFirst(),1,61,keys.subList(1,keys.size())));
        for(int n=0;n<19;n++) specs.add(BenchPatternDetails.InputSpec.of(keys.get(n),1));
        var p=new BenchPatternDetails(target,1,specs);
        var candidate=FuzzyFamilyAllocator.allocate(p,k->1L,Long.MAX_VALUE).orElseThrow();
        var recipe=candidate.recipe(); assertEquals(80,recipe.inputs().size());
        assertTrue(recipe.inputs().values().stream().allMatch(amount->amount==1));
        assertEquals(61,candidate.slots().getFirst().portions().size());
        for(int n=0;n<19;n++) assertEquals(keys.get(n),candidate.slots().get(n+1).portions().getFirst().template().what());
        var variants=PatternVariants.enumerate(p,k->1L);
        assertFalse(variants.complete());
        assertTrue(variants.recipes().stream().anyMatch(r->r.inputs().equals(recipe.inputs())));
        var inventory=new ListCraftingInventory(k->{}); keys.forEach(k->inventory.insert(k,1,Actionable.MODULATE));
        var result=plan(p,keys.stream().collect(java.util.stream.Collectors.toMap(k->(AEKey)k,k->1L)),1);
        assertNotNull(new ProductionJob(result).extract(p,inventory,null,new KeyCounter(),new KeyCounter()));
    }
    @Test void familyFlowAggregatesEquivalentSlotsButCannotInventMissingStock() {
        var x=BenchAEKey.of("family_equal_x"); var y=BenchAEKey.of("family_equal_y");
        var broad=new BenchPatternDetails.InputSpec(x,1,2,List.of(y));
        var p=new BenchPatternDetails(target,1,Collections.nCopies(20,broad));
        var exact=FuzzyFamilyAllocator.allocate(p,k->20L,Long.MAX_VALUE).orElseThrow();
        assertEquals(20,exact.slots().size()); assertEquals(Map.of(x,20L,y,20L),exact.recipe().inputs());
        assertTrue(FuzzyFamilyAllocator.allocate(p,k->19L,Long.MAX_VALUE).isEmpty());
    }
    @Test void interruptedEnumerationCancelsRatherThanReturningMissingStock() {
        Thread.currentThread().interrupt();
        try { assertThrows(java.util.concurrent.CancellationException.class,()->PatternVariants.enumerate(pattern(tool(),1),k->1L)); }
        finally { Thread.interrupted(); }
    }
}
