package com.ae2vm.addon.planner;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import java.util.*;

/** Exact slot allocation. The provider still receives the original encoded pattern. */
public record PatternVariant(IPatternDetails pattern, List<Slot> slots) {
    public record Portion(GenericStack template,long multiplier) {
        public Portion { if(template==null || template.amount()<=0 || multiplier<=0) throw new IllegalArgumentException("Invalid allocation"); }
    }
    public record Slot(List<Portion> portions) {
        public Slot { portions=List.copyOf(portions); if(portions.isEmpty()) throw new IllegalArgumentException("Empty allocation"); }
    }
    public PatternVariant { Objects.requireNonNull(pattern); slots=List.copyOf(slots); }
    public ProductionPlanner.Recipe<AEKey,PatternVariant> recipe() {
        if(slots.size()!=pattern.getInputs().length) throw new IllegalArgumentException("Slot count changed");
        var inputs=new LinkedHashMap<AEKey,Long>(); var outputs=new LinkedHashMap<AEKey,Long>();
        for(var out:pattern.getOutputs()) outputs.merge(out.what(),out.amount(),Math::addExact);
        for(int i=0;i<slots.size();i++) {
            var input=pattern.getInputs()[i]; long allocated=0;
            if(input instanceof com.ae2vm.addon.compiler.IFiniteUseInput finite && finite.durabilityUses()!=Long.MAX_VALUE)
                throw new IllegalArgumentException("Finite durability is not an unchanged reusable key");
            for(var portion:slots.get(i).portions()) {
                var choice=portion.template(); allocated=Math.addExact(allocated,portion.multiplier());
                if(Arrays.stream(input.getPossibleInputs()).noneMatch(choice::equals)) throw new IllegalArgumentException("Invalid template");
                inputs.merge(choice.what(),Math.multiplyExact(choice.amount(),portion.multiplier()),Math::addExact);
                var remainder=input.getRemainingKey(choice.what());
                // One remainder per template, NOT per item.
                if(remainder!=null) outputs.merge(remainder,portion.multiplier(),Math::addExact);
            }
            if(allocated!=input.getMultiplier()) throw new IllegalArgumentException("Incomplete slot allocation");
        }
        return new ProductionPlanner.Recipe<>(this,inputs,outputs);
    }
}
