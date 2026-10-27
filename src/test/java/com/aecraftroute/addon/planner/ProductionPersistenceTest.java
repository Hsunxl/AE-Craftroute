package com.aecraftroute.addon.planner;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import com.aecraftroute.addon.bench.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.aecraftroute.addon.bench.BenchPatternDetails.InputSpec.*;

/** Actual program/NBT serializer, with only registry-dependent leaf codecs injected.
 * Does not claim Minecraft registry decoding or a real world restart was tested. */
class ProductionPersistenceTest {
    final BenchAEKey target=BenchAEKey.of("persist_target"), tool=BenchAEKey.of("persist_tool"), fuel=BenchAEKey.of("persist_fuel");
    IPatternDetails pattern(boolean returns) {
        return new BenchPatternDetails(target,1,List.of(returns?returned(tool,1):of(tool,1),of(fuel,1)));
    }
    ProductionJob job(IPatternDetails p) {
        return new ProductionJob(NetworkProductionPlanner.plan(target,20,k->k.equals(target)?List.of(p):List.of(),
                k->k.equals(tool)?1L:k.equals(fuel)?20L:0L));
    }
    CompoundTag encode(GenericStack stack) {
        var tag=new CompoundTag(); tag.putString("key",((BenchAEKey)stack.what()).itemId()); tag.putLong("amount",stack.amount()); return tag;
    }
    GenericStack decode(CompoundTag tag) { return new GenericStack(BenchAEKey.of(tag.getString("key")),tag.getLong("amount")); }
    CompoundTag save(ProductionJob job) {
        return job.save(p->{var tag=new CompoundTag();tag.putString("id","pattern");return tag;},this::encode);
    }
    ProductionJob load(CompoundTag tag,IPatternDetails p) { return ProductionJob.load(tag,t->p,this::decode,this::encode); }
    CompoundTag binary(CompoundTag tag) throws IOException {
        var buffer=new ByteArrayOutputStream(); NbtIo.write(tag,new DataOutputStream(buffer));
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())),NbtAccounter.unlimitedHeap());
    }
    @Test void binaryNbtRoundTripAfterEveryAcceptedDispatchAndAtCompletion() throws Exception {
        var p=pattern(true); var job=job(p);
        for(int dispatched=0;dispatched<20;dispatched++) {
            job=load(binary(save(job)),p); assertFalse(job.done()); assertTrue(job.allows(p)); job.accepted(p);
        }
        job=load(binary(save(job)),p); assertTrue(job.done()); assertFalse(job.allows(p));
    }
    @Test void steadyStateWordLongerThanSixteenStepsSurvivesRestart() throws Exception {
        var p=pattern(true);
        var variant=ProductionExecutionTest.variant(p,new GenericStack(tool,1),new GenericStack(fuel,1));
        var word=new ArrayList<ProductionPlanner.Step<PatternVariant>>();
        for(int i=0;i<17;i++) word.add(new ProductionPlanner.Step<>(variant,1));
        var program=List.of(new ProductionPlanner.Block<>(word,1));
        var used=new KeyCounter();used.add(tool,1);used.add(fuel,17);
        var plan=new CertifiedCraftingPlan(new GenericStack(target,17),256,used,Map.of(p,17L),program);
        var restored=load(binary(save(new ProductionJob(plan))),p);
        assertTrue(restored.validateRemainingTasks(Map.of(p,17L)));
        for(int i=0;i<17;i++) {assertTrue(restored.allows(p));restored.accepted(p);}
        assertTrue(restored.done());
    }
    @Test void outputDisplayOrderChangeDoesNotCancelAnOtherwiseIdenticalProgram() {
        var a=BenchAEKey.of("persist_byproduct_a");
        var b=BenchAEKey.of("persist_byproduct_b");
        var inputs=List.of(returned(tool,1),of(fuel,1));
        var original=new BenchPatternDetails(target,1,inputs,List.of(
                BenchPatternDetails.OutputSpec.of(a,1),BenchPatternDetails.OutputSpec.of(b,2)));
        var reordered=new BenchPatternDetails(target,1,inputs,List.of(
                BenchPatternDetails.OutputSpec.of(b,2),BenchPatternDetails.OutputSpec.of(a,1)));
        var restored=load(save(job(original)),reordered);
        assertTrue(restored.allows(reordered));
        assertTrue(restored.validateRemainingTasks(Map.of(reordered,20L)));
    }
    @Test void aggregateStepLimitRejectsHugeSavedAndRestoredProgramsBeforeDecoding() {
        var p=pattern(true);
        var variant=ProductionExecutionTest.variant(p,new GenericStack(tool,1),new GenericStack(fuel,1));
        var step=new ProductionPlanner.Step<>(variant,1);
        var word=Collections.nCopies(8192,step);
        var block=new ProductionPlanner.Block<>(word,1);
        var program=Collections.nCopies(15,block); // 122,880 steps, each word individually legal.
        var plan=new CertifiedCraftingPlan(new GenericStack(target,1),256,new KeyCounter(),Map.of(p,122_880L),program);
        assertThrows(IllegalArgumentException.class,()->save(new ProductionJob(plan)));

        var stored=save(job(p));
        var first=stored.getList("blocks",Tag.TAG_COMPOUND).getCompound(0);
        var encodedStep=first.getList("steps",Tag.TAG_COMPOUND).getCompound(0);
        var manySteps=new ListTag();
        for(int i=0;i<8192;i++)manySteps.add(encodedStep);
        var manyBlocks=new ListTag();
        for(int i=0;i<15;i++) {
            var encodedBlock=new CompoundTag();encodedBlock.putLong("repeat",1);encodedBlock.put("steps",manySteps);
            manyBlocks.add(encodedBlock);
        }
        stored.put("blocks",manyBlocks);
        var loaded=load(stored,p);
        assertFalse(loaded.allows(p));
    }
    @Test void reboundCachedProofRestoresWithoutTheInMemoryCache() throws Exception {
        NetworkProductionPlanner.clearCache();
        job(pattern(true));
        var current=pattern(true);
        var cached=job(current);
        assertEquals(1,NetworkProductionPlanner.cacheStats().hits());
        cached.accepted(current);
        var persisted=binary(save(cached));
        NetworkProductionPlanner.clearCache();
        var decoded=pattern(true);
        var restored=load(persisted,decoded);
        assertTrue(restored.validateRemainingTasks(Map.of(decoded,19L)));
        for(int i=0;i<19;i++) { assertTrue(restored.allows(decoded)); restored.accepted(decoded); }
        assertTrue(restored.done());
        assertEquals(0,NetworkProductionPlanner.cacheStats().entries());
    }
    @Test void changedContainerSemanticsRefuseResumeEvenWhenDeclaredOutputsMatch() throws Exception {
        var job=job(pattern(true)); var changed=pattern(false);
        var loaded=load(binary(save(job)),changed);
        assertFalse(loaded.done()); assertFalse(loaded.allows(changed));
        assertThrows(IllegalStateException.class,()->loaded.accepted(changed));
    }
    @Test void changingInfiniteCatalystToFiniteToolRefusesResume() {
        var changed=new BenchPatternDetails(target,1,List.of(finiteUse(tool,1,2),of(fuel,1)));
        var restored=load(save(job(pattern(true))),changed);
        assertFalse(restored.done()); assertFalse(restored.allows(changed));
    }
    @Test void restoredCursorMustMatchNativeTaskCounts() throws Exception {
        var p=pattern(true); var job=job(p); job.accepted(p);
        var restored=load(binary(save(job)),p);
        assertTrue(restored.validateRemainingTasks(Map.of(p,19L)));
        assertTrue(restored.allows(p));
        assertFalse(restored.validateRemainingTasks(Map.of(p,20L)));
        assertFalse(restored.allows(p)); assertFalse(restored.done());
        assertFalse(restored.validateRemainingTasks(Map.of(p,19L))); // Cannot silently re-enable after a mismatch.
    }
    @Test void completedCursorCannotHideUndispatchedNativeTasks() {
        var p=pattern(true); var job=job(p);
        for(int n=0;n<20;n++) job.accepted(p);
        assertTrue(job.done()); assertTrue(job.validateRemainingTasks(Map.of(p,0L)));
        assertFalse(job.validateRemainingTasks(Map.of(p,1L))); assertFalse(job.done());
    }
    @Test void missingOrNegativeNativeTasksCannotResume() {
        var p=pattern(true);
        assertFalse(job(p).validateRemainingTasks(Map.of()));
        assertFalse(job(p).validateRemainingTasks(Map.of(p,-1L)));
    }
    @Test void corruptPositionAndAllocationNeverResumeOrBecomeCompleted() {
        var p=pattern(true); var original=save(job(p));
        var position=original.copy();position.putLong("completed",Long.MAX_VALUE);
        assertFalse(load(position,p).allows(p)); assertFalse(load(position,p).done());
        var allocation=original.copy();
        allocation.getList("blocks",Tag.TAG_COMPOUND).getCompound(0).getList("steps",Tag.TAG_COMPOUND).getCompound(0)
                .getList("slots",Tag.TAG_COMPOUND).getCompound(0).getList("portions",Tag.TAG_COMPOUND).getCompound(0).putLong("allocation",2);
        assertFalse(load(allocation,p).allows(p));
    }
}
