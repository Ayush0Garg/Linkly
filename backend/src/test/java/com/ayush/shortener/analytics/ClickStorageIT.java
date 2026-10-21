package com.ayush.shortener.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.ayush.shortener.link.CreateLinkCommand;
import com.ayush.shortener.link.LinkService;
import com.ayush.shortener.user.User;
import com.ayush.shortener.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("embedded")
class ClickStorageIT {

    @Autowired ClickWriter writer;
    @Autowired PartitionManager partitions;
    @Autowired LinkService linkService;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    long linkId;

    @BeforeEach
    void link() {
        long userId = users.save(new User("sub-" + System.nanoTime(), "w" + System.nanoTime() + "@example.com", "W", null, Instant.now())).getId();
        linkId = linkService.create(userId, new CreateLinkCommand("https://example.com", null, null)).getId();
    }

    static ClickRow click(long linkId, Instant at, boolean bot) {
        return new ClickRow(UUID.randomUUID(), linkId, at, "IN", bot ? "bot" : "desktop", "Chrome", "Linux", "example.org",
                "0123456789abcdef0123456789abcdef", bot);
    }

    long events(long linkId) {
        return jdbc.queryForObject("SELECT count(*) FROM click_events WHERE link_id = ?", Long.class, linkId);
    }

    long[] daily(long linkId, LocalDate day) {
        return jdbc.queryForObject("SELECT clicks, bot_clicks FROM link_daily_stats WHERE link_id = ? AND day = ?",
                (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}, linkId, day);
    }

    boolean tableExists(String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, name));
    }

    @Test
    void writesRawRowsAndTheDailyRollupTogether() {
        Instant noon = Instant.parse("2026-10-14T12:00:00Z");
        writer.write(List.of(click(linkId, noon, false), click(linkId, noon.plusSeconds(5), false), click(linkId, noon.plusSeconds(9), true)));

        assertThat(events(linkId)).isEqualTo(3);
        assertThat(daily(linkId, LocalDate.of(2026, 10, 14))).containsExactly(2, 1);
    }

    @Test
    void successiveBatchesAccumulateInTheRollup() {
        Instant noon = Instant.parse("2026-10-14T12:00:00Z");
        writer.write(List.of(click(linkId, noon, false)));
        writer.write(List.of(click(linkId, noon, false), click(linkId, noon, false)));
        assertThat(daily(linkId, LocalDate.of(2026, 10, 14))).containsExactly(3, 0);
    }

    @Test
    void clicksAreBucketedByUtcDay() {
        writer.write(List.of(
                click(linkId, Instant.parse("2026-10-14T23:59:59Z"), false),
                click(linkId, Instant.parse("2026-10-15T00:00:00Z"), false)));
        assertThat(daily(linkId, LocalDate.of(2026, 10, 14))).containsExactly(1, 0);
        assertThat(daily(linkId, LocalDate.of(2026, 10, 15))).containsExactly(1, 0);
    }

    @Test
    void createsTheMonthlyPartitionOnDemand() {
        assertThat(tableExists("click_events_y2021m03")).isFalse();
        writer.write(List.of(click(linkId, Instant.parse("2021-03-15T10:00:00Z"), false)));

        assertThat(tableExists("click_events_y2021m03")).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM click_events_y2021m03 WHERE link_id = ?", Long.class, linkId)).isEqualTo(1);
    }

    @Test
    void ensureUpcomingCreatesCurrentAndNextTwoMonthsAndIsIdempotent() {
        partitions.ensureUpcoming();
        partitions.ensureUpcoming();
        YearMonth now = YearMonth.now(ZoneOffset.UTC);
        for (int i = 0; i <= 2; i++) {
            assertThat(tableExists(PartitionManager.tableName(now.plusMonths(i)))).as("month +%d", i).isTrue();
        }
    }

    @Test
    void retentionDropsOldRawPartitionsButKeepsRecentOnesAndTheRollups() {
        Instant old = Instant.parse("2019-06-10T10:00:00Z");
        writer.write(List.of(click(linkId, old, false), click(linkId, Instant.now(), false)));
        assertThat(tableExists("click_events_y2019m06")).isTrue();

        int dropped = partitions.dropExpired();

        assertThat(dropped).isGreaterThanOrEqualTo(1);
        assertThat(tableExists("click_events_y2019m06")).isFalse();
        assertThat(tableExists(PartitionManager.tableName(YearMonth.now(ZoneOffset.UTC)))).isTrue();
        // The raw old click is gone, the recent one remains, and the rollup still remembers the old day.
        assertThat(events(linkId)).isEqualTo(1);
        assertThat(daily(linkId, LocalDate.of(2019, 6, 10))).containsExactly(1, 0);

        // A new write for the dropped month recreates the partition rather than failing.
        writer.write(List.of(click(linkId, old, false)));
        assertThat(tableExists("click_events_y2019m06")).isTrue();
    }

    @Test
    void concurrentFlushesNeverLoseRollupCounts() throws Exception {
        int threads = 8;
        int perThread = 50;
        Instant noon = Instant.parse("2026-10-14T12:00:00Z");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                List<ClickRow> rows = new ArrayList<>();
                for (int i = 0; i < perThread; i++) {
                    rows.add(click(linkId, noon, false));
                }
                writer.write(rows);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(events(linkId)).isEqualTo(threads * perThread);
        assertThat(daily(linkId, LocalDate.of(2026, 10, 14))).containsExactly(threads * perThread, 0);
    }

    // ---- exactly-once: the same click delivered more than once

    static ClickRow withId(UUID id, long linkId, Instant at, boolean bot) {
        return new ClickRow(id, linkId, at, "IN", bot ? "bot" : "desktop", "Chrome", "Linux", null, "0123456789abcdef0123456789abcdef", bot);
    }

    @Test
    void theSameClickWrittenTwiceIsStoredAndCountedOnce() {
        ClickRow row = click(linkId, Instant.parse("2026-10-14T12:00:00Z"), false);
        assertThat(writer.write(List.of(row))).isEqualTo(1);
        assertThat(writer.write(List.of(row))).as("second delivery adds nothing").isZero();

        assertThat(events(linkId)).isEqualTo(1);
        assertThat(daily(linkId, LocalDate.of(2026, 10, 14))).containsExactly(1, 0);
    }

    @Test
    void aBatchMixingNewAndAlreadyStoredClicksCountsOnlyTheNewOnes() {
        Instant noon = Instant.parse("2026-10-14T12:00:00Z");
        ClickRow a = click(linkId, noon, false);
        ClickRow b = click(linkId, noon, true);
        ClickRow c = click(linkId, noon, false);

        assertThat(writer.write(List.of(a, b))).isEqualTo(2);
        assertThat(writer.write(List.of(b, c, a))).as("only c is new").isEqualTo(1);

        assertThat(events(linkId)).isEqualTo(3);
        assertThat(daily(linkId, LocalDate.of(2026, 10, 14))).containsExactly(2, 1); // a and c human, b a bot
    }

    @Test
    void theSameIdTwiceInOneBatchIsStoredOnce() {
        ClickRow row = click(linkId, Instant.parse("2026-10-14T12:00:00Z"), false);
        assertThat(writer.write(List.of(row, row, row))).isEqualTo(1);
        assertThat(events(linkId)).isEqualTo(1);
        assertThat(daily(linkId, LocalDate.of(2026, 10, 14))).containsExactly(1, 0);
    }

    @Test
    void manyWritersRacingOnTheSameBatchStoreAndCountEachClickExactlyOnce() throws Exception {
        int writers = 8;
        Instant noon = Instant.parse("2026-10-14T12:00:00Z");
        List<ClickRow> batch = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            batch.add(click(linkId, noon.plusMillis(i), i % 6 == 0)); // 10 bots, 50 humans
        }
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            results.add(pool.submit(() -> {
                start.await();
                return writer.write(batch); // every writer tries to store the very same 60 clicks
            }));
        }
        start.countDown();
        int totalInserted = 0;
        for (Future<Integer> result : results) {
            totalInserted += result.get();
        }
        pool.shutdown();

        assertThat(totalInserted).as("across all writers, each click was inserted exactly once").isEqualTo(60);
        assertThat(events(linkId)).isEqualTo(60);
        assertThat(daily(linkId, LocalDate.of(2026, 10, 14))).as("and counted exactly once").containsExactly(50, 10);
    }

    @Test
    void clicksWithUnknownDimensionsAreStoredToo() {
        ClickRow row = new ClickRow(UUID.randomUUID(), linkId, Instant.parse("2026-10-14T12:00:00Z"), null, "desktop", null, null, null,
                "0123456789abcdef0123456789abcdef", false);
        assertThat(writer.write(List.of(row))).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT country, browser, os, referrer_host FROM click_events WHERE link_id = ?", linkId)).containsOnlyKeys("country", "browser", "os", "referrer_host")
                .containsValue(null);
    }

    @Test
    void theEventIdIsStoredWithTheClickAndTheTimestampKeepsItsMilliseconds() {
        UUID id = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-14T12:34:56.789Z");
        writer.write(List.of(withId(id, linkId, at, false)));
        assertThat(jdbc.queryForObject("SELECT event_id FROM click_events WHERE link_id = ?", UUID.class, linkId)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT (extract(epoch from clicked_at) * 1000)::bigint FROM click_events WHERE link_id = ?", Long.class, linkId))
                .isEqualTo(at.toEpochMilli());
    }

    @Test
    void emptyBatchIsANoOp() {
        assertThat(writer.write(List.of())).isZero();
        assertThat(events(linkId)).isZero();
    }
}
