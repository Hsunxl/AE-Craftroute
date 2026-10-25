package com.aecraftroute.addon.planner;

import java.math.BigInteger;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Incomplete construction of a nonnegative, non-increasing resource potential. A heuristic failure
 * means UNKNOWN; only a fully checked separating certificate proves unreachable.
 * No item IDs, presumed recipe costs, or floating point tolerances are used. */
final class ConservedResourceBound {
    record Certificate(List<BigInteger> weights,BigInteger available,BigInteger required,
                       BigInteger maximumAdditionalTarget) {}
    private static final BigInteger ZERO=BigInteger.ZERO,ONE=BigInteger.ONE;
    private ConservedResourceBound() {}

    static Certificate prove(CompiledProductionGraph graph,BigInteger[] stock,int target,long increment,
                             BooleanSupplier expired) {
        if(expired.getAsBoolean())return null;
        var weights=new BigInteger[graph.keys];Arrays.fill(weights,ZERO);
        // Integer fixed-point precision is only a candidate-construction choice.
        // Rounding upwards weakens the bound; the final proof is exact regardless.
        weights[target]=ONE.shiftLeft(256);
        boolean stable=false;
        for(int pass=0;pass<=graph.keys;pass++) {
            boolean changed=false;
            for(int r=0;r<graph.recipes;r++) {
                if(expired.getAsBoolean())return null;
                var gain=dot(weights,graph.produced[r],graph.production[r])
                        .subtract(dot(weights,graph.consumed[r],graph.consumption[r]));
                if(gain.signum()<=0)continue;
                if(graph.consumed[r].length==0)return null;
                BigInteger units=ZERO;
                for(long n:graph.consumption[r])units=units.add(BigInteger.valueOf(n));
                var addition=gain.add(units).subtract(ONE).divide(units);
                for(int key:graph.consumed[r]) {
                    weights[key]=weights[key].add(addition);
                    if(weights[key].bitLength()>8192)return null;
                }
                changed=true;
            }
            if(!changed){stable=true;break;}
        }
        if(!stable || expired.getAsBoolean())return null;
        // Independently verify EVERY physical transition, not just the chosen
        // supply policy. Alternative producers, byproducts and growth loops may
        // invalidate the bound, in which case it must not reject a request.
        for(int r=0;r<graph.recipes;r++) {
            if(expired.getAsBoolean())return null;
            if(dot(weights,graph.produced[r],graph.production[r])
                    .compareTo(dot(weights,graph.consumed[r],graph.consumption[r]))>0)return null;
        }
        BigInteger available=ZERO;
        for(int k=0;k<graph.keys;k++) {
            if(expired.getAsBoolean())return null;
            available=available.add(weights[k].multiply(stock[k]));
        }
        var required=weights[target].multiply(stock[target].add(BigInteger.valueOf(increment)));
        if(required.compareTo(available)<=0 || expired.getAsBoolean())return null;
        var maximum=available.divide(weights[target]).subtract(stock[target]).max(ZERO);
        return new Certificate(List.copyOf(Arrays.asList(weights)),available,required,maximum);
    }
    private static BigInteger dot(BigInteger[] weights,int[] keys,long[] counts) {
        BigInteger total=ZERO;
        for(int i=0;i<keys.length;i++)if(weights[keys[i]].signum()!=0)
            total=total.add(weights[keys[i]].multiply(BigInteger.valueOf(counts[i])));
        return total;
    }
}
