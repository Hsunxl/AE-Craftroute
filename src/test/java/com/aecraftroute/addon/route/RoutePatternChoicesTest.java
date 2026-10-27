package com.aecraftroute.addon.route;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.GenericStack;
import com.aecraftroute.addon.bench.BenchAEKey;
import com.aecraftroute.addon.bench.BenchPatternDetails;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;

import static com.aecraftroute.addon.bench.BenchPatternDetails.InputSpec.of;
import static org.junit.jupiter.api.Assertions.*;

class RoutePatternChoicesTest {
    @Test
    void lockAndBanAcceptEqualSerializedIdentityNotOnlyTheSameStringObject() {
        var identities=new java.util.IdentityHashMap<IPatternDetails,String>();
        var pattern=new BenchPatternDetails(BenchAEKey.of("example:output"),1,List.of());
        identities.put(pattern,new String("pattern-sha256"));
        assertTrue(RouteNetwork.hasPatternIdentity(identities,new String("pattern-sha256")));
        assertFalse(RouteNetwork.hasPatternIdentity(identities,"different"));
    }

    private static IPatternDetails pattern(IPatternDetails.IInput[] inputs, List<GenericStack> outputs) {
        return (IPatternDetails) Proxy.newProxyInstance(IPatternDetails.class.getClassLoader(),
                new Class<?>[]{IPatternDetails.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getInputs" -> inputs;
                    case "getOutputs" -> outputs;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static RouteView.Choice read(IPatternDetails pattern) {
        return RoutePatternChoices.read(pattern, "healthy", stack ->
                new RouteView.Stack(String.valueOf(stack.what()), "test", stack.amount()));
    }

    @Test
    void malformedThirdPartyPatternDoesNotPreventReadingTheNextHealthyPattern() {
        var inputKey = BenchAEKey.of("example:input");
        var outputKey = BenchAEKey.of("example:output");
        var healthy = new BenchPatternDetails(outputKey, 2, List.of(of(inputKey, 3)));
        var input = healthy.getInputs()[0];
        var nullCandidates = (IPatternDetails.IInput) Proxy.newProxyInstance(
                IPatternDetails.IInput.class.getClassLoader(), new Class<?>[]{IPatternDetails.IInput.class},
                (proxy, method, args) -> method.getName().equals("getPossibleInputs") ? null
                        : method.invoke(input, args));

        assertThrows(IllegalArgumentException.class, () -> read(pattern(new IPatternDetails.IInput[]{nullCandidates}, healthy.getOutputs())));
        assertThrows(IllegalArgumentException.class, () -> read(pattern(healthy.getInputs(), null)));
        assertThrows(IllegalArgumentException.class, () -> read(pattern(healthy.getInputs(), Arrays.asList((GenericStack) null))));

        var choice = read(healthy);
        assertEquals(3, choice.inputs().get(0).amount());
        assertEquals(2, choice.outputs().get(0).amount());
    }
}
