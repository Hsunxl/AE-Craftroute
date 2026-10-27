package com.aecraftroute.addon.route;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.GenericStack;

import java.util.ArrayList;
import java.util.function.Function;

/** Reads one third-party pattern for the canvas without trusting nullable interface returns. */
final class RoutePatternChoices {
    private RoutePatternChoices() {}

    static RouteView.Choice read(IPatternDetails pattern, String identity,
                                 Function<GenericStack, RouteView.Stack> stackView) {
        var rawInputs = pattern.getInputs();
        var rawOutputs = pattern.getOutputs();
        if (rawInputs == null || rawOutputs == null)
            throw new IllegalArgumentException("样板输入或输出为空");
        var inputs = new ArrayList<RouteView.Stack>();
        boolean alternatives = false;
        for (var input : rawInputs) {
            if (input == null) throw new IllegalArgumentException("样板输入为空");
            var possible = input.getPossibleInputs();
            if (possible == null) throw new IllegalArgumentException("样板输入候选为空");
            if (possible.length == 0) continue;
            alternatives |= possible.length > 1;
            var first = possible[0];
            if (first == null || first.what() == null)
                throw new IllegalArgumentException("样板输入候选无材料");
            inputs.add(stackView.apply(new GenericStack(first.what(),
                    Math.multiplyExact(first.amount(), input.getMultiplier()))));
        }
        var outputs = new ArrayList<RouteView.Stack>();
        for (var output : rawOutputs) {
            if (output == null || output.what() == null)
                throw new IllegalArgumentException("样板输出无材料");
            outputs.add(stackView.apply(output));
        }
        return new RouteView.Choice(identity, inputs, outputs, alternatives);
    }
}
