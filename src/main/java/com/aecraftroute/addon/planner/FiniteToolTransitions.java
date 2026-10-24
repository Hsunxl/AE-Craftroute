package com.aecraftroute.addon.planner;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import com.aecraftroute.addon.compiler.IFiniteUseInput;
import java.util.*;

/** Validates physical, acyclic damage states rather than amortising a tool count. */
final class FiniteToolTransitions {
    static void validate(IPatternDetails.IInput input) {
        if (!(input instanceof IFiniteUseInput finite) || finite.durabilityUses()==Long.MAX_VALUE) return;
        if (finite.durabilityUses()<=0) throw new IllegalArgumentException("Nonpositive tool lifetime");
        var options=input.getPossibleInputs();
        if(options==null || options.length==0) throw new IllegalArgumentException("Missing exact tool states");
        var states=new HashSet<AEKey>();
        for(var option:options) {
            if(option==null || option.amount()!=1) throw new IllegalArgumentException("Finite tool requires single-item states");
            states.add(option.what());
        }
        for(var key:states) {
            long uses=finite.remainingUses(key); var next=input.getRemainingKey(key);
            if(uses<=0 || uses>finite.durabilityUses()) throw new IllegalArgumentException("Unknown exact tool lifetime");
            if(uses==1 ? next!=null : next==null || !states.contains(next) || finite.remainingUses(next)!=uses-1)
                throw new IllegalArgumentException("Finite tool must degrade by one exact state and finally break");
        }
    }
    private FiniteToolTransitions() {}
}
