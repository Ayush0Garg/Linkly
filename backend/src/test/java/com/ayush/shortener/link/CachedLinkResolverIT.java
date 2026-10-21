package com.ayush.shortener.link;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.ayush.shortener.analytics.ClickRecorder;
import com.ayush.shortener.common.ApiException;
import com.ayush.shortener.redis.RedisGateway;
import com.ayush.shortener.redis.RedisTestServer;
import com.ayush.shortener.redis.RequiresRedis;
import com.ayush.shortener.user.User;
import com.ayush.shortener.user.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The redirect lookup with a real Postgres and a real Redis, including Redis dying in the middle of a test. */
@RequiresRedis
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class CachedLinkResolverIT {

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("app.redis.url", () -> RedisTestServer.get().url());
        registry.add("app.redis.key-prefix", RedisTestServer::uniquePrefix);
    }

    @Autowired CachedLinkResolver resolver;
    @Autowired RedisGateway redis;
    @Autowired UserRepository users;
    @Autowired MockMvc mvc;
    @Autowired ClickRecorder recorder;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry metrics;
    @MockitoSpyBean LinkService links;

    long userId;

    @BeforeEach
    void setUp() {
        RedisTestServer.get().ensureRunning();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            redis.probe();
            assertThat(redis.mode()).isEqualTo(RedisGateway.Mode.UP);
        });
        userId = users.save(new User("sub-" + System.nanoTime(), "c" + System.nanoTime() + "@example.com", "C", null, Instant.now())).getId();
        Mockito.clearInvocations(links);
    }

    @AfterEach
    void restoreRedis() {
        RedisTestServer.get().ensureRunning();
    }

    Link newLink() {
        return links.create(userId, new CreateLinkCommand("https://example.com/landing", null, null));
    }

    String cached(String code) {
        return redis.call(c -> c.get(redis.key("link:" + code)), "<unavailable>");
    }

    double count(String result) {
        return metrics.counter("cache.link", "result", result).count();
    }

    // ---- the happy path

    @Test
    void theFirstLookupReadsPostgresAndTheNextOnesComeFromRedis() {
        Link link = newLink();
        double hits = count("hit");
        double misses = count("miss");

        ResolvedLink first = resolver.resolve(link.getCode());
        ResolvedLink second = resolver.resolve(link.getCode());
        ResolvedLink third = resolver.resolve(link.getCode());

        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        assertThat(first.longUrl()).isEqualTo("https://example.com/landing");
        verify(links, times(1)).lookup(link.getCode());   // one database read for three redirects
        assertThat(count("miss") - misses).isEqualTo(1);
        assertThat(count("hit") - hits).isEqualTo(2);
        assertThat(cached(link.getCode())).startsWith("F|" + link.getId() + "|0|https://example.com/landing");
    }

    @Test
    void redirectsServedFromTheCacheAreStillCountedAsClicks() throws Exception {
        Link link = newLink();
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/" + link.getCode()).header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0"))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "https://example.com/landing"));
        }
        verify(links, times(1)).lookup(link.getCode()); // the 2nd and 3rd never touched the database...
        // ...and all three still counted. With Redis up, clicks reach the database through the stream, asynchronously.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            recorder.flush(); // anything that had to be kept in memory instead
            assertThat(jdbc.queryForObject("SELECT coalesce(sum(clicks + bot_clicks), 0) FROM link_daily_stats WHERE link_id = ?", Long.class, link.getId()))
                    .isEqualTo(3L);
        });
    }

    // ---- changes must show up at once

    @Test
    void deactivatingTakesEffectImmediatelyEvenThoughTheLinkWasCached() throws Exception {
        Link link = newLink();
        resolver.resolve(link.getCode());
        assertThat(cached(link.getCode())).startsWith("F|");

        links.deactivate(userId, link.getId());

        assertThat(cached(link.getCode())).isNull();   // evicted after the commit
        assertThatThrownBy(() -> resolver.resolve(link.getCode()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(410));
        mvc.perform(get("/" + link.getCode())).andExpect(status().isGone());
        assertThat(cached(link.getCode())).isEqualTo("G"); // and the new truth is remembered
    }

    @Test
    void theSecondEvictionCatchesAReaderThatLoadedTheOldRowJustBeforeTheChange() {
        Link link = newLink();
        links.deactivate(userId, link.getId());
        // simulate the straggler: a reader that read "active" before the commit writes it back after the first eviction
        redis.call(c -> c.set(redis.key("link:" + link.getCode()), "F|" + link.getId() + "|0|https://example.com/landing"), null);
        await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> assertThat(cached(link.getCode())).isNull());
    }

    @Test
    void noSuchCodeIsRememberedSoProbingDoesNotHammerThePostgres() {
        String code = "ghost-" + System.nanoTime();
        double negatives = count("negative");

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> resolver.resolve(code))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(404));
        }
        verify(links, times(1)).lookup(code);
        assertThat(count("negative") - negatives).isEqualTo(4);
        assertThat(cached(code)).isEqualTo("N");
    }

    @Test
    void creatingALinkForACodeThatWasJustAskedForWorksAtOnceNotAfterTheNegativeEntryExpires() {
        String alias = "later-" + System.nanoTime();
        assertThatThrownBy(() -> resolver.resolve(alias)).isInstanceOf(ApiException.class);
        assertThat(cached(alias)).isEqualTo("N");

        links.create(userId, new CreateLinkCommand("https://example.com/now-exists", alias, null));

        assertThat(resolver.resolve(alias).longUrl()).isEqualTo("https://example.com/now-exists");
    }

    // ---- expiry

    @Test
    void anExpiringLinkIsCachedOnlyUntilItExpiresAndThenAnswers410() throws Exception {
        Link link = links.create(userId, new CreateLinkCommand("https://example.com/short-lived", null, Instant.now().plusSeconds(2)));
        resolver.resolve(link.getCode());
        long ttl = redis.call(c -> c.pttl(redis.key("link:" + link.getCode())), -3L);
        assertThat(ttl).isBetween(1L, 2_000L);

        Thread.sleep(2_300);

        assertThatThrownBy(() -> resolver.resolve(link.getCode()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(410));
    }

    @Test
    void anEntryThatExpiredAfterItWasCachedIsRefusedWithoutAskingThePostgres() {
        String code = "stale-" + System.nanoTime();
        long past = System.currentTimeMillis() - 5_000;
        redis.call(c -> c.set(redis.key("link:" + code), "F|1|" + past + "|https://example.com"), null);
        assertThatThrownBy(() -> resolver.resolve(code))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(410));
        verify(links, Mockito.never()).lookup(code);
    }

    @Test
    void aCorruptEntryIsIgnoredAndRepairedFromThePostgres() {
        Link link = newLink();
        redis.call(c -> c.set(redis.key("link:" + link.getCode()), "garbage"), null);
        assertThat(resolver.resolve(link.getCode()).longUrl()).isEqualTo("https://example.com/landing");
        assertThat(cached(link.getCode())).startsWith("F|");
    }

    // ---- Redis failing

    @Test
    void whenRedisDiesRedirectsKeepWorkingFromPostgresAndWhenItReturnsTheCacheResumes() {
        Link link = newLink();
        resolver.resolve(link.getCode());
        double unavailable = count("unavailable");
        RedisTestServer server = RedisTestServer.get();

        server.stop();
        for (int i = 0; i < 3; i++) {
            assertThat(resolver.resolve(link.getCode()).longUrl()).isEqualTo("https://example.com/landing"); // no exception, no 500
        }
        assertThat(redis.mode()).isEqualTo(RedisGateway.Mode.DOWN);
        assertThat(count("unavailable") - unavailable).isGreaterThanOrEqualTo(2);

        // writes that need to invalidate the cache also carry on while Redis is away
        Link other = newLink();
        links.deactivate(userId, other.getId());

        server.ensureRunning();
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            redis.probe();
            assertThat(redis.mode()).isEqualTo(RedisGateway.Mode.UP);
        });
        resolver.resolve(link.getCode());                       // repopulates...
        Mockito.clearInvocations(links);
        resolver.resolve(link.getCode());                       // ...and is served from cache again
        verify(links, Mockito.never()).lookup(link.getCode());
    }

    @Autowired LinkCache linkCache;

    @Test
    void aLinkDeactivatedWhileRedisWasUnreachableIsNotServedFromTheStaleEntryOnceItIsBack() {
        Link link = newLink();
        resolver.resolve(link.getCode());                 // cached as live
        RedisTestServer server = RedisTestServer.get();

        server.pause();                                   // a partition: Redis keeps its data but cannot be reached
        try {
            // a redirect during the partition still works (served from the database) and notices Redis is unreachable
            assertThat(resolver.resolve(link.getCode()).longUrl()).isEqualTo("https://example.com/landing");
            RedisTestServer.untilGatewayNotices(redis, () -> resolver.resolve(link.getCode())); // timeouts add up to "down"
            assertThat(redis.mode()).isEqualTo(RedisGateway.Mode.DOWN);
            links.deactivate(userId, link.getId());       // so this eviction cannot be delivered
        } finally {
            server.resume();
        }
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            redis.probe();
            assertThat(redis.mode()).isEqualTo(RedisGateway.Mode.UP);
        });

        // the retry runs on a schedule; once it has, the stale "live" entry is gone and the database's 410 wins
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            linkCache.drainPendingEvictions();
            assertThatThrownBy(() -> resolver.resolve(link.getCode()))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(410));
        });
    }

    // ---- many at once

    @Test
    void aBurstOfRequestsForOneColdCodeCausesASingleDatabaseRead() throws Exception {
        Link link = newLink();
        redis.call(c -> c.del(redis.key("link:" + link.getCode())), 0L);
        Mockito.clearInvocations(links);
        // slow the database down so that the burst really overlaps
        Mockito.doAnswer(invocation -> {
            Thread.sleep(300);
            return invocation.callRealMethod();
        }).when(links).lookup(link.getCode());

        int threads = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ResolvedLink>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<ResolvedLink> task = () -> {
                go.await();
                return resolver.resolve(link.getCode());
            };
            results.add(pool.submit(task));
        }
        go.countDown();
        for (Future<ResolvedLink> result : results) {
            assertThat(result.get().longUrl()).isEqualTo("https://example.com/landing");
        }
        pool.shutdown();

        verify(links, times(1)).lookup(link.getCode());
    }
}
