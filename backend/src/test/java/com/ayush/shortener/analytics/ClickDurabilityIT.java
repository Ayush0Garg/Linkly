package com.ayush.shortener.analytics;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.ayush.shortener.link.CreateLinkCommand;
import com.ayush.shortener.link.Link;
import com.ayush.shortener.link.LinkService;
import com.ayush.shortener.redis.RedisGateway;
import com.ayush.shortener.redis.RedisTestServer;
import com.ayush.shortener.redis.RequiresRedis;
import com.ayush.shortener.user.User;
import com.ayush.shortener.user.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole path with a real Redis and a real Postgres: redirect -> stream -> consumer -> database. The point of the
 * stream is that clicks survive things that used to lose them, so most of these tests are about things going wrong.
 */
@RequiresRedis
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class ClickDurabilityIT {

    static final String FIREFOX = "Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0";

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("app.redis.url", () -> RedisTestServer.get().url());
        registry.add("app.redis.key-prefix", RedisTestServer::uniquePrefix);
    }

    @Autowired MockMvc mvc;
    @Autowired ClickStream stream;
    @Autowired ClickRecorder recorder;
    @Autowired ClickWriter writer;
    @Autowired ClickEnricher enricher;
    @Autowired RedisGateway redis;
    @Autowired LinkService links;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry metrics;

    Link link;

    @BeforeEach
    void setUp() {
        RedisTestServer.get().ensureRunning();
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            redis.probe();
            assertThat(redis.mode()).isEqualTo(RedisGateway.Mode.UP);
        });
        stream.resume();
        stream.beforeAcknowledge = null;
        long userId = users.save(new User("sub-" + System.nanoTime(), "d" + System.nanoTime() + "@example.com", "D", null, Instant.now())).getId();
        link = links.create(userId, new CreateLinkCommand("https://example.com/durable", null, null));
    }

    @AfterEach
    void restore() {
        stream.resume();
        stream.beforeAcknowledge = null;
        RedisTestServer.get().ensureRunning();
    }

    void redirect(int times) throws Exception {
        for (int i = 0; i < times; i++) {
            mvc.perform(get("/" + link.getCode()).header("User-Agent", FIREFOX)).andExpect(status().isFound());
        }
    }

    long events() {
        return jdbc.queryForObject("SELECT count(*) FROM click_events WHERE link_id = ?", Long.class, link.getId());
    }

    long counted() {
        return jdbc.queryForObject("SELECT coalesce(sum(clicks + bot_clicks), 0) FROM link_daily_stats WHERE link_id = ?", Long.class, link.getId());
    }

    long streamLength() {
        return redis.call(c -> c.xlen(stream.streamKey()), -1L);
    }

    void awaitStored(long expected) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(events()).as("rows in click_events").isEqualTo(expected);
            assertThat(counted()).as("clicks in the daily totals").isEqualTo(expected);
        });
    }

    @Test
    void aRedirectTravelsThroughTheStreamAndIsStoredWithoutTheInProcessQueueEverSeeingIt() throws Exception {
        double added = metrics.counter("clicks.stream.added").count();
        redirect(5);

        awaitStored(5);
        assertThat(metrics.counter("clicks.stream.added").count() - added).isEqualTo(5);
        assertThat(recorder.flush()).as("nothing was kept in memory: the stream took every click").isZero();
        assertThat(streamLength()).isZero();
        assertThat(jdbc.queryForMap("SELECT browser, os, device, country FROM click_events WHERE link_id = ? LIMIT 1", link.getId()))
                .containsEntry("browser", "Firefox").containsEntry("os", "Linux").containsEntry("device", "desktop");
    }

    @Test
    void clicksPileUpSafelyWhileNothingIsConsumingAndAreAllStoredOnceItResumes() throws Exception {
        stream.pause();                       // as if the app were down or stuck: the consumer is not running
        redirect(12);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(streamLength()).isEqualTo(12));
        assertThat(events()).as("nothing stored yet, nothing lost either").isZero();

        stream.resume();
        awaitStored(12);
        assertThat(streamLength()).isZero();
    }

    @Test
    void aCrashBetweenStoringAndAcknowledgingDoesNotCountAnyClickTwice() throws Exception {
        double duplicatesBefore = metrics.counter("clicks.duplicates").count();
        java.util.concurrent.atomic.AtomicBoolean crashed = new java.util.concurrent.atomic.AtomicBoolean();
        stream.beforeAcknowledge = () -> {
            if (crashed.compareAndSet(false, true)) {
                throw new RedisGateway.RedisUnavailableException("the process died right after committing to Postgres");
            }
        };

        redirect(6);

        awaitStored(6);                        // delivered again after the "crash", but stored and counted once
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(streamLength()).isZero());
        assertThat(crashed).isTrue();
        assertThat(events()).isEqualTo(6);
        assertThat(counted()).isEqualTo(6);
        assertThat(metrics.counter("clicks.duplicates").count() - duplicatesBefore)
                .as("the redelivered clicks were recognised and skipped").isGreaterThanOrEqualTo(1);
    }

    @Test
    void aClickThatReachedBothTheStreamAndTheLocalQueueIsStillCountedOnce() throws Exception {
        // This is what a timed-out XADD that Redis nevertheless applied looks like: the stream has the click, and the caller,
        // told it failed, also wrote it locally. Same event id both times.
        PendingClick click = enricher.capture(link.getId(), Instant.now(), "203.0.113.5", FIREFOX, null);
        ClickRow row = enricher.finish(click);
        assertThat(stream.publish(click, () -> { })).isTrue();
        assertThat(writer.write(java.util.List.of(row))).isEqualTo(1);       // the "local" copy lands first

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(streamLength()).isZero());
        assertThat(events()).isEqualTo(1);
        assertThat(counted()).isEqualTo(1);
    }

    @Test
    void ifRedisDiesTrafficKeepsBeingRecordedAndAfterItReturnsEverythingIsStoredExactlyOnce() throws Exception {
        RedisTestServer server = RedisTestServer.get();
        redirect(3);
        awaitStored(3);

        server.stop();                                                      // outage
        redirect(5);                                                        // each of these must still be kept somewhere
        // the in-process queue is flushed by a schedule in the app; do it now so the test does not wait for it
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            recorder.flush();
            assertThat(events()).as("clicks during the outage were held in memory and written").isEqualTo(8);
        });

        server.ensureRunning();
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            redis.probe();
            assertThat(redis.mode()).isEqualTo(RedisGateway.Mode.UP);
        });
        redirect(4);                                                        // back on the stream

        awaitStored(12);
        assertThat(events()).isEqualTo(12);
        assertThat(counted()).isEqualTo(12);
    }

    @Test
    void theStreamMetricsReportWhatIsGoingOn() throws Exception {
        stream.pause();
        redirect(4);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(streamLength()).isEqualTo(4));
        stream.resume();
        awaitStored(4);
        assertThat(metrics.get("clicks.stream.consumed").counter().count()).isGreaterThanOrEqualTo(4);
        assertThat(metrics.find("clicks.stream.length").gauge()).isNotNull();
        assertThat(metrics.find("clicks.stream.pending").gauge()).isNotNull();
        assertThat(UUID.randomUUID()).isNotNull();
    }
}
