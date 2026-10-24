package com.aecraftroute.addon.route;

/**
 * Retry state for the authoritative target handshake that runs when the canvas is opened from a
 * crafting confirmation screen.
 *
 * <p>The server can only report {@code whatToCraft} once the confirmation plan exists, and it
 * sends no indication when it does not. An unanswered attempt therefore must be repeated rather
 * than treated as final, and the repetition must stop at a bounded attempt count so an absent plan
 * does not turn into an indefinite request loop.
 *
 * <p>Kept apart from the screen so the timing rules can be exercised without a client.
 */
public final class BeginRetryState {
    private long retryAt = -1;
    private int attempts;

    /** Records that an attempt was sent at {@code now}, scheduling the next one. */
    public void sent(long now) {
        attempts++;
        retryAt = now + RouteRequestRules.BEGIN_RETRY_FRAMES;
    }

    /** Records that the server answered with a target; no further attempt is scheduled. */
    public void answered() {
        retryAt = -1;
    }

    /** True once the attempt budget is spent; the caller reports the failure instead of retrying. */
    public boolean exhausted() {
        return attempts >= RouteRequestRules.BEGIN_MAX_ATTEMPTS;
    }

    /**
     * Reports whether another attempt is due now. Returns false when nothing is scheduled, when the
     * budget is spent, or before the scheduled tick. A granted attempt is not counted here; the
     * caller counts it by sending.
     */
    public boolean due(long now) {
        return retryAt >= 0 && !exhausted() && RouteRequestRules.beginRetryDue(now, retryAt);
    }

    public int attempts() {
        return attempts;
    }

    public long retryAt() {
        return retryAt;
    }
}
