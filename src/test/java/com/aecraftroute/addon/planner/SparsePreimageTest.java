package com.aecraftroute.addon.planner;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.Random;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class SparsePreimageTest {
    @Test void matchesDenseFormulaIncludingCatalystsAndLargeBatches() {
        var random = new Random(20261029);
        for (int trial=0;trial<500;trial++) {
            var goal=new BigInteger[40]; var input=new BigInteger[40]; var delta=new BigInteger[40];
            for(int k=0;k<40;k++) {
                goal[k]=BigInteger.valueOf(random.nextInt(100));
                long a=random.nextInt(4)==0?random.nextInt(9):0;
                long b=random.nextInt(4)==0?random.nextInt(9):0;
                input[k]=BigInteger.valueOf(a);delta[k]=BigInteger.valueOf(b-a);
            }
            long times=trial%2==0?Long.MAX_VALUE:1+random.nextInt(100);
            var touched=IntStream.range(0,40).filter(k->input[k].signum()!=0||delta[k].signum()!=0).toArray();
            var actual=SparsePreimage.apply(goal,input,delta,times,touched);
            var n=BigInteger.valueOf(times);
            for(int k=0;k<40;k++) assertEquals(input[k].add(delta[k].negate().max(BigInteger.ZERO)
                    .multiply(n.subtract(BigInteger.ONE))).max(goal[k].subtract(delta[k].multiply(n)))
                    .max(BigInteger.ZERO),actual[k]);
            assertNotSame(goal,actual);
        }
    }
}
