package com.aecraftroute.addon.route;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoutePatternRulesTest {
    RouteView.Stack stack(long amount) { return new RouteView.Stack("test:key","材料",amount); }
    RouteView.Draft draft(List<RouteView.Stack> inputs,List<RouteView.Stack> outputs) {
        return new RouteView.Draft("test:recipe","测试",inputs,outputs,"");
    }
    @Test void validDraft() { assertDoesNotThrow(()->RoutePatternRules.validate(draft(List.of(stack(1)),List.of(stack(4))))); }
    @Test void emptyInputsRejected() { assertThrows(IllegalArgumentException.class,()->RoutePatternRules.validate(draft(List.of(),List.of(stack(1))))); }
    @Test void emptyOutputsRejected() { assertThrows(IllegalArgumentException.class,()->RoutePatternRules.validate(draft(List.of(stack(1)),List.of()))); }
    @Test void nonPositiveOrOverflowRejected() {
        for(long n:new long[]{0,-1,Long.MAX_VALUE,2147483648L})
            assertThrows(IllegalArgumentException.class,()->RoutePatternRules.validate(draft(List.of(stack(n)),List.of(stack(1)))));
    }
    @Test void slotLimits() {
        assertThrows(IllegalArgumentException.class,()->RoutePatternRules.validate(draft(Collections.nCopies(82,stack(1)),List.of(stack(1)))));
        assertThrows(IllegalArgumentException.class,()->RoutePatternRules.validate(draft(List.of(stack(1)),Collections.nCopies(27,stack(1)))));
    }
    @Test void nullOrHugeKeysRejected() {
        for(String key:Arrays.asList(null,"","x".repeat(4097)))
            assertThrows(IllegalArgumentException.class,()->RoutePatternRules.validate(draft(List.of(new RouteView.Stack(key,"",1)),List.of(stack(1)))));
    }
    @Test void overlappingTagBacktracks() {
        assertArrayEquals(new int[]{1,0},RoutePatternRules.assign(2,(slot,item)->slot==0 || item==0));
    }
    @Test void eachInputUsedExactlyOnce() {
        assertThrows(IllegalArgumentException.class,()->RoutePatternRules.assign(2,(slot,item)->item==0));
    }
    @Test void matchingBoundedToCraftingGrid() { assertThrows(IllegalArgumentException.class,()->RoutePatternRules.assign(10,(a,b)->true)); }
}
