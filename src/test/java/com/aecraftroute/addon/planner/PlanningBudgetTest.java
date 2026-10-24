package com.aecraftroute.addon.planner;

import appeng.api.stacks.AEKey;
import com.aecraftroute.addon.bench.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.aecraftroute.addon.bench.BenchPatternDetails.InputSpec.of;
import static org.junit.jupiter.api.Assertions.*;

class PlanningBudgetTest {
    private final BenchAEKey ore=BenchAEKey.of("budget-ore"), out=BenchAEKey.of("budget-out");
    private final BenchPatternDetails pattern=new BenchPatternDetails(out,1,List.of(of(ore,1)));
    private void slow() {try {Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private void limit(Runnable action) {
        assertEquals(ProductionPlanningException.Reason.LIMIT,assertThrows(ProductionPlanningException.class,action::run).reason());
    }
    @Test void sourceTraversalIsChargedToWholeWorkerBudget() {
        limit(()->NetworkProductionPlanner.plan(out,1,k->{slow();return List.of(pattern);},k->1L,
                new ProductionPlanner.Limits(1000,1_000_000L)));
    }
    @Test void stockCallbacksCannotHideOutsideSearchBudget() {
        limit(()->NetworkProductionPlanner.plan(out,1,k->k.equals(out)?List.of(pattern):List.of(),k->{slow();return k.equals(ore)?1L:0L;},
                new ProductionPlanner.Limits(1000,1_000_000L)));
    }
    @Test void cachedProofDoesNotBypassExpiredEndToEndBudget() {
        NetworkProductionPlanner.plan(out,1,k->k.equals(out)?List.of(pattern):List.of(),k->k.equals(ore)?1L:0L);
        limit(()->NetworkProductionPlanner.plan(out,1,k->k.equals(out)?List.of(pattern):List.of(),k->k.equals(ore)?1L:0L,
                new ProductionPlanner.Limits(1000,1)));
    }
}
