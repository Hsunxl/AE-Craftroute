package com.aecraftroute.addon.compiler;

/**
 * (v1.10.x DURABILITY) Optional capability an {@code IPatternDetails.IInput} may
 * implement to declare itself a FINITE-USE (durability) tool input — one
 * {@code amount}-sized unit survives {@code durabilityUses()} firings (a degrading
 * catalyst, e.g. {@code 1·A(n) + 1·B → 1·C + A(n-1)}).
 *
 * <p>The historical bytecode compiler used {@code amount × ceil(times/uses)}
 * for batch accounting. That compiler has been removed. The certified planner
 * supports finite values only with an exact {@link #remainingUses} state graph.
 * Count-only declarations remain UNSUPPORTED: a count formula does not describe
 * the physical damaged key returned by each dispatch or its final breakage.
 *
 * <p>Implementors should return {@link Long#MAX_VALUE} for a true catalyst (infinite
 * uses) and a positive finite count for a durability tool. Inputs that do NOT
 * implement this interface are treated as plain consumed or (if returned) catalyst
 * seeds according to their exact input/remainder transitions.
 */
public interface IFiniteUseInput {
    /** Firings a single amount-sized unit survives; {@code Long.MAX_VALUE} = true catalyst. */
    long durabilityUses();

    /** Exact remaining firings for a concrete damage/component key. A finite
     * tool must enumerate every accepted state in getPossibleInputs(), consume
     * one item, return the state with one fewer use via getRemainingKey(), and
     * return null on its last use. Unknown/count-only implementations deliberately
     * remain unsupported; no synthetic damage keys are supplied to a provider. */
    default long remainingUses(appeng.api.stacks.AEKey key) { return -1; }
}
