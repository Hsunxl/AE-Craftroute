package com.aecraftroute.addon.planner;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.*;
import java.util.*;

/** Plan and proof are one object: never strip the schedule at a cache/rebind boundary. */
public record CertifiedCraftingPlan(GenericStack finalOutput,long bytes,KeyCounter usedItems,
        Map<IPatternDetails,Long> patternTimes,List<ProductionPlanner.Block<PatternVariant>> program)
        implements ICraftingPlan {
    public CertifiedCraftingPlan { patternTimes=Map.copyOf(patternTimes); program=List.copyOf(program); }
    @Override public boolean simulation() { return false; }
    @Override public boolean multiplePaths() { return true; }
    @Override public KeyCounter emittedItems() { return new KeyCounter(); }
    @Override public KeyCounter missingItems() { return new KeyCounter(); }
}
