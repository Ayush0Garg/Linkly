package com.ayush.shortener.link;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.ayush.shortener.redis.RedisGateway;
import com.ayush.shortener.redis.RedisProperties;
import com.ayush.shortener.redis.RedisTestServer;
import com.ayush.shortener.redis.RequiresRedis;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The cache's storage format and lifetimes, against a real Redis. */
@RequiresRedis
class LinkCacheTest {

    static final Duration LINK_TTL = Duration.ofMinutes(15);
    static final Duration MISS_TTL = Duration.ofSeconds(30);

    RedisGateway redis;
    LinkCache cache;
    String code;
    Clock clock = Clock.system(ZoneOffset.UTC);

    static RedisProperties props(String url) {
        return new RedisProperties(url, false, Duration.ofMillis(200), Duration.ofSeconds(2), "test:" + System.nanoTime() + ":",
                new RedisProperties.Cache(LINK_TTL, MISS_TTL));
    }

    @BeforeEach
    void setUp() {
        redis = new RedisGateway(props(RedisTestServer.get().url()), new SimpleMeterRegistry());
        redis.start();
        cache = new LinkCache(redis, clock, props("ignored"));
        code = "linkly-t" + System.nanoTime();
    }

    @AfterEach
    void tearDown() {
        cache.stop();
        redis.stop();
    }

    long pttl() {
        return redis.call(c -> c.pttl(redis.key("link:" + code)), -3L);
    }

    @Test
    void anUnknownCodeIsAMissNotAnError() {
        assertThat(cache.get(code)).isInstanceOf(LinkCache.Cached.Miss.class);
    }

    @Test
    void aLiveLinkRoundTripsWithItsIdUrlAndExpiry() {
        cache.putFound(code, new ResolvedLink(1042, "https://example.com/landing?a=1"), null);
        assertThat(cache.get(code)).isEqualTo(new LinkCache.Cached.Found(new ResolvedLink(1042, "https://example.com/landing?a=1"), 0));

        Instant expiry = Instant.now().plus(Duration.ofHours(2));
        cache.putFound(code, new ResolvedLink(7, "https://example.com/x"), expiry);
        assertThat(cache.get(code)).isEqualTo(new LinkCache.Cached.Found(new ResolvedLink(7, "https://example.com/x"), expiry.toEpochMilli()));
    }

    @Test
    void urlsContainingTheSeparatorSurviveIntact() {
        String url = "https://example.com/a|b|c?x=1|2#f|g";
        cache.putFound(code, new ResolvedLink(5, url), null);
        assertThat(((LinkCache.Cached.Found) cache.get(code)).link().longUrl()).isEqualTo(url);
    }

    @Test
    void theMaximumLengthUrlSurvivesToo() {
        String url = "https://example.com/" + "a".repeat(8192 - 20);
        cache.putFound(code, new ResolvedLink(5, url), null);
        assertThat(((LinkCache.Cached.Found) cache.get(code)).link().longUrl()).hasSize(8192).isEqualTo(url);
    }

    @Test
    void noSuchCodeAndGoneAreRememberedBriefly() {
        cache.putAbsent(code);
        assertThat(cache.get(code)).isInstanceOf(LinkCache.Cached.Absent.class);
        assertThat(pttl()).isBetween(MISS_TTL.toMillis() - 2_000, MISS_TTL.toMillis());

        cache.putGone(code);
        assertThat(cache.get(code)).isInstanceOf(LinkCache.Cached.Gone.class);
        assertThat(pttl()).isBetween(MISS_TTL.toMillis() - 2_000, MISS_TTL.toMillis());
    }

    @Test
    void aLiveLinkLivesAboutTheConfiguredTimeWithJitterSoEntriesDoNotAllExpireTogether() {
        long low = (long) (LINK_TTL.toMillis() * 0.9) - 2_000;
        long high = (long) (LINK_TTL.toMillis() * 1.1);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i < 30; i++) {
            cache.putFound(code, new ResolvedLink(1, "https://example.com"), null);
            long ttl = pttl();
            assertThat(ttl).isBetween(low, high);
            seen.add(ttl / 5_000); // bucket by 5 s
        }
        assertThat(seen.size()).as("TTLs should vary").isGreaterThan(1);
    }

    @Test
    void anEntryNeverOutlivesTheLinkItDescribes() {
        cache.putFound(code, new ResolvedLink(1, "https://example.com"), Instant.now().plusSeconds(5));
        assertThat(pttl()).isBetween(1L, 5_000L);
    }

    @Test
    void aLinkThatHasAlreadyExpiredIsNotCachedAtAll() {
        cache.putFound(code, new ResolvedLink(1, "https://example.com"), Instant.now().minusSeconds(1));
        assertThat(cache.get(code)).isInstanceOf(LinkCache.Cached.Miss.class);
    }

    @Test
    void evictRemovesTheEntry() {
        cache.putFound(code, new ResolvedLink(1, "https://example.com"), null);
        cache.evict(code);
        assertThat(cache.get(code)).isInstanceOf(LinkCache.Cached.Miss.class);
    }

    @Test
    void evictLaterRemovesItAfterTheDelay() {
        cache.putFound(code, new ResolvedLink(1, "https://example.com"), null);
        cache.evictLater(code, Duration.ofMillis(200));
        assertThat(cache.get(code)).isInstanceOf(LinkCache.Cached.Found.class);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(cache.get(code)).isInstanceOf(LinkCache.Cached.Miss.class));
    }

    @Test
    void anEntryItCannotReadIsTreatedAsNoEntry() {
        for (String junk : new String[] {"garbage", "F|", "F|abc|0|https://x", "F|1|zzz|https://x", "F|1", "", "X|1|0|u"}) {
            redis.call(c -> c.set(redis.key("link:" + code), junk), null);
            assertThat(cache.get(code)).as("entry: '%s'", junk).isInstanceOf(LinkCache.Cached.Miss.class);
        }
    }

    @Test
    void withRedisOffEveryOperationIsAQuietNoOp() {
        RedisGateway off = new RedisGateway(props(""), new SimpleMeterRegistry());
        off.start();
        LinkCache offCache = new LinkCache(off, clock, props(""));
        assertThat(offCache.get(code)).isInstanceOf(LinkCache.Cached.Unavailable.class);
        offCache.putFound(code, new ResolvedLink(1, "https://example.com"), null); // must not throw
        offCache.putAbsent(code);
        offCache.putGone(code);
        offCache.evict(code);
        offCache.evictLater(code, Duration.ofMillis(10));
        assertThat(offCache.get(code)).isInstanceOf(LinkCache.Cached.Unavailable.class);
        offCache.stop();
    }

    @Test
    void anEvictionThatCouldNotReachRedisIsRetriedWhenItIsBackAndTheStaleEntryDisappears() {
        RedisTestServer server = RedisTestServer.get();
        cache.putFound(code, new ResolvedLink(1, "https://example.com/old"), null);

        server.pause();                  // partitioned away: the data is still there, but unreachable
        try {
            // a few commands time out in a row, and the gateway flips to DOWN...
            assertThat(cache.get(code)).isInstanceOf(LinkCache.Cached.Unavailable.class);
            RedisTestServer.untilGatewayNotices(redis, () -> cache.get(code));
            // ...so this eviction is refused locally and never reaches Redis: it has to be remembered
            cache.evict(code);
            assertThat(cache.pendingEvictionCount()).isEqualTo(1);
        } finally {
            server.resume();
        }
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            redis.probe();
            assertThat(redis.mode()).isEqualTo(RedisGateway.Mode.UP);
        });
        assertThat(cache.get(code)).as("the stale entry survived the partition").isInstanceOf(LinkCache.Cached.Found.class);

        cache.drainPendingEvictions();   // in the app this runs every probe-interval

        assertThat(cache.pendingEvictionCount()).isZero();
        assertThat(cache.get(code)).isInstanceOf(LinkCache.Cached.Miss.class);
    }

    @Test
    void whenRedisDiesTheCacheReportsUnavailableInsteadOfThrowing() {
        RedisTestServer server = RedisTestServer.get();
        cache.putFound(code, new ResolvedLink(1, "https://example.com"), null);
        server.stop();
        try {
            assertThat(cache.get(code)).isInstanceOf(LinkCache.Cached.Unavailable.class);
            cache.putFound(code, new ResolvedLink(2, "https://example.com"), null); // must not throw
            cache.evict(code);
        } finally {
            server.ensureRunning();
        }
    }
}
