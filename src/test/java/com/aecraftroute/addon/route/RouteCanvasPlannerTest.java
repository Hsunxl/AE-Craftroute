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
}
