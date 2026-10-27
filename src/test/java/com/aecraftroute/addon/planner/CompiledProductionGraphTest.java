package com.aecraftroute.addon.planner;

import appeng.api.config.Actionable;
import appeng.api.stacks.*;
import appeng.crafting.inv.ListCraftingInventory;
import com.aecraftroute.addon.bench.*;
import com.aecraftroute.addon.vm.NumericCraftingVM;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.aecraftroute.addon.bench.BenchPatternDetails.InputSpec.*;

class CompiledProductionGraphTest {
    @BeforeEach void reset(){NetworkProductionPlanner.clearPlanningCaches();}
    private CompiledProductionGraph compile(long consumed,long returned) {
        return CompiledProductionGraph.compile(2,new int[][]{{0}},new long[][]{{consumed}},
                returned==0?new int[][]{{1}}:new int[][]{{0,1}},
                returned==0?new long[][]{{1}}:new long[][]{{returned,1}},()->false);
    }
    @Test void equalRulesReuseBytecodeWithoutRetainingMutableSourceArrays() {
        int[][] keys={{0}};long[][] amounts={{2}};
        var graph=CompiledProductionGraph.compile(2,keys,amounts,new int[][]{{1}},new long[][]{{1}},()->false);
        keys[0][0]=1;amounts[0][0]=99;
        var hit=compile(2,0);assertSame(graph,hit);assertEquals(1,CompiledProductionGraph.stats().hits());
        var result=NumericCraftingVM.execute(hit.bytecode,0,1,new long[]{2,0},()->false);
        assertEquals(NumericCraftingVM.Status.EXECUTED,result.status());assertEquals(1,result.quantity(1));
        assertEquals(2,hit.inputCounts[0][0]);
    }
    @Test void quantityAndRemainderChangesCannotReuseCompilation() {
        var base=compile(1,0);assertNotSame(base,compile(2,0));assertNotSame(base,compile(1,1));
        assertEquals(3,CompiledProductionGraph.stats().misses());
    }
    @Test void warmCacheCannotBypassCompilerValidation() {
        compile(2,0);
        assertThrows(IllegalArgumentException.class,()->CompiledProductionGraph.compile(2,new int[][]{{0,0}},new long[][]{{1,2}},
                new int[][]{{1}},new long[][]{{1}},()->false));
        assertThrows(IllegalArgumentException.class,()->CompiledProductionGraph.compile(2,new int[][]{{0}},new long[][]{{2}},
                new int[][]{{1},{0}},new long[][]{{1},{1}},()->false));
        assertEquals(0,CompiledProductionGraph.stats().hits());
    }
    @Test void numericPositionsKeepRecipeOrderAndKeyCountInTheSignature() {
        var one=compile(1,0);
        var reversed=CompiledProductionGraph.compile(2,new int[][]{{1}},new long[][]{{1}},new int[][]{{0}},new long[][]{{1}},()->false);
        assertNotSame(one,reversed);
        var extraKey=CompiledProductionGraph.compile(3,new int[][]{{0}},new long[][]{{1}},new int[][]{{1}},new long[][]{{1}},()->false);
        assertNotSame(one,extraKey);
        var forward=CompiledProductionGraph.compile(3,new int[][]{{0},{1}},new long[][]{{1},{1}},new int[][]{{1},{2}},new long[][]{{1},{2}},()->false);
        var swap=CompiledProductionGraph.compile(3,new int[][]{{1},{0}},new long[][]{{1},{1}},new int[][]{{2},{1}},new long[][]{{2},{1}},()->false);
        assertNotSame(forward,swap);assertEquals(1,swap.inputKeys[0][0]);assertEquals(2,swap.outputKeys[0][0]);
    }
    @Test void expiredOrCanceledCompilationNeverBecomesAValidEntry() {
        assertNull(CompiledProductionGraph.compile(2,new int[][]{{0}},new long[][]{{1}},new int[][]{{1}},new long[][]{{1}},()->true));
        assertEquals(0,CompiledProductionGraph.stats().entries());
        var calls=new AtomicInteger();
        assertNull(CompiledProductionGraph.compile(2,new int[][]{{0}},new long[][]{{1}},new int[][]{{1}},new long[][]{{1}},()->calls.incrementAndGet()>4));
        assertEquals(0,CompiledProductionGraph.stats().entries());
        assertThrows(CancellationException.class,()->CompiledProductionGraph.compile(2,new int[][]{{0}},new long[][]{{1}},new int[][]{{1}},new long[][]{{1}},()->{throw new CancellationException();}));
        var graph=compile(1,0);assertNotNull(graph);
        assertNull(CompiledProductionGraph.compile(2,new int[][]{{0}},new long[][]{{1}},new int[][]{{1}},new long[][]{{1}},()->true));
    }
    @Test void cacheIsBoundedAndServerStopClearsIt() {
        for(int i=1;i<=80;i++)compile(i,0);
        assertTrue(CompiledProductionGraph.stats().entries()<=32);assertTrue(CompiledProductionGraph.stats().weight()<=262144);
        com.aecraftroute.addon.route.RoutePlanningService.stopped(null);
        assertEquals(0,CompiledProductionGraph.stats().entries());
    }
    @Test void concurrentRequestsShareOnlyImmutableNumericCode() throws Exception {
        var expected=compile(1,0);var executor=Executors.newFixedThreadPool(2);
        try {
            var tasks=new ArrayList<Callable<Long>>();
            for(int i=1;i<=40;i++){long amount=i;tasks.add(()->{
                var graph=compile(1,0);assertSame(expected,graph);
                return NumericCraftingVM.execute(graph.bytecode,0,amount,new long[]{amount,0},()->false).quantity(1);
            });}
            var results=executor.invokeAll(tasks);for(int i=0;i<results.size();i++)assertEquals(i+1L,results.get(i).get());
        } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));}
    }
    private CertifiedCraftingPlan plan(BenchPatternDetails pattern,BenchAEKey ore,BenchAEKey out,long stock,long amount) {
        return NetworkProductionPlanner.plan(out,amount,k->k.equals(out)?List.of(pattern):List.of(),k->k.equals(ore)?stock:0L);
    }
    @Test void newRequestsRebindFreshSlotsWhileOldExecutionRemainsUnchanged() {
        var ore=BenchAEKey.of("compile_ore");var out=BenchAEKey.of("compile_out");
        var old=new BenchPatternDetails(out,1,List.of(of(ore,2)));
        var original=plan(old,ore,out,20,5);var active=new ProductionJob(original);
        var fresh=new BenchPatternDetails(out,1,List.of(of(ore,1),of(ore,1)));
        var next=plan(fresh,ore,out,40,7);assertTrue(CompiledProductionGraph.stats().hits()>0);
        assertSame(old,original.program().getFirst().word().getFirst().recipe().pattern());
        assertSame(fresh,next.program().getFirst().word().getFirst().recipe().pattern());
        var firstInv=new ListCraftingInventory(k->{});firstInv.insert(ore,2,Actionable.MODULATE);
        assertEquals(1,active.extract(old,firstInv,null,new KeyCounter(),new KeyCounter()).length);active.accepted(old);
        var secondInv=new ListCraftingInventory(k->{});secondInv.insert(ore,2,Actionable.MODULATE);
        var newJob=new ProductionJob(next);var inputs=newJob.extract(fresh,secondInv,null,new KeyCounter(),new KeyCounter());
        assertNotNull(inputs);assertEquals(2,inputs.length);assertEquals(1,inputs[0].get(ore));assertEquals(1,inputs[1].get(ore));
        assertEquals(1,active.position().completed());assertEquals(0,newJob.position().completed());
        var changed=new BenchPatternDetails(out,1,List.of(of(ore,3)));
        var third=plan(changed,ore,out,60,8);assertEquals(24,third.usedItems().get(ore));
        assertEquals(10,original.usedItems().get(ore));assertEquals(1,active.position().completed());
    }
    @Test void isomorphicNumericFlowsMayReuseCompilationAcrossDifferentKeys() {
        var shared=compile(1,0);
        assertSame(shared,compile(1,0));
        var firstOre=BenchAEKey.of("compile_identity_ore_a");
        var firstOut=BenchAEKey.of("compile_identity_out_a");
        var secondOre=BenchAEKey.of("compile_identity_ore_b");
        var secondOut=BenchAEKey.of("compile_identity_out_b");
        var firstPattern=new BenchPatternDetails(firstOut,1,List.of(of(firstOre,1)));
        var secondPattern=new BenchPatternDetails(secondOut,1,List.of(of(secondOre,1)));
        var first=plan(firstPattern,firstOre,firstOut,5,3);
        var second=plan(secondPattern,secondOre,secondOut,5,3);
        assertEquals(3,first.patternTimes().get(firstPattern));
        assertEquals(3,second.patternTimes().get(secondPattern));
        assertEquals(0,second.usedItems().get(firstOre));
        assertEquals(3,second.usedItems().get(secondOre));
        assertSame(secondPattern,second.program().getFirst().word().getFirst().recipe().pattern());
    }
    @Test void cachedCompilationDoesNotCacheCatalystAvailabilityOrRemovedProducers() {
        var ore=BenchAEKey.of("compile_seed_ore");var out=BenchAEKey.of("compile_seed_out");var tool=BenchAEKey.of("compile_seed_tool");
        var pattern=new BenchPatternDetails(out,1,List.of(of(ore,1),returned(tool,1)));
        NetworkProductionPlanner.plan(out,5,k->k.equals(out)?List.of(pattern):List.of(),k->k.equals(ore)?10L:k.equals(tool)?1L:0L);
        var missing=assertThrows(ProductionPlanningException.class,()->NetworkProductionPlanner.plan(out,6,
                k->k.equals(out)?List.of(pattern):List.of(),k->k.equals(ore)?20L:0L));
        assertNotEquals(ProductionPlanningException.Reason.INVALID,missing.reason());
        assertTrue(CompiledProductionGraph.stats().hits()>0);
        assertThrows(ProductionPlanningException.class,()->NetworkProductionPlanner.plan(out,5,k->List.of(),k->k.equals(ore)?10L:k.equals(tool)?1L:0L));
    }
}
