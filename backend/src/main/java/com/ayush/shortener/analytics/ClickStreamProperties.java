package com.ayush.shortener.analytics;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled      false: never use the stream, even with Redis up (clicks then use the in-process queue only)
 * @param key          the stream's name (the Redis key prefix is added)
 * @param group        the consumer group every instance joins, so each click goes to exactly one of them
 * @param maxLength    safety cap: roughly this many entries are kept if the consumers fall far behind
 * @param claimIdle    an entry delivered to a consumer that has not acknowledged it for this long is taken over by
 *                     another one (the first consumer is presumed dead)
 * @param claimInterval how often to look for such entries
 * @param pollInterval  how long to wait before asking again when the stream was empty
 * @param maxAttempts   after this many failed attempts to store an entry it goes to the dead-letter stream
 */
@ConfigurationProperties("app.analytics.stream")
public record ClickStreamProperties(boolean enabled, String key, String group, long maxLength, Duration claimIdle,
                                    Duration claimInterval, Duration pollInterval, int maxAttempts) {
}
