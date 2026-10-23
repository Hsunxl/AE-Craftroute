package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.route.*;
import com.ae2vm.addon.vm.*;
import com.ae2vm.addon.planner.ProductionPlanningException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ae2vm.addon.bench.AlternativePatternSearchTest.*;

class ManualRoutePlannerTest {
    String id(IPatternDetails p) { return "p"+System.identityHashCode(p); }
    RoutePolicy<AEKey> empty(){return new RoutePolicy<>(Map.of());}
    Map<AEKey,List<IPatternDetails>> capture(Fixture f,RoutePolicy<AEKey> policy){
        return ManualRoutePlanner.capture(R,policy,k->f.patterns.getOrDefault(k,List.of()),this::id);
    }
    appeng.api.networking.crafting.ICraftingPlan plan(Fixture f,RoutePolicy<AEKey> policy){
        return ManualRoutePlanner.plan(R,32,capture(f,policy),k->f.stock.getOrDefault(k,0L),new BenchSimulationState(f.stock));
    }
    @Test void lockExternalDustBreaksZeroGrowthCycle(){
        var f=resonating();var external=p(D,4,RAW,1);f.add(external);
        var result=plan(f,empty().change(D,"lock",id(external)));
        assertFalse(result.simulation());assertEquals(8,result.patternTimes().get(external));assertFalse(result.patternTimes().containsKey(f.first(D)));
        EntroGrowthPatchTest.executeCpuModel(result,false,true);
    }
    @Test void lockBadCycleDoesNotUseAvailableExternalRecipe(){
        var f=resonating();f.add(p(D,4,RAW,1));
        assertThrows(ProductionPlanningException.class,()->plan(f,empty().change(D,"lock",id(f.first(D)))));
    }
    @Test void banCycleUsesExternalRecipe(){
        var f=resonating();var external=p(D,4,RAW,1);f.add(external);
        assertTrue(plan(f,empty().change(D,"ban",id(f.first(D)))).patternTimes().containsKey(external));
    }
    @Test void stockOnlyConsumesStockButNeverCraftsShortfall(){
        var f=resonating();f.add(p(D,4,RAW,1));f.stock.put(D,5L);
        var error=assertThrows(ProductionPlanningException.class,()->plan(f,empty().change(D,"stock","")));
        assertEquals(ProductionPlanningException.Reason.UNREACHABLE,error.reason());
    }
    @Test void sufficientManualStockSucceeds(){
        var f=resonating();f.stock.put(D,32L);
        var result=plan(f,empty().change(D,"stock",""));assertFalse(result.simulation());assertEquals(32,result.usedItems().get(D));
    }
    @Test void lockedRootOverridesShorterAlternative(){
        var f=resonating();var ext=p(D,4,RAW,1);f.add(ext);var direct=p(R,64,RAW,1);f.add(direct);
        var result=plan(f,empty().change(R,"lock",id(f.first(R))).change(D,"lock",id(ext)));
        assertFalse(result.patternTimes().containsKey(direct));assertTrue(result.patternTimes().containsKey(ext));
    }
    @Test void missingLockedPatternFailsExplicitly(){
        var f=resonating();assertThrows(RoutePlanningException.class,()->capture(f,empty().change(D,"lock","gone")));
    }
    @Test void rootStockOnlyDoesNotPretendToProduceNewItems(){
        var f=resonating();assertThrows(ProductionPlanningException.class,()->plan(f,empty().change(R,"stock","")));
    }
    @Test void frozenCandidateSetSurvivesSourceListMutation(){
        var f=resonating();var ext=p(D,4,RAW,1);f.add(ext);var snapshot=capture(f,empty().change(D,"lock",id(ext)));
        f.patterns.get(D).clear();assertEquals(List.of(ext),snapshot.get(D));
    }
    @Test void zeroOrNegativeQuantityFails(){
        var f=resonating();var snapshot=capture(f,empty());
        assertThrows(ProductionPlanningException.class,()->ManualRoutePlanner.plan(R,0,snapshot,k->0L,new BenchSimulationState(f.stock)));
        assertThrows(ProductionPlanningException.class,()->ManualRoutePlanner.plan(R,-1,snapshot,k->0L,new BenchSimulationState(f.stock)));
    }
    @Test void noExternalStockNeverUnlocksOrFabricatesAPlan(){
        var f=resonating();var ext=p(D,4,RAW,1);f.add(ext);f.stock.put(RAW,0L);
        var error=assertThrows(ProductionPlanningException.class,()->plan(f,empty().change(D,"lock",id(ext))));assertTrue(Set.of(ProductionPlanningException.Reason.UNREACHABLE,ProductionPlanningException.Reason.LIMIT).contains(error.reason()));
    }
}
