package com.aecraftroute.addon.route;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouteSynchronizedPlanTest {
    public static class Job {public String output="最终产物 × 2048";}
    public static class Summary {public Job getJob() {return new Job();}}
    public static class MissingJob {public Job getJob() {return null;}}
    public static class Broken {public Job getJob() {throw new IllegalStateException();}}
    @Test void finalOutputComesFromSynchronizedJob() {assertEquals("最终产物 × 2048",RouteSynchronizedPlan.output(new Summary(),String.class));}
    @Test void pendingOrAbsentExtensionDoesNotInventRoot() {
        assertNull(RouteSynchronizedPlan.output(null,String.class));
        assertNull(RouteSynchronizedPlan.output(new Object(),String.class));
        assertNull(RouteSynchronizedPlan.output(new MissingJob(),String.class));
    }
    @Test void optionalExtensionFailureDoesNotCrashScreen() {assertNull(RouteSynchronizedPlan.output(new Broken(),String.class));}
    @Test void malformedOutputIsNotUsedAsAnotherType() {assertNull(RouteSynchronizedPlan.output(new Summary(),Integer.class));}
}
