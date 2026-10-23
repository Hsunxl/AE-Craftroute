package com.ae2vm.addon.route;

/** Optional AE2CT extension. Never guesses the client target from server-only menu fields. */
public final class RouteSynchronizedPlan {
    private RouteSynchronizedPlan() {}
    public static Object job(Object summary) {
        if(summary==null)return null;
        try {return summary.getClass().getMethod("getJob").invoke(summary);}
        catch(ReflectiveOperationException | RuntimeException e) {return null;}
    }
    public static <T> T output(Object summary,Class<T> type) {
        Object job=job(summary);if(job==null)return null;
        try {return type.cast(job.getClass().getField("output").get(job));}
        catch(ReflectiveOperationException | RuntimeException e) {return null;}
    }
}
