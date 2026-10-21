package com.ayush.shortener.redis;

import java.util.List;

/**
 * A feature that behaves differently while Redis is off, so the startup banner can list it. Each feature that leans on
 * Redis contributes its own lines instead of the gateway hard-coding everyone's behaviour.
 */
public interface DegradedEffects {

    List<String> effects();
}
