package com.aecraftroute.addon.route;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.aecraftroute.addon.bench.*;
import com.aecraftroute.addon.planner.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.aecraftroute.addon.bench.BenchPatternDetails.InputSpec.*;

class RouteCanvasPlannerTest {
    final BenchAEKey goal=BenchAEKey.of("goal"),left=BenchAEKey.of("left"),right=BenchAEKey.of("right"),metal=BenchAEKey.of("metal"),a=BenchAEKey.of("ore_a"),b=BenchAEKey.of("ore_b");
    final IPatternDetails root=new BenchPatternDetails(goal,1,List.of(of(left,1),of(right,1)));
    final IPatternDetails pLeft=new BenchPatternDetails(left,1,List.of(of(metal,1))),pRight=new BenchPatternDetails(right,1,List.of(of(metal,1)));
    final IPatternDetails pA=new BenchPatternDetails(metal,1,List.of(of(a,1))),pB=new BenchPatternDetails(metal,1,List.of(of(b,1)));
    final ProductionPlanner.Limits limits=new ProductionPlanner.Limits(12000,2_000_000_000L);
    RoutePolicy.Canvas<AEKey> canvas() {return new RoutePolicy.Canvas<>(goal,1,List.of(
            new RoutePolicy.Path<>("root",goal,"root"),new RoutePolicy.Path<>("root/0",left,"left"),new RoutePolicy.Path<>("root/1",right,"right"),
            new RoutePolicy.Path<>("root/0/0",metal,"a"),new RoutePolicy.Path<>("root/1/0",metal,"b")));}
    RouteSnapshot.Result snapshot(boolean includeB) {
        return new RouteSnapshot.Result(Map.of(goal,List.of(root),left,List.of(pLeft),right,List.of(pRight),metal,includeB?List.of(pA,pB):List.of(pA)),
                Map.of(a,1L,b,1L),Map.of(root,"root",pLeft,"left",pRight,"right",pA,"a",pB,"b"));
    }
    @Test void twoSameMaterialPathsProduceOneRealCpuProgram() {
        var plan=RouteCanvasPlanner.plan(snapshot(true),canvas(),goal,1,limits);
        assertEquals(1L,plan.patternTimes().get(pA));assertEquals(1L,plan.patternTimes().get(pB));
        assertEquals(plan.patternTimes().values().stream().mapToLong(Long::longValue).sum(),plan.bytes(),
                "Only physical pattern firings consume native crafting CPU bytes");
        var job=new ProductionJob(plan);var counts=new HashMap<IPatternDetails,Long>();
        while(!job.done()) {
            var next=plan.patternTimes().keySet().stream().filter(job::allows).findFirst().orElseThrow();
            counts.merge(next,1L,Long::sum);job.accepted(next);
            assertTrue(counts.values().stream().mapToLong(Long::longValue).sum()<10);
        }
        assertEquals(plan.patternTimes(),counts,"no virtual path step is ever dispatched to the CPU");
        assertEquals(1L,plan.usedItems().get(a));assertEquals(1L,plan.usedItems().get(b));
    }
    @Test void missingOrBannedPathPatternFailsRatherThanChoosingOtherProducer() {
        var error=assertThrows(ProductionPlanningException.class,()->RouteCanvasPlanner.plan(snapshot(false),canvas(),goal,1,limits));
        assertEquals(ProductionPlanningException.Reason.INVALID,error.reason());assertTrue(error.getMessage().contains("root/1/0"));
    }
    @Test void pathCannotLieAboutParentInputOrUseDisconnectedNode() {
        var paths=new ArrayList<>(canvas().paths());paths.set(1,new RoutePolicy.Path<>("root/0",right,"right"));
        assertEquals(ProductionPlanningException.Reason.INVALID,assertThrows(ProductionPlanningException.class,()->
                RouteCanvasPlanner.plan(snapshot(true),new RoutePolicy.Canvas<>(goal,1,paths),goal,1,limits)).reason());
    }
    @Test void appliedCanvasCannotSilentlyFollowDifferentGoalOrAmount() {
        assertEquals(ProductionPlanningException.Reason.INVALID,assertThrows(ProductionPlanningException.class,()->
                RouteCanvasPlanner.plan(snapshot(true),canvas(),goal,2,limits)).reason());
        assertEquals(ProductionPlanningException.Reason.INVALID,assertThrows(ProductionPlanningException.class,()->
                RouteCanvasPlanner.plan(snapshot(true),canvas(),metal,1,limits)).reason());
    }
    @Test void pathOnlyPolicyIsNotTreatedAsEmptyAndMaterialEditExplicitlyReplacesIt() {
        var policy=new RoutePolicy<AEKey>(Map.of(),canvas());assertFalse(policy.empty());
        var changed=policy.change(metal,"lock","a");assertNull(changed.canvas());assertEquals("a",changed.rule(metal).locked());
    }
    private RouteSnapshot.Result countedSnapshot(java.util.concurrent.atomic.AtomicInteger inspected) {
        IPatternDetails counted=(IPatternDetails)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{IPatternDetails.class},(proxy,method,args)->{
                    if(method.getName().equals("hashCode")){inspected.incrementAndGet();return System.identityHashCode(proxy);}
                    if(method.getName().equals("equals"))return proxy==args[0];
                    return method.invoke(root,args);
                });
        var identities=new IdentityHashMap<IPatternDetails,String>();identities.put(counted,"root");
        return new RouteSnapshot.Result(Map.of(goal,List.of(counted)),Map.of(),identities);
    }
    @Test void interruptedCanvasDoesNotStartPatternInspection() {
        var inspected=new java.util.concurrent.atomic.AtomicInteger();var snapshot=countedSnapshot(inspected);
        var canvas=new RoutePolicy.Canvas<AEKey>(goal,1,List.of(new RoutePolicy.Path<>("root",goal,"root")));
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.util.concurrent.CancellationException.class,()->RouteCanvasPlanner.plan(snapshot,canvas,goal,1,limits));
            assertEquals(0,inspected.get());
        } finally {Thread.interrupted();}
    }
    @Test void expiredCanvasBudgetDoesNotStartPatternInspection() {
        var inspected=new java.util.concurrent.atomic.AtomicInteger();var snapshot=countedSnapshot(inspected);
        var canvas=new RoutePolicy.Canvas<AEKey>(goal,1,List.of(new RoutePolicy.Path<>("root",goal,"root")));
        assertEquals(ProductionPlanningException.Reason.LIMIT,assertThrows(ProductionPlanningException.class,
                ()->RouteCanvasPlanner.plan(snapshot,canvas,goal,1,new ProductionPlanner.Limits(6000,1))).reason());
        assertEquals(0,inspected.get());
    }
    @Test void cancellationDuringIdentityLookupStopsBeforeTheNextCandidate() {
        var calls=new java.util.concurrent.atomic.AtomicInteger[]{new java.util.concurrent.atomic.AtomicInteger(),new java.util.concurrent.atomic.AtomicInteger()};
        var armed=new java.util.concurrent.atomic.AtomicBoolean();
        var candidates=new ArrayList<IPatternDetails>();var identities=new IdentityHashMap<IPatternDetails,String>();
        for(int i=0;i<2;i++) {
            final int index=i;
            IPatternDetails candidate=(IPatternDetails)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{IPatternDetails.class},(proxy,method,args)->{
                        if(method.getName().equals("hashCode")) {
                            if(calls[index].incrementAndGet()==2 && index==0 && armed.get())Thread.currentThread().interrupt();
                            return index+1;
                        }
                        if(method.getName().equals("equals"))return proxy==args[0];
                        return method.invoke(root,args);
                    });
            candidates.add(candidate);identities.put(candidate,i==0?"other":"root");
        }
        var snapshot=new RouteSnapshot.Result(Map.of(goal,candidates),Map.of(),identities);
        for(var counter:calls)counter.set(0);armed.set(true);
        var canvas=new RoutePolicy.Canvas<AEKey>(goal,1,List.of(new RoutePolicy.Path<>("root",goal,"root")));
        try {
            assertThrows(java.util.concurrent.CancellationException.class,()->RouteCanvasPlanner.plan(snapshot,canvas,goal,1,limits));
            assertEquals(1,calls[1].get(),"only preflight may have hashed the second candidate");
        } finally {Thread.interrupted();}
    }
    @Test void deepestAcceptedCanvasPathCanConsumeAnUnexpandedRawIngredient() {
        var patterns=new LinkedHashMap<AEKey,List<IPatternDetails>>();
        var ids=new IdentityHashMap<IPatternDetails,String>();
        var paths=new ArrayList<RoutePolicy.Path<AEKey>>();
        var keys=new ArrayList<BenchAEKey>();
        for(int i=0;i<=RouteGraphMath.MAX_PATH_DEPTH+1;i++)keys.add(BenchAEKey.of("depth_"+i));
        String path="root";
        for(int i=0;i<=RouteGraphMath.MAX_PATH_DEPTH;i++) {
            var pattern=new BenchPatternDetails(keys.get(i),1,List.of(of(keys.get(i+1),1)));
            String id="depth_pattern_"+i;patterns.put(keys.get(i),List.of(pattern));ids.put(pattern,id);
            paths.add(new RoutePolicy.Path<>(path,keys.get(i),id));
            if(i<RouteGraphMath.MAX_PATH_DEPTH)path=RouteGraphMath.childPath(path,0);
        }
        assertTrue(RouteGraphMath.validPath(path));
        assertFalse(RouteGraphMath.canExtendPath(path));
        for(long amount:new long[]{1,100_000}) {
            var snapshot=new RouteSnapshot.Result(patterns,Map.of(keys.getLast(),amount),ids);
            var canvas=new RoutePolicy.Canvas<AEKey>(keys.getFirst(),amount,paths);
            var plan=RouteCanvasPlanner.plan(snapshot,canvas,keys.getFirst(),amount,limits);
            assertEquals(RouteGraphMath.MAX_PATH_DEPTH+1,plan.patternTimes().size());
            assertEquals(amount,plan.usedItems().get(keys.getLast()));
            assertTrue(plan.patternTimes().values().stream().allMatch(n->n==amount));
        }
        var noRawStock=new RouteSnapshot.Result(patterns,Map.of(),ids);
        var canvas=new RoutePolicy.Canvas<AEKey>(keys.getFirst(),1,paths);
        assertEquals(ProductionPlanningException.Reason.UNREACHABLE,assertThrows(ProductionPlanningException.class,
                ()->RouteCanvasPlanner.plan(noRawStock,canvas,keys.getFirst(),1,limits)).reason());
        var tooDeep=new ArrayList<>(paths);
        tooDeep.add(new RoutePolicy.Path<>(path+"/0",keys.getFirst(),"depth_pattern_0"));
        assertEquals(ProductionPlanningException.Reason.INVALID,assertThrows(ProductionPlanningException.class,
                ()->RouteCanvasPlanner.plan(noRawStock,new RoutePolicy.Canvas<>(keys.getFirst(),1,tooDeep),keys.getFirst(),1,limits)).reason());
    }
}
