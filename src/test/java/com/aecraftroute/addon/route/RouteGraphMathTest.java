package com.aecraftroute.addon.route;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RouteGraphMathTest {
    @Test void parentLookupDoesNotSlicePathsWithoutASeparator() {
        assertNull(RouteGraphMath.parentPath(null));
        assertNull(RouteGraphMath.parentPath("root"));
        assertNull(RouteGraphMath.parentPath("foreign-root"));
        assertEquals("root/1",RouteGraphMath.parentPath("root/1/2"));
    }
    @Test void deepPathsAreAcceptedWithoutRegexStackGrowth() {
        String path="root";
        for(int depth=1;depth<=2000;depth++)path=RouteGraphMath.childPath(path,0);
        assertTrue(RouteGraphMath.validPath(path));assertTrue(RouteGraphMath.canExtendPath(path));
        assertFalse(RouteGraphMath.validPath(path+"/"));
        assertTrue(RouteGraphMath.validPath(path+"/1234"));
        assertFalse(RouteGraphMath.validPath(path+"/2147483648"));
        assertFalse(RouteGraphMath.validPath("rootx/0"));
    }
    private static RouteView.Stack stack(String key, long amount) {
        return new RouteView.Stack(key, key, amount);
    }

    private static RouteView.Choice choice(String id, String output, long amount, long input) {
        return new RouteView.Choice(id,
                List.of(stack("input", input)),
                List.of(stack(output, amount)),
                false);
    }

    private static RouteView.Reply reply(String locked, Set<String> banned, boolean stock,
                                         RouteView.Choice... choices) {
        return new RouteView.Reply("ok", List.of(choices), locked, List.copyOf(banned), stock,
                choices.length, false);
    }

    @Test
    void lockedRecipeWinsEvenWhenAnotherRecipeIsCheaperLooking() {
        var first = choice("a", "dust", 64, 1);
        var locked = choice("b", "dust", 1, 8);
        var preview = RouteGraphMath.preview(reply("b", Set.of(), false, first, locked), null);
        assertTrue(preview.locked());
        assertEquals("b", preview.choice().id());
        assertEquals("已固定", preview.reason());
    }

    @Test
    void missingLockNeverFallsBack() {
        var preview = RouteGraphMath.preview(
                reply("gone", Set.of(), false, choice("a", "dust", 64, 1)), null);
        assertTrue(preview.locked());
        assertNull(preview.choice());
        assertTrue(preview.reason().contains("固定的样板已不存在"));
    }

    @Test
    void bannedRecipesAreSkippedForAutomaticPreview() {
        var banned = choice("bad", "dust", 64, 1);
        var good = choice("good", "dust", 16, 2);
        var preview = RouteGraphMath.preview(reply(null, Set.of("bad"), false, banned, good), null);
        assertFalse(preview.locked());
        assertEquals("good", preview.choice().id());
        assertTrue(preview.reason().contains("还没固定"));
    }

    @Test
    void jeiDraftIsPreviewOnlyAndCannotPretendToBeEncoded() {
        var draft = new RouteView.Choice("jei:test", List.of(stack("seed", 1)),
                List.of(stack("crystal", 1)), false);
        var preview = RouteGraphMath.preview(reply(null, Set.of(), false,
                choice("encoded", "crystal", 1, 1)), draft);
        assertTrue(preview.draft());
        assertFalse(preview.executable());
        assertEquals("jei:test", preview.choice().id());
    }

    @Test
    void stockOnlyDoesNotExpandAnyRecipe() {
        var preview = RouteGraphMath.preview(
                reply(null, Set.of(), true, choice("a", "dust", 64, 1)), null);
        assertNull(preview.choice());
        assertTrue(preview.reason().contains("只用手头材料"));
    }

    @Test
    void demandPropagatesAsWholePatternRuns() {
        assertEquals(1, RouteGraphMath.runs(0, 64));
        assertEquals(1, RouteGraphMath.runs(64, 64));
        assertEquals(2, RouteGraphMath.runs(65, 64));
        assertEquals(2, RouteGraphMath.runs(128, 64));
        assertEquals(3, RouteGraphMath.runs(129, 64));
        assertEquals(64, RouteGraphMath.inputNeed(32, 2));
        assertEquals(0, RouteGraphMath.inputNeed(0, 10));
    }

    @Test
    void outputAmountSumsOnlyTheRequestedMaterial() {
        var multi = new RouteView.Choice("multi", List.of(),
                List.of(stack("dust", 2), stack("byproduct", 5), stack("dust", 3)), false);
        assertEquals(5, RouteGraphMath.outputAmount(multi, "dust"));
        assertEquals(5, RouteGraphMath.outputAmount(multi, "byproduct"));
        assertEquals(0, RouteGraphMath.outputAmount(multi, "other"));
    }

    @Test
    void overflowIsRejectedInsteadOfWrapping() {
        assertThrows(ArithmeticException.class,
                () -> RouteGraphMath.inputNeed(Long.MAX_VALUE, 2));
    }

    @Test
    void compactAmountsRemainReadable() {
        assertEquals("0", RouteGraphMath.format(0));
        assertEquals("9999", RouteGraphMath.format(9999));
        assertEquals("1000", RouteGraphMath.format(1000));
        assertEquals("10.0k", RouteGraphMath.format(10_000));
        assertEquals("1.50M", RouteGraphMath.format(1_500_000));
    }

    @Test
    void demandOverrideIsClampedAndParsedAsWholeNumbers() {
        assertEquals(1, RouteGraphMath.clampDemand(0));
        assertEquals(1, RouteGraphMath.clampDemand(-5));
        assertEquals(RouteGraphMath.MAX_DEMAND, RouteGraphMath.clampDemand(Long.MAX_VALUE));
        assertNull(RouteGraphMath.parseDemand(""));
        assertNull(RouteGraphMath.parseDemand("12a"));
        assertNull(RouteGraphMath.parseDemand("-3"));
        assertNull(RouteGraphMath.parseDemand("99999999999999999999"));
        assertEquals(Long.valueOf(1), RouteGraphMath.parseDemand("0"));
        assertEquals(Long.valueOf(12), RouteGraphMath.parseDemand("12"));
        assertEquals(Long.valueOf(999_999_999_999_999L), RouteGraphMath.parseDemand("999999999999999"));
        assertEquals(Long.valueOf(RouteGraphMath.MAX_DEMAND),RouteGraphMath.parseDemand("10000000000000000"));
    }

    @Test
    void stepMovesByOneRecipeBatchOrOneUnit() {
        assertEquals(74, RouteGraphMath.stepDemand(10, 64, 1, false));
        assertEquals(11, RouteGraphMath.stepDemand(10, 64, 1, true));
        assertEquals(1, RouteGraphMath.stepDemand(10, 64, -1, false));
        assertEquals(9, RouteGraphMath.stepDemand(10, 64, -1, true));
        assertEquals(6, RouteGraphMath.stepDemand(5, 0, 1, false));
        assertEquals(RouteGraphMath.MAX_DEMAND,
                RouteGraphMath.stepDemand(RouteGraphMath.MAX_DEMAND, Long.MAX_VALUE, 1, false));
    }

    @Test
    void overrideReplacesDerivedDemandOnlyWhenPresent() {
        assertEquals(40, RouteGraphMath.applyOverride(40, null));
        assertEquals(7, RouteGraphMath.applyOverride(40, 7L));
        assertEquals(RouteGraphMath.MAX_DEMAND, RouteGraphMath.applyOverride(40, Long.MAX_VALUE));
    }

    @Test
    void downstreamFollowsOverriddenDemandAsWholeRuns() {
        // override 130 with output 64 -> 3 runs -> 3 * perRun(3) = 9 input
        assertEquals(3, RouteGraphMath.runs(130, 64));
        assertEquals(9, RouteGraphMath.inputNeed(3, RouteGraphMath.runs(130, 64)));
    }
}
