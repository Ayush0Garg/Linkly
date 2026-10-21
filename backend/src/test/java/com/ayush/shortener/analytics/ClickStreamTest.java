package com.ayush.shortener.analytics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The stream's delivery guarantees, against a real Redis that the tests can freeze, kill, and wipe. */
@RequiresRedis
class ClickStreamTest {

    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final ClickEnricher enricher = new ClickEnricher(new UserAgentClassifier(), ip -> java.util.Optional.of("IN"),
            new VisitorHasher(TestProps.props(10, 10)));
    final List<RowRecorder> writers = new ArrayList<>();
    RedisGateway redis;
    String prefix;

    /** A writer that remembers every id it was handed and can be told to fail. */
    static class RowRecorder {
        final ClickWriter writer = mock(ClickWriter.class);
        final List<List<UUID>> calls = new CopyOnWriteArrayList<>();
        final AtomicInteger failuresLeft = new AtomicInteger();

        RowRecorder() {
            when(writer.write(anyList())).thenAnswer(inv -> {
                List<ClickRow> rows = inv.getArgument(0);
                calls.add(rows.stream().map(ClickRow::eventId).toList());
                if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    throw new IllegalStateException("database is down");
                }
                return rows.size();
            });
        }

        void failNext(int times) {
            failuresLeft.set(times);
        }

        List<UUID> stored() {
            return calls.stream().flatMap(List::stream).toList();
        }
    }

    @BeforeEach
    void connect() {
        prefix = "stream-test-" + System.nanoTime() + ":";
        redis = new RedisGateway(new RedisProperties(RedisTestServer.get().url(), false, Duration.ofMillis(200), Duration.ofSeconds(2),
                prefix, new RedisProperties.Cache(Duration.ofMinutes(15), Duration.ofSeconds(30))), metrics);
        redis.start();
    }

    @AfterEach
    void disconnect() {
        redis.stop();
        RedisTestServer.get().ensureRunning();
    }

    ClickStreamProperties props(int maxAttempts, long maxLength) {
        return new ClickStreamProperties(true, "clicks", "analytics", maxLength, Duration.ofMillis(300), Duration.ZERO,
                Duration.ofMillis(50), maxAttempts);
    }

    ClickStream consumer(String name, RowRecorder writer, int batch, int maxAttempts) {
        writers.add(writer);
        return new ClickStream(redis, enricher, writer.writer, props(maxAttempts, 1_000_000), batch, metrics, name);
    }

    ClickStream consumer(String name, RowRecorder writer) {
        return consumer(name, writer, 100, 5);
    }

    PendingClick click() {
        return enricher.capture(1000, Instant.now(), "8.8.8.8", "Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0", null);
    }

    /** Publishes and waits until Redis has confirmed every one of them (counted, not measured by stream length: a running
     *  consumer may already be draining it, and other entries may be in there). */
    List<PendingClick> publish(ClickStream stream, int count) {
        double before = metrics.counter("clicks.stream.added").count();
        List<PendingClick> sent = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            PendingClick click = click();
            assertThat(stream.publish(click, () -> { throw new AssertionError("publish failed"); })).isTrue();
            sent.add(click);
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(
                () -> assertThat(metrics.counter("clicks.stream.added").count() - before).isEqualTo(count));
        return sent;
    }

    long length(ClickStream stream) {
        return redis.call(c -> c.xlen(stream.streamKey()), -1L);
    }

    long pending(ClickStream stream) {
        return redis.call(c -> c.xpending(stream.streamKey(), "analytics").getCount(), -1L);
    }

    long deadLetters(ClickStream stream) {
        return redis.call(c -> c.xlen(stream.deadLetterKey()), 0L);
    }

    // ---------------------------------------------------------------- the normal path

    @Test
    void publishedClicksAreStoredAndThenRemovedFromTheStream() {
        RowRecorder writer = new RowRecorder();
        ClickStream stream = consumer("a", writer);
        List<PendingClick> sent = publish(stream, 5);

        assertThat(stream.processOnce()).isEqualTo(5);

        assertThat(writer.stored()).containsExactlyInAnyOrderElementsOf(sent.stream().map(PendingClick::eventId).toList());
        assertThat(length(stream)).as("removed from the stream once stored").isZero();
        assertThat(pending(stream)).as("and acknowledged").isZero();
        assertThat(metrics.counter("clicks.stream.consumed").count()).isEqualTo(5);
        assertThat(metrics.counter("clicks.stream.added").count()).isEqualTo(5);
        assertThat(metrics.counter("clicks.written").count()).as("stored, counted under the one total used by both paths").isEqualTo(5);
        assertThat(metrics.counter("clicks.duplicates").count()).isZero();
    }

    @Test
    void storedRowsCarryTheParsedUserAgentAndTheOriginalFields() {
        ClickWriter writer = mock(ClickWriter.class);
        List<ClickRow> seen = new CopyOnWriteArrayList<>();
        when(writer.write(anyList())).thenAnswer(inv -> {
            List<ClickRow> rows = inv.getArgument(0);
            seen.addAll(rows);
            return rows.size();
        });
        ClickStream stream = new ClickStream(redis, enricher, writer, props(5, 1_000_000), 100, metrics, "a");
        PendingClick click = publish(stream, 1).get(0);

        stream.processOnce();

        ClickRow row = seen.get(0);
        assertThat(row.eventId()).isEqualTo(click.eventId());
        assertThat(row.browser()).isEqualTo("Firefox");
        assertThat(row.os()).isEqualTo("Linux");
        assertThat(row.device()).isEqualTo("desktop");
        assertThat(row.country()).isEqualTo("IN");
        assertThat(row.visitorHash()).isEqualTo(click.visitorHash());
        assertThat(row.at().toEpochMilli()).isEqualTo(click.at().toEpochMilli());
    }

    @Test
    void readsInBatchesOfTheConfiguredSize() {
        RowRecorder writer = new RowRecorder();
        ClickStream stream = consumer("a", writer, 2, 5);
        publish(stream, 5);

        int total = 0;
        while (length(stream) > 0) {
            total += stream.processOnce();
        }
        assertThat(total).isEqualTo(5);
        assertThat(writer.calls).extracting(List::size).containsExactly(2, 2, 1);
    }

    @Test
    void anEmptyStreamIsFine() {
        ClickStream stream = consumer("a", new RowRecorder());
        assertThat(stream.processOnce()).isZero();
        assertThat(stream.processOnce()).isZero();
    }

    // ---------------------------------------------------------------- nothing is lost

    @Test
    void aClickIsNotRemovedUntilItIsStoredAndIsRetriedAfterAFailure() {
        RowRecorder writer = new RowRecorder();
        ClickStream stream = consumer("a", writer);
        PendingClick click = publish(stream, 1).get(0);

        writer.failNext(1);
        stream.processOnce();                                   // the database "is down"
        assertThat(length(stream)).as("still in the stream").isEqualTo(1);
        assertThat(pending(stream)).as("waiting to be retried").isEqualTo(1);

        stream.processOnce();                                   // the database is back: the retry succeeds
        assertThat(length(stream)).isZero();
        assertThat(pending(stream)).isZero();
        assertThat(writer.calls).hasSize(2);
        assertThat(writer.calls.get(1)).as("the very same click, with the same id, delivered again").containsExactly(click.eventId());
    }

    @Test
    void ifTheAcknowledgementFailsAfterStoringTheClickIsDeliveredAgainWithTheSameId() {
        RowRecorder writer = new RowRecorder();
        java.util.concurrent.atomic.AtomicBoolean failAck = new java.util.concurrent.atomic.AtomicBoolean(true);
        ClickStream stream = new ClickStream(redis, enricher, writer.writer, props(5, 1_000_000), 100, metrics, "a") {
            @Override
            void acknowledge(List<String> ids) {
                if (failAck.getAndSet(false)) {
                    throw new RedisGateway.RedisUnavailableException("lost the connection right after the commit");
                }
                super.acknowledge(ids);
            }
        };
        PendingClick click = publish(stream, 1).get(0);

        try {
            stream.processOnce();
        } catch (RedisGateway.RedisUnavailableException expected) {
            // stored, but not acknowledged: a crash at the worst possible moment
        }
        assertThat(writer.stored()).containsExactly(click.eventId());
        assertThat(pending(stream)).isEqualTo(1);

        stream.processOnce();                                   // delivered again
        assertThat(writer.stored()).as("handed to the writer twice, with one id: the database keeps it once")
                .containsExactly(click.eventId(), click.eventId());
        assertThat(length(stream)).isZero();
        assertThat(pending(stream)).isZero();
    }

    @Test
    void entriesHeldByADeadConsumerAreTakenOverAfterTheIdleTime() throws Exception {
        RowRecorder dying = new RowRecorder();
        ClickStream a = consumer("instance-a", dying);
        PendingClick click = publish(a, 1).get(0);
        dying.failNext(1);
        a.processOnce();                                        // A read it, could not store it, and now "crashes"
        assertThat(pending(a)).isEqualTo(1);

        RowRecorder survivor = new RowRecorder();
        ClickStream b = consumer("instance-b", survivor);
        assertThat(b.processOnce()).as("too soon: A might still be working on it").isZero();
        assertThat(survivor.calls).isEmpty();

        Thread.sleep(400);                                      // longer than claim-idle (300 ms)
        assertThat(b.processOnce()).isEqualTo(1);
        assertThat(survivor.stored()).containsExactly(click.eventId());
        assertThat(length(b)).isZero();
        assertThat(pending(b)).isZero();
        assertThat(metrics.counter("clicks.stream.claimed").count()).isEqualTo(1);
    }

    @Test
    void twoConsumersShareTheWorkAndNoClickIsHandedOutTwice() {
        RowRecorder writerA = new RowRecorder();
        RowRecorder writerB = new RowRecorder();
        ClickStream a = consumer("a", writerA, 7, 5);
        ClickStream b = consumer("b", writerB, 7, 5);
        List<PendingClick> sent = publish(a, 40);

        int rounds = 0;
        while (length(a) > 0 && rounds++ < 50) {
            a.processOnce();
            b.processOnce();
        }
        Set<UUID> byA = new HashSet<>(writerA.stored());
        Set<UUID> byB = new HashSet<>(writerB.stored());
        assertThat(byA).as("disjoint").doesNotContainAnyElementsOf(byB);
        Set<UUID> all = new HashSet<>(byA);
        all.addAll(byB);
        assertThat(all).containsExactlyInAnyOrderElementsOf(sent.stream().map(PendingClick::eventId).toList());
        assertThat(byA).isNotEmpty();
        assertThat(byB).isNotEmpty();
    }

    // ---------------------------------------------------------------- things that must not block the rest

    @Test
    void anUnreadableEntryIsSetAsideAtOnceAndTheGoodOnesAreStillStored() {
        RowRecorder writer = new RowRecorder();
        ClickStream stream = consumer("a", writer);
        redis.call(c -> c.xadd(stream.streamKey(), Map.of("garbage", "yes")), null);   // not a click at all
        List<PendingClick> good = publish(stream, 2);

        stream.processOnce();

        assertThat(writer.stored()).containsExactlyInAnyOrderElementsOf(good.stream().map(PendingClick::eventId).toList());
        assertThat(deadLetters(stream)).isEqualTo(1);
        assertThat(length(stream)).isZero();
        assertThat(pending(stream)).isZero();
        assertThat(metrics.counter("clicks.stream.dead").count()).isEqualTo(1);
        var dead = redis.call(c -> c.xrange(stream.deadLetterKey(), io.lettuce.core.Range.create("-", "+")), List.<io.lettuce.core.StreamMessage<String, String>>of());
        assertThat(dead.get(0).getBody()).containsEntry("garbage", "yes").containsKey("dead-reason").containsKey("dead-original-id");
        assertThat(dead.get(0).getBody().get("dead-reason")).startsWith("unreadable");
    }

    @Test
    void anEntryThatKeepsFailingEndsUpInTheDeadLetterStreamInsteadOfBlockingForever() {
        RowRecorder writer = new RowRecorder();
        ClickStream stream = consumer("a", writer, 100, 3);                      // give up after 3 attempts
        publish(stream, 1);
        writer.failNext(100);

        for (int i = 0; i < 3; i++) {
            stream.processOnce();
        }
        assertThat(deadLetters(stream)).isEqualTo(1);
        assertThat(length(stream)).isZero();
        assertThat(pending(stream)).isZero();
        assertThat(writer.calls).as("tried exactly 3 times").hasSize(3);
    }

    // ---------------------------------------------------------------- Redis misbehaving

    @Test
    void publishingNeverWaitsForRedisAndAFailedSendIsHandedBack() throws Exception {
        ClickStream stream = consumer("a", new RowRecorder());
        RedisTestServer server = RedisTestServer.get();
        List<PendingClick> handedBack = new CopyOnWriteArrayList<>();

        server.pause();                                         // Redis stops answering
        try {
            long start = System.nanoTime();
            for (int i = 0; i < 20; i++) {
                PendingClick click = click();
                assertThat(stream.publish(click, () -> handedBack.add(click))).isTrue();
            }
            assertThat(Duration.ofNanos(System.nanoTime() - start)).as("20 publishes while Redis is frozen").isLessThan(Duration.ofMillis(150));
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(handedBack).hasSize(20)); // every failure is reported
        } finally {
            server.resume();
        }
        assertThat(metrics.counter("clicks.stream.add.failed").count()).isEqualTo(20);
        // once Redis is known to be down, publishing refuses at once so the caller keeps the click itself
        assertThat(redis.mode()).isEqualTo(RedisGateway.Mode.DOWN);
        assertThat(stream.publish(click(), () -> { })).isFalse();
    }

    /**
     * Found by running the real app: the consumer's first read of a 500-entry backlog took longer than the hot path's
     * 100 ms command timeout, which flipped the whole application to "Redis is down" for a moment. A slow batch is not an
     * outage. Here Redis is kept busy for ~600 ms by a script run on a separate connection while the consumer reads.
     */
    @Test
    void aSlowBatchReadIsNotMistakenForRedisBeingDown() throws Exception {
        RowRecorder writer = new RowRecorder();
        ClickStream stream = consumer("a", writer);
        List<PendingClick> sent = publish(stream, 30);

        io.lettuce.core.RedisClient raw = io.lettuce.core.RedisClient.create(RedisTestServer.get().url());
        try (var busy = raw.connect()) {
            String script = "local t=redis.call('TIME'); local s=t[1]*1000000+t[2]; "
                    + "while true do local n=redis.call('TIME'); if n[1]*1000000+n[2]-s > 600000 then break end end return 1";
            Thread holdRedis = new Thread(() -> busy.sync().eval(script, io.lettuce.core.ScriptOutputType.INTEGER));
            holdRedis.start();
            Thread.sleep(100);                                  // let the script start occupying the server

            long start = System.nanoTime();
            assertThat(stream.processOnce()).isEqualTo(30);     // queued behind the script, then answered
            assertThat(Duration.ofNanos(System.nanoTime() - start)).as("it really did have to wait").isGreaterThan(Duration.ofMillis(300));
            holdRedis.join();
        } finally {
            raw.shutdown();
        }

        assertThat(writer.stored()).containsExactlyInAnyOrderElementsOf(sent.stream().map(PendingClick::eventId).toList());
        assertThat(redis.mode()).as("the application was never told Redis is down").isEqualTo(RedisGateway.Mode.UP);
    }

    /**
     * Also found live: Redis delivered a batch to this consumer, but the reply never arrived (here: the server is frozen
     * mid-read and resumed later). The entries are now pending for this consumer; it must look at its own pending list again
     * straight away, not wait for the 30-second takeover.
     */
    @Test
    void aLostReplyDoesNotStrandEntriesUntilTheTakeoverTimeout() {
        RedisGateway impatient = new RedisGateway(new RedisProperties(RedisTestServer.get().url(), false, Duration.ofMillis(200),
                Duration.ofSeconds(2), prefix, new RedisProperties.Cache(Duration.ofMinutes(15), Duration.ofSeconds(30))),
                metrics, Duration.ofMillis(300));                                   // background reads give up after 300 ms
        impatient.start();
        try {
            RowRecorder writer = new RowRecorder();
            writers.add(writer);
            // claim-idle is 30 s here: if the entries were only recoverable by takeover, this test would time out
            ClickStreamProperties slowClaim = new ClickStreamProperties(true, "clicks", "analytics", 1_000_000, Duration.ofSeconds(30),
                    Duration.ofSeconds(30), Duration.ofMillis(50), 5);
            ClickStream stream = new ClickStream(impatient, enricher, writer.writer, slowClaim, 100, metrics, "a");
            stream.processOnce();                                                   // group created, own pending list known to be empty

            double before = metrics.counter("clicks.stream.added").count();
            List<PendingClick> sent = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                PendingClick click = click();
                stream.publish(click, () -> { throw new AssertionError("publish failed"); });
                sent.add(click);
            }
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(metrics.counter("clicks.stream.added").count() - before).isEqualTo(3));

            RedisTestServer server = RedisTestServer.get();
            server.pause();
            try {
                org.assertj.core.api.Assertions.assertThatThrownBy(stream::processOnce)
                        .isInstanceOf(RedisGateway.RedisUnavailableException.class);   // the read timed out
            } finally {
                server.resume();                                                    // Redis now runs the read it had queued
            }
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(redis.call(c -> c.xpending(stream.streamKey(), "analytics").getCount(), -1L))
                            .as("delivered to this consumer, whose client never saw the reply").isEqualTo(3));

            assertThat(stream.processOnce()).as("found in its own pending list at once").isEqualTo(3);
            assertThat(writer.stored()).containsExactlyInAnyOrderElementsOf(sent.stream().map(PendingClick::eventId).toList());
            assertThat(redis.call(c -> c.xlen(stream.streamKey()), -1L)).isZero();
            assertThat(metrics.counter("clicks.stream.claimed").count()).as("no takeover was needed").isZero();
        } finally {
            impatient.stop();
        }
    }

    @Test
    void itRefusesToPublishWhenRedisIsOffOrTheStreamIsDisabled() {
        RedisGateway off = new RedisGateway(new RedisProperties("", false, Duration.ofMillis(100), Duration.ofSeconds(2), "x:",
                new RedisProperties.Cache(Duration.ofMinutes(1), Duration.ofSeconds(30))), metrics);
        off.start();
        assertThat(new ClickStream(off, enricher, mock(ClickWriter.class), props(5, 1000), 10, metrics, "a").publish(click(), () -> { })).isFalse();

        ClickStreamProperties disabled = new ClickStreamProperties(false, "clicks", "analytics", 1000, Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), 5);
        assertThat(new ClickStream(redis, enricher, mock(ClickWriter.class), disabled, 10, metrics, "a").publish(click(), () -> { })).isFalse();
    }

    @Test
    void ifRedisLosesTheGroupItIsRecreatedAndNothingNewIsMissed() {
        RowRecorder writer = new RowRecorder();
        ClickStream stream = consumer("a", writer);
        stream.processOnce();                                   // creates the group
        redis.call(c -> c.del(stream.streamKey()), 0L);         // as if Redis restarted without persistence

        PendingClick click = publish(stream, 1).get(0);         // the stream exists again, but without the group
        stream.processOnce();                                   // NOGROUP: noticed, group recreated
        stream.processOnce();                                   // ...and now the click is read

        assertThat(writer.stored()).containsExactly(click.eventId());
        assertThat(length(stream)).isZero();
    }

    @Test
    void ifTheConsumersFallFarBehindTheStreamIsCappedInsteadOfEatingRedisMemory() {
        ClickStream stream = new ClickStream(redis, enricher, new RowRecorder().writer, props(5, 100), 100, metrics, "a");
        for (int i = 0; i < 1_500; i++) {
            stream.publish(click(), () -> { });
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(metrics.counter("clicks.stream.added").count()).isEqualTo(1_500));
        assertThat(length(stream)).as("approximately capped at 100").isLessThan(1_000);
    }

    @Test
    void theBackgroundConsumerThreadDrainsTheStreamOnItsOwn() {
        RowRecorder writer = new RowRecorder();
        ClickStream stream = consumer("a", writer);
        stream.start();
        try {
            List<PendingClick> sent = publish(stream, 25);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(length(stream)).isZero());
            assertThat(writer.stored()).containsExactlyInAnyOrderElementsOf(sent.stream().map(PendingClick::eventId).toList());
        } finally {
            stream.stop();
        }
    }

    @Test
    void pausingTheConsumerLetsClicksPileUpSafelyInRedisAndResumingDrainsThem() {
        RowRecorder writer = new RowRecorder();
        ClickStream stream = consumer("a", writer);
        stream.pause();
        stream.start();
        try {
            publish(stream, 10);
            org.awaitility.Awaitility.await().pollDelay(Duration.ofMillis(700)).atMost(Duration.ofSeconds(3)).until(() -> true);
            assertThat(length(stream)).as("nothing consumed while paused, and nothing lost").isEqualTo(10);
            assertThat(writer.calls).isEmpty();

            stream.resume();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(length(stream)).isZero());
            assertThat(writer.stored()).hasSize(10);
        } finally {
            stream.stop();
        }
    }

    @SuppressWarnings("unused")
    private static final Object UNUSED = Collections.emptyList();
}
