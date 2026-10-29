package com.aecraftroute.addon.planner;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/** Immutable signed sparse quantities; absent entries are exactly zero. */
final class SparseAmounts {
    final int[] keys;
    private final BigInteger[] values;
    SparseAmounts(Map<Integer,BigInteger> source) {
        var sorted=new TreeMap<Integer,BigInteger>();
        source.forEach((k,v)-> {if(v.signum()!=0)sorted.put(k,v);});
        keys=sorted.keySet().stream().mapToInt(Integer::intValue).toArray();
        values=sorted.values().toArray(BigInteger[]::new);
    }
    BigInteger get(int key) {
        int index=Arrays.binarySearch(keys,key);
        return index<0?BigInteger.ZERO:values[index];
    }
    static SparseAmounts difference(SparseAmounts output,SparseAmounts input) {
        var values=new TreeMap<Integer,BigInteger>();
        for(int k:output.keys)values.put(k,output.get(k));
        for(int k:input.keys)values.merge(k,input.get(k).negate(),BigInteger::add);
        return new SparseAmounts(values);
    }
}
