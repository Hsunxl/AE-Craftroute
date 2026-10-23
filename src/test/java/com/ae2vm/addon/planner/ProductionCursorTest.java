package com.ae2vm.addon.planner;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ProductionCursorTest {
    @Test void compressedRemainingCountsMatchEveryExpandedPrefix() {
        var blocks=List.of(new ProductionPlanner.Block<>(List.of(new ProductionPlanner.Step<>("a",2),new ProductionPlanner.Step<>("b",1)),3),
                new ProductionPlanner.Block<>(List.of(new ProductionPlanner.Step<>("a",4)),2));
        var cursor=new ProductionCursor<>(blocks);
        var remaining=new HashMap<String,Long>(Map.of("a",14L,"b",3L));
        while(!cursor.done()) {
            assertEquals(remaining,cursor.remainingCounts());
            String current=cursor.current();remaining.compute(current,(k,v)->v==1?null:v-1);cursor.accepted(current);
        }
        assertTrue(cursor.remainingCounts().isEmpty());
    }
    @Test void saveRestoreAtEveryBoundaryAndNeverAdvanceOnFailedPush() {
        var program=List.of(new ProductionPlanner.Block<>(List.of(new ProductionPlanner.Step<>("a",2),new ProductionPlanner.Step<>("b",1)),3),
                new ProductionPlanner.Block<>(List.of(new ProductionPlanner.Step<>("c",4)),1));
        var cursor=new ProductionCursor<>(program); var sequence=new ArrayList<String>();
        while(!cursor.done()) {
            var position=cursor.position(); var reloaded=new ProductionCursor<>(program); reloaded.restore(position);
            assertEquals(cursor.current(),reloaded.current());
            assertThrows(IllegalStateException.class,()->reloaded.accepted("wrong")); assertEquals(position,reloaded.position());
            sequence.add(cursor.current()); reloaded.accepted(cursor.current()); cursor=reloaded;
        }
        assertEquals(List.of("a","a","b","a","a","b","a","a","b","c","c","c","c"),sequence);
        var reload=new ProductionCursor<>(program); reload.restore(cursor.position()); assertTrue(reload.done());
        assertThrows(IllegalArgumentException.class,()->reload.restore(new ProductionCursor.Position(1,0,0,4)));
    }
}
