package com.ae2vm.addon.route;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RouteCanvasBranchesTest {
    @Test void nineIdenticalSlotsBecomeOneBranchWithNineCount() {
        var source=Collections.nCopies(9,new RouteView.Stack("iron","铁锭",1));
        assertEquals(List.of(new RouteView.Stack("iron","铁锭",9)),RouteGraphMath.groupedInputs(source));
        assertEquals(9,source.size());
    }
    @Test void componentVariantsStaySeparateAndOrderIsStable() {
        var a=new RouteView.Stack("item:{variant:a}","A",2);
        var b=new RouteView.Stack("item:{variant:b}","B",3);
        assertEquals(List.of(new RouteView.Stack(a.key(),"A",4),b),RouteGraphMath.groupedInputs(List.of(a,b,a)));
    }
    @Test void overflowIsNotSilentlyClamped() {
        assertThrows(ArithmeticException.class,()->RouteGraphMath.groupedInputs(List.of(
                new RouteView.Stack("x","X",Long.MAX_VALUE),new RouteView.Stack("x","X",1))));
    }
}
