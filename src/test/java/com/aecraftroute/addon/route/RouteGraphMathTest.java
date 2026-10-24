package com.aecraftroute.addon.route;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RouteGraphMathTest {
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
}
