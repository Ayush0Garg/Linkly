package com.ayush.shortener.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.ayush.shortener.config.AppProperties;
import com.ayush.shortener.redis.RedisGateway;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Token-bucket limits, one named policy at a time.
 *
 * <p>With Redis available a shared policy is counted in Redis, so every instance of the app draws from the same bucket.
 * With Redis off, down, or for a per-instance policy, it is counted in this process, and while Redis is off the limit is the
 * policy's tighter {@code degraded-limit}. The caller never sees Redis trouble: a failing Redis simply moves the check
 * onto the local, stricter path.
 *
 * <p>Whatever the limit is counted against (an IP address, an email, a user id) is hashed first, so neither Redis keys nor
 * logs ever hold a raw address or email.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final int DEFAULT_MAX_LOCAL_BUCKETS = 200_000;

    private record LocalBucket(Bucket bucket, long idleAfterNanos) {
    }

    private final RateLimitProperties props;
    private final RedisGateway redis;
    private final MeterRegistry metrics;
    private final byte[] hashKey;
    private final Map<String, LocalBucket> local = new ConcurrentHashMap<>();
    private final Map<String, Long> lastUsedNanos = new ConcurrentHashMap<>();
    private volatile LettuceBasedProxyManager<String> shared;
    private volatile StatefulRedisConnection<String, byte[]> sharedConnection;
    int maxLocalBuckets = DEFAULT_MAX_LOCAL_BUCKETS; // a field only so a test can use a small cap
    private final ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rate-limit-janitor");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public RateLimiter(RateLimitProperties props, AppProperties app, RedisGateway redis, MeterRegistry metrics) {
        this(props, app.auth().jwtSecret(), redis, metrics);
    }

    public RateLimiter(RateLimitProperties props, String secret, RedisGateway redis, MeterRegistry metrics) {
        this.props = props;
        this.redis = redis;
        this.metrics = metrics;
        // A key of its own, derived from the application secret, so hashes cannot be reproduced without it.
        this.hashKey = hmac(secret.getBytes(StandardCharsets.UTF_8), "rate-limit-subjects");
        janitor.scheduleWithFixedDelay(this::sweepLocal, 30, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        janitor.shutdownNow();
        dropSharedConnection();
    }

    public boolean enabled() {
        return props.enabled();
    }

    /** Counts one request against {@code subject} (an IP key, user id or email) under the named policy. */
    public Decision check(String policyName, String subject) {
        if (!props.enabled()) {
            return Decision.unlimited();
        }
        RateLimitProperties.Policy policy = props.policy(policyName);
        boolean redisUp = redis.available();
        int limit = redisUp ? policy.limit() : policy.degradedLimit(); // tighter while Redis is off
        String subjectHash = hash(subject);

        Decision decision = null;
        if (redisUp && policy.shared()) {
            decision = checkShared(policyName, policy, limit, subjectHash);
        }
        if (decision == null) {
            decision = checkLocal(policyName, policy, redis.available() ? limit : policy.degradedLimit(), subjectHash);
        }
        metrics.counter("ratelimit.requests", "policy", policyName, "result", decision.allowed() ? "allowed" : "denied",
                "store", decision.shared() ? "redis" : "local").increment();
        return decision;
    }

    /** Like {@link #check} but throws when the request is over the limit. */
    public Decision require(String policyName, String subject) {
        Decision decision = check(policyName, subject);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(policyName, decision);
        }
        return decision;
    }

    // ------------------------------------------------------------------ shared (Redis)

    /** Null means "Redis could not be used just now": the caller falls back to the local path. */
    private Decision checkShared(String policyName, RateLimitProperties.Policy policy, int limit, String subjectHash) {
        try {
            LettuceBasedProxyManager<String> manager = sharedManager();
            // The limit is part of the key: change the configuration and fresh buckets are used, rather than old ones
            // that were created with the previous numbers.
            String key = redis.key("rl:" + policyName + ":" + limit + ":" + subjectHash);
            ConsumptionProbe probe = manager.builder().build(key, () -> configuration(limit, policy.per()))
                    .tryConsumeAndReturnRemaining(1);
            return toDecision(probe, limit, true);
        } catch (RuntimeException e) {
            redis.reportFailure(e);
            dropSharedConnection(); // the next use opens a fresh connection instead of waiting for this one to recover
            return null;
        }
    }

    private synchronized void dropSharedConnection() {
        shared = null;
        StatefulRedisConnection<String, byte[]> old = sharedConnection;
        sharedConnection = null;
        if (old != null) {
            try {
                old.closeAsync();
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
    }

    private LettuceBasedProxyManager<String> sharedManager() {
        LettuceBasedProxyManager<String> manager = shared;
        if (manager == null) {
            synchronized (this) {
                if (shared == null) {
                    StatefulRedisConnection<String, byte[]> connection =
                            redis.client().connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
                    sharedConnection = connection;
                    shared = Bucket4jLettuce.casBasedBuilder(connection)
                            // forget a bucket a few seconds after it has refilled completely: idle keys do not pile up
                            .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(10)))
                            .build();
                }
                manager = shared;
            }
        }
        return manager;
    }

    // ------------------------------------------------------------------ local (this process)

    private Decision checkLocal(String policyName, RateLimitProperties.Policy policy, int limit, String subjectHash) {
        String key = policyName + ":" + limit + ":" + subjectHash;
        LocalBucket entry = local.computeIfAbsent(key, k -> new LocalBucket(
                Bucket.builder().addLimit(l -> l.capacity(limit).refillGreedy(limit, policy.per())).build(),
                Math.max(policy.per().toNanos() * 2, Duration.ofMinutes(1).toNanos())));
        lastUsedNanos.put(key, System.nanoTime());
        return toDecision(entry.bucket().tryConsumeAndReturnRemaining(1), limit, false);
    }

    /** Forgets idle buckets, and keeps the map bounded however many different clients show up. */
    void sweepLocal() {
        sweepLocal(System.nanoTime());
    }

    void sweepLocal(long now) {
        for (Iterator<Map.Entry<String, LocalBucket>> it = local.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, LocalBucket> e = it.next();
            Long used = lastUsedNanos.get(e.getKey());
            if (used == null || now - used > e.getValue().idleAfterNanos()) {
                it.remove();
                lastUsedNanos.remove(e.getKey());
            }
        }
        if (local.size() > maxLocalBuckets) {
            // Under a flood of distinct clients, drop the least recently used quarter.
            lastUsedNanos.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .limit(Math.max(1, maxLocalBuckets / 4))
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(k -> {
                        local.remove(k);
                        lastUsedNanos.remove(k);
                    });
            log.warn("Local rate-limit table was over {} entries; dropped the least recently used", maxLocalBuckets);
        }
    }

    int localBucketCount() {
        return local.size();
    }

    // ------------------------------------------------------------------ helpers

    private static BucketConfiguration configuration(int limit, Duration per) {
        return BucketConfiguration.builder().addLimit(l -> l.capacity(limit).refillGreedy(limit, per)).build();
    }

    private static Decision toDecision(ConsumptionProbe probe, int limit, boolean shared) {
        long retry = probe.isConsumed() ? 0 : Math.max(1, (long) Math.ceil(probe.getNanosToWaitForRefill() / 1_000_000_000.0));
        return new Decision(probe.isConsumed(), limit, probe.getRemainingTokens(), retry, shared);
    }

    private String hash(String subject) {
        byte[] digest = hmac(hashKey, subject == null ? "" : subject);
        return HexFormat.of().formatHex(digest, 0, 12); // 96 bits is plenty to tell clients apart
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
