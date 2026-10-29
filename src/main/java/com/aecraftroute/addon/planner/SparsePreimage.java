package com.aecraftroute.addon.planner;

import java.math.BigInteger;

/** Exact predecessor calculation; untouched keys retain their original demand. */
final class SparsePreimage {
    private SparsePreimage() {}

    static BigInteger[] apply(BigInteger[] goal, SparseAmounts required,
            SparseAmounts delta, long times, int[] touched) {
        var n=BigInteger.valueOf(times); var before=n.subtract(BigInteger.ONE);
        var result=goal.clone();
        for(int k:touched) result[k]=required.get(k).add(delta.get(k).negate().max(BigInteger.ZERO).multiply(before))
                .max(goal[k].subtract(delta.get(k).multiply(n))).max(BigInteger.ZERO);
        return result;
    }

    static BigInteger[] apply(BigInteger[] goal, BigInteger[] required,
            BigInteger[] delta, long times, int[] touched) {
        BigInteger n = BigInteger.valueOf(times);
        BigInteger before = n.subtract(BigInteger.ONE);
        var result = goal.clone();
        for (int k : touched) {
            result[k] = required[k].add(delta[k].negate().max(BigInteger.ZERO).multiply(before))
                    .max(goal[k].subtract(delta[k].multiply(n))).max(BigInteger.ZERO);
        }
        return result;
    }
}
