package com.aecraftroute.addon.planner;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.aecraftroute.addon.bench.BenchAEKey;
import com.aecraftroute.addon.bench.BenchPatternDetails;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.aecraftroute.addon.bench.BenchPatternDetails.InputSpec.of;
import static com.aecraftroute.addon.bench.BenchPatternDetails.InputSpec.returned;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the certified dispatch identity rule to the pattern's own {@code equals}.
 *
 * <p>A reload decodes the native task map and this mod's saved program separately, so two
 * instances that describe the same work are only comparable through value equality. It is
 * tempting to widen that to "same class, same visible outputs, same slot candidates" so a
 * third-party pattern without {@code equals} survives a reload, but visible stacks do not
 * describe container returns, substitution rules or provider behaviour: such a pattern would
 * be accepted where it is not the same pattern. The fail-closed cancel is the cheaper mistake
 * (the cancel path touches no inventory and the job only needs resubmitting), so this test
 * fails if the comparison is ever widened again.
 */
class PatternIdentityTest {
    private final BenchAEKey dust = BenchAEKey.of("identity_dust");
    private final BenchAEKey out = BenchAEKey.of("identity_out");

    /** A third-party-shaped pattern: same class on both sides, no value equality. */
    private static final class IdentityPattern implements IPatternDetails {
        @Override public AEItemKey getDefinition() { return null; }
        @Override public IPatternDetails.IInput[] getInputs() { return null; }
        @Override public List<GenericStack> getOutputs() { return null; }
    }

    @Test void identicalInstanceIsTheSamePattern() {
        var pattern = new BenchPatternDetails(out, 1, List.of(of(dust, 1)));
        assertTrue(ProductionJob.samePattern(pattern, pattern));
    }

    @Test void equalDecodesOfTheSamePatternAreTheSamePattern() {
        var left = new BenchPatternDetails(out, 1, List.of(of(dust, 1)));
        var right = new BenchPatternDetails(out, 1, List.of(of(dust, 1)));
        assertFalse(left == right);
        assertTrue(ProductionJob.samePattern(left, right));
    }

    /** Same outputs, same slot key and multiplier: only the container return differs. */
    @Test void identicalVisibleStacksWithDifferentContainerReturnAreNotTheSamePattern() {
        var plain = new BenchPatternDetails(out, 1, List.of(of(dust, 1)));
        var keepsContainer = new BenchPatternDetails(out, 1, List.of(returned(dust, 1)));
        assertTrue(plain.getOutputs().equals(keepsContainer.getOutputs()));
        assertFalse(plain.equals(keepsContainer));
        assertFalse(ProductionJob.samePattern(plain, keepsContainer));
    }

    @Test void patternClassWithoutValueEqualityFailsClosed() {
        assertFalse(ProductionJob.samePattern(new IdentityPattern(), new IdentityPattern()));
        assertFalse(ProductionJob.samePattern(new BenchPatternDetails(out, 1, List.of(of(dust, 1))), new IdentityPattern()));
    }
}
