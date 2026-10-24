package com.aecraftroute.addon.route;

/** Debounce and retry gate; a locally rejected request is never treated as submitted. */
public final class RouteProviderSearch {
    public static final long DELAY_NANOS=250_000_000L;
    private String submitted="";
    private boolean dirty;
    private long nextAttempt;
    public boolean matchesSubmitted(String query) {
        return RouteProviderFilter.normalize(submitted).equals(RouteProviderFilter.normalize(query));
    }
    public void changed(String query,long now) {
        dirty=!matchesSubmitted(query);nextAttempt=now+DELAY_NANOS;
    }
    public void attempted(String query,boolean accepted,long now) {
        if(accepted)submitted=RouteProviderFilter.boundedQuery(query);
        dirty=!accepted;nextAttempt=now+DELAY_NANOS;
    }
    public boolean due(long now) {return dirty && now-nextAttempt>=0;}
    public boolean dirty() {return dirty;}
}
