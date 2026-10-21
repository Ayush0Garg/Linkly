package com.ayush.shortener.link;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import com.ayush.shortener.redis.RedisGateway;
import com.ayush.shortener.redis.RedisProperties;
import io.lettuce.core.SetArgs;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The redirect cache in Redis: code -> where it goes. Three kinds of entry, in one compact string:
 * <pre>
 *   F|{linkId}|{expiresAtMillis, 0 = never}|{url}   a live link
 *   N                                                no such code (so probing random codes does not hammer Postgres)
 *   G                                                deactivated or expired
 * </pre>
 * Every method is safe to call whether or not Redis is there: when it is off or down the answer is "unavailable" and
 * the caller reads Postgres instead. Nothing here ever throws into a redirect.
 */
@Component
public class LinkCache {

    /** The cache's answer for a code. */
    public sealed interface Cached {
        /** A live link; {@code expiresAtMillis} is 0 when it never expires. */
        record Found(ResolvedLink link, long expiresAtMillis) implements Cached {
        }

        record Absent() implements Cached {
        }

        record Gone() implements Cached {
        }

        /** Redis is working and has nothing for this code. */
        record Miss() implements Cached {
        }

        /** Redis is off or down: skip the cache entirely. */
        record Unavailable() implements Cached {
        }
    }

    private static final Cached MISS = new Cached.Miss();
    private static final Cached UNAVAILABLE = new Cached.Unavailable();
    private static final Cached ABSENT = new Cached.Absent();
    private static final Cached GONE = new Cached.Gone();
    private static final String FALLBACK = new String("<redis unavailable>"); // compared by identity, never by value
    private static final double JITTER = 0.10;
    private static final int MAX_PENDING_EVICTIONS = 10_000;
    private static final Logger log = LoggerFactory.getLogger(LinkCache.class);

    /**
     * Evictions that could not reach Redis (it was off, down or partitioned away). A deactivation must not be undone by
     * a stale entry that is still sitting in a Redis we could not talk to, so these are retried as soon as it is back.
     */
    private final Set<String> pendingEvictions = ConcurrentHashMap.newKeySet();

    private final RedisGateway redis;
    private final Clock clock;
    private final Duration linkTtl;
    private final Duration missTtl;
    private final ScheduledExecutorService later = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "link-cache-evict");
        t.setDaemon(true);
        return t;
    });

    LinkCache(RedisGateway redis, Clock clock, RedisProperties props) {
        this.redis = redis;
        this.clock = clock;
        this.linkTtl = props.cache().linkTtl();
        this.missTtl = props.cache().missTtl();
    }

    @PreDestroy
    void stop() {
        later.shutdownNow();
    }

    private String key(String code) {
        return redis.key("link:" + code);
    }

    public Cached get(String code) {
        String value = redis.call(c -> c.get(key(code)), FALLBACK);
        if (value == FALLBACK) { // identity on purpose: Redis failed just now
            return UNAVAILABLE;
        }
        return value == null ? MISS : parse(value);
    }

    public void putFound(String code, ResolvedLink link, java.time.Instant expiresAt) {
        long expiresMillis = expiresAt == null ? 0 : expiresAt.toEpochMilli();
        long ttl = jittered(linkTtl);
        if (expiresMillis > 0) {
            // never outlive the link itself
            ttl = Math.min(ttl, expiresMillis - clock.millis());
        }
        if (ttl < 1) {
            return; // already expired: nothing worth keeping
        }
        write(code, "F|" + link.linkId() + "|" + expiresMillis + "|" + link.longUrl(), ttl);
    }

    public void putAbsent(String code) {
        write(code, "N", missTtl.toMillis());
    }

    public void putGone(String code) {
        write(code, "G", missTtl.toMillis());
    }

    public void evict(String code) {
        Long removed = redis.call(c -> c.del(key(code)), null); // null = Redis could not be reached
        if (removed == null) {
            if (pendingEvictions.size() < MAX_PENDING_EVICTIONS) {
                pendingEvictions.add(code);
            } else {
                log.warn("Too many cache evictions are waiting for Redis; entries age out by TTL ({}) instead", linkTtl);
            }
        }
    }

    /** Retries evictions that failed while Redis was away. Cheap when there are none. */
    @Scheduled(fixedDelayString = "${app.redis.probe-interval}")
    public void drainPendingEvictions() {
        if (pendingEvictions.isEmpty() || !redis.available()) {
            return;
        }
        for (String code : java.util.List.copyOf(pendingEvictions)) {
            Long removed = redis.call(c -> c.del(key(code)), null);
            if (removed == null) {
                return; // Redis went away again; the rest stay queued
            }
            pendingEvictions.remove(code);
        }
    }

    int pendingEvictionCount() {
        return pendingEvictions.size();
    }

    /**
     * A second eviction shortly after the first. It closes the one remaining race: a reader that loaded the old row just
     * before a change committed, and writes it into the cache just after the first eviction.
     */
    public void evictLater(String code, Duration delay) {
        try {
            later.schedule(() -> evict(code), delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            // the application is stopping: nothing left to protect
        }
    }

    private void write(String code, String value, long ttlMillis) {
        redis.call(c -> c.set(key(code), value, SetArgs.Builder.px(ttlMillis)), null);
    }

    /** ttl +/- 10%, so a burst of entries created together does not all expire (and reload) together. */
    private static long jittered(Duration ttl) {
        double factor = 1 - JITTER + ThreadLocalRandom.current().nextDouble() * 2 * JITTER;
        return Math.max(1, (long) (ttl.toMillis() * factor));
    }

    static Cached parse(String value) {
        try {
            if (value.equals("N")) {
                return ABSENT;
            }
            if (value.equals("G")) {
                return GONE;
            }
            if (value.startsWith("F|")) {
                int first = value.indexOf('|', 2);
                int second = value.indexOf('|', first + 1);
                long id = Long.parseLong(value.substring(2, first));
                long expires = Long.parseLong(value.substring(first + 1, second));
                return new Cached.Found(new ResolvedLink(id, value.substring(second + 1)), expires); // the url may contain '|'
            }
        } catch (NumberFormatException | StringIndexOutOfBoundsException corrupt) {
            // fall through: an entry we cannot read is the same as no entry
        }
        return MISS;
    }
}
