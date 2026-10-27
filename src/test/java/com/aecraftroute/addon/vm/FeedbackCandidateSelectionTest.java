package com.aecraftroute.addon.vm;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ListCraftingInventory;
import com.aecraftroute.addon.bench.BenchAEKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Characterizes the installed AE2 executor before changing feedback reservation. */
class FeedbackCandidateSelectionTest {
    private static IPatternDetails.IInput alternatives(AEKey first, AEKey second) {
        return new IPatternDetails.IInput() {
            public GenericStack[] getPossibleInputs() {
                return new GenericStack[]{new GenericStack(first,1),new GenericStack(second,1)};
            }
            public long getMultiplier() {return 1;}
            public boolean isValid(AEKey key, Level level) {return first.equals(key) || second.equals(key);}
            public AEKey getRemainingKey(AEKey key) {return null;}
        };
    }

    private static IPatternDetails pattern(AEKey first, AEKey second) {
        return new IPatternDetails() {
            public appeng.api.stacks.AEItemKey getDefinition() {return null;}
            public IInput[] getInputs() {return new IInput[]{alternatives(first,second)};}
            public List<GenericStack> getOutputs() {return List.of(new GenericStack(second,1));}
        };
    }

    @Test void ae2CanSelectSecondFeedbackCandidateWhenFirstIsUnavailable() {
        var other=BenchAEKey.of("feedback_alternative");
        var feedback=BenchAEKey.of("feedback_final");
        var inventory=new ListCraftingInventory(k->{});
        inventory.insert(feedback,1, Actionable.MODULATE);
        List<?> templates=StreamSupport.stream(CraftingCpuHelper.getValidItemTemplates(
                inventory,alternatives(other,feedback),null).spliterator(),false).toList();
        assertEquals(1,templates.size());
        assertEquals(feedback,((appeng.crafting.execution.InputTemplate)templates.getFirst()).key());
    }

    @Test void alternativeStockCanRunWithoutSpendingFinalOutput() {
        var other=BenchAEKey.of("feedback_alternative");
        var feedback=BenchAEKey.of("feedback_final");
        var inventory=new ListCraftingInventory(k->{});
        inventory.insert(other,1,Actionable.MODULATE);
        List<?> templates=StreamSupport.stream(CraftingCpuHelper.getValidItemTemplates(
                inventory,alternatives(other,feedback),null).spliterator(),false).toList();
        assertEquals(1,templates.size());
        assertEquals(other,((appeng.crafting.execution.InputTemplate)templates.getFirst()).key());
    }

    @Test void actualAe2ExtractionPrefersFirstCandidateButUsesFeedbackWhenOnlyItExists() {
        var other=BenchAEKey.of("feedback_alternative");
        var feedback=BenchAEKey.of("feedback_final");
        var pattern=pattern(other,feedback);
        var both=new ListCraftingInventory(k->{});
        both.insert(other,1,Actionable.MODULATE);
        both.insert(feedback,1,Actionable.MODULATE);
        var consumed=CraftingCpuHelper.extractPatternInputs(pattern,both,null,new KeyCounter(),new KeyCounter());
        assertEquals(1,consumed.length);
        assertEquals(0,both.extract(other,1,Actionable.SIMULATE));
        assertEquals(1,both.extract(feedback,1,Actionable.SIMULATE));

        var onlyFeedback=new ListCraftingInventory(k->{});
        onlyFeedback.insert(feedback,1,Actionable.MODULATE);
        consumed=CraftingCpuHelper.extractPatternInputs(pattern,onlyFeedback,null,new KeyCounter(),new KeyCounter());
        assertEquals(1,consumed.length);
        assertEquals(0,onlyFeedback.extract(feedback,1,Actionable.SIMULATE));
    }
}
