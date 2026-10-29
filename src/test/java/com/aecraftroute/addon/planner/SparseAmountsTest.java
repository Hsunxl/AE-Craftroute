package com.aecraftroute.addon.planner;

import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SparseAmountsTest {
    @Test void differenceKeepsSignedNonzeroAmountsOnly() {
        var input=new SparseAmounts(Map.of(1,BigInteger.TEN,5,BigInteger.ONE));
        var output=new SparseAmounts(Map.of(1,BigInteger.ONE,5,BigInteger.ONE,90000,BigInteger.TWO));
        var delta=SparseAmounts.difference(output,input);
        assertArrayEquals(new int[]{1,90000},delta.keys);
        assertEquals(BigInteger.valueOf(-9),delta.get(1));
        assertEquals(BigInteger.ZERO,delta.get(5));
        assertEquals(BigInteger.ZERO,delta.get(60000));
        var goal=new BigInteger[]{BigInteger.ZERO,BigInteger.TEN};
        var required=new SparseAmounts(Map.of(1,BigInteger.TWO));
        var net=new SparseAmounts(Map.of(1,BigInteger.ONE));
        assertArrayEquals(new BigInteger[]{BigInteger.ZERO,BigInteger.valueOf(7)},
                SparsePreimage.apply(goal,required,net,3,new int[]{1}));
    }
}
