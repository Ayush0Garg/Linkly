package com.ayush.shortener.ratelimit;

public class RateLimitExceededException extends RuntimeException {

    private final transient Decision decision;
    private final String policy;

    public RateLimitExceededException(String policy, Decision decision) {
        super("Rate limit exceeded for " + policy);
        this.policy = policy;
        this.decision = decision;
    }

    public Decision decision() {
        return decision;
    }

    public String policy() {
        return policy;
    }
}
