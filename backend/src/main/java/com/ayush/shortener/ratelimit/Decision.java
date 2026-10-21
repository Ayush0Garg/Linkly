package com.ayush.shortener.ratelimit;

/**
 * @param limit             the limit that was applied (the tighter one while Redis is off)
 * @param remaining         requests left in the current window
 * @param retryAfterSeconds when denied, how long until a request would be allowed
 * @param shared            whether the count was kept in Redis (all instances) or in this process
 */
public record Decision(boolean allowed, int limit, long remaining, long retryAfterSeconds, boolean shared) {

    public static Decision unlimited() {
        return new Decision(true, 0, 0, 0, false);
    }
}
