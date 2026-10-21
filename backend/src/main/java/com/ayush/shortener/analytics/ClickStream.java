package com.ayush.shortener.analytics;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.ayush.shortener.config.AppProperties;
import com.ayush.shortener.redis.RedisGateway;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisBusyException;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Clicks through a Redis Stream: durable, and shared by every instance of the app.
 *
 * <p><b>Producing</b> (on the redirect): a non-blocking XADD. The redirect never waits for Redis, and if the send later
 * fails the click is handed back to the caller to keep in memory instead.
 *
 * <p><b>Consuming</b> (a background thread per instance, all in one consumer group so each click goes to one instance):
 * read a batch, store it in Postgres, and only THEN acknowledge it. So:
 * <ul>
 *   <li>a crash before acknowledging means the click is delivered again, never lost;</li>
 *   <li>delivering it again is harmless, because the database stores an event id once (see {@link ClickWriter});</li>
 *   <li>entries delivered to an instance that died are taken over by another after {@code claim-idle} (XAUTOCLAIM);</li>
 *   <li>an entry that cannot be read, or that keeps failing, goes to a dead-letter stream instead of blocking the rest.</li>
 * </ul>
 */
@Component
public class ClickStream implements ClickPublisher {

    private static final Logger log = LoggerFactory.getLogger(ClickStream.class);
    private static final int MAX_IN_FLIGHT = 10_000;
    private static final long DEAD_LETTER_MAX_LENGTH = 10_000;
    private static final long GAUGE_REFRESH_MILLIS = 5_000;

    private final RedisGateway redis;
    private final ClickEnricher enricher;
    private final ClickWriter writer;
    private final ClickStreamProperties props;
    private final int batchSize;
    private final String streamKey;
    private final String deadLetterKey;
    private final String consumerName;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();
    private final AtomicLong streamLength = new AtomicLong();
    private final AtomicLong pendingCount = new AtomicLong();
    private final Counter added;
    private final Counter addFailed;
    private final Counter consumed;
    private final Counter written;
    private final Counter duplicates;
    private final Counter dead;
    private final Counter claimed;
    private final Timer readTime;
    private final Timer parseTime;
    private final Timer writeTime;
    private final Timer ackTime;

    private volatile boolean running;
    private volatile boolean paused;
    private volatile boolean groupReady;
    private volatile boolean pendingMayExist = true;
    private volatile boolean lastBatchFailed;
    /** Test seam: runs just before a batch is acknowledged, to simulate a crash between storing and acknowledging. */
    volatile Runnable beforeAcknowledge;
    private long lastClaimMillis;
    private long lastGaugeMillis;
    private Thread worker;

    @Autowired
    public ClickStream(RedisGateway redis, ClickEnricher enricher, ClickWriter writer, ClickStreamProperties props,
                       AppProperties app, MeterRegistry metrics) {
        this(redis, enricher, writer, props, app.analytics().batchSize(), metrics, defaultConsumerName());
    }

    ClickStream(RedisGateway redis, ClickEnricher enricher, ClickWriter writer, ClickStreamProperties props,
                int batchSize, MeterRegistry metrics, String consumerName) {
        this.redis = redis;
        this.enricher = enricher;
        this.writer = writer;
        this.props = props;
        this.batchSize = batchSize;
        this.consumerName = consumerName;
        this.streamKey = redis.key(props.key());
        this.deadLetterKey = redis.key(props.key() + ":dead");
        this.added = metrics.counter("clicks.stream.added");
        this.addFailed = metrics.counter("clicks.stream.add.failed");
        this.consumed = metrics.counter("clicks.stream.consumed");
        this.duplicates = metrics.counter("clicks.duplicates");
        this.written = metrics.counter("clicks.written"); // the same meter the in-process path uses: total stored, either way
        this.dead = metrics.counter("clicks.stream.dead");
        this.claimed = metrics.counter("clicks.stream.claimed");
        // where a batch spends its time: reading from Redis, parsing user agents, writing to Postgres, acknowledging
        this.readTime = metrics.timer("clicks.stream.stage", "stage", "read");
        this.parseTime = metrics.timer("clicks.stream.stage", "stage", "parse");
        this.writeTime = metrics.timer("clicks.stream.stage", "stage", "write");
        this.ackTime = metrics.timer("clicks.stream.stage", "stage", "ack");
        Gauge.builder("clicks.stream.length", streamLength, AtomicLong::get).register(metrics);
        Gauge.builder("clicks.stream.pending", pendingCount, AtomicLong::get).register(metrics);
    }

    private static String defaultConsumerName() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "host";
        }
        return host + "-" + ProcessHandle.current().pid() + "-" + UUID.randomUUID().toString().substring(0, 4);
    }

    // ------------------------------------------------------------------ producing

    @Override
    public boolean publish(PendingClick click, Runnable onFailure) {
        if (!props.enabled() || !redis.available()) {
            return false;
        }
        if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) { // Redis is slow: do not pile up unbounded work behind it
            inFlight.decrementAndGet();
            return false;
        }
        Map<String, String> body = click.toFields();
        boolean submitted = redis.callAsync(
                c -> c.xadd(streamKey, XAddArgs.Builder.maxlen(props.maxLength()).approximateTrimming(), body),
                (id, error) -> {
                    inFlight.decrementAndGet();
                    if (error != null) {
                        addFailed.increment();
                        onFailure.run(); // the caller keeps the click another way
                    } else {
                        added.increment();
                    }
                });
        if (!submitted) {
            inFlight.decrementAndGet();
        }
        return submitted;
    }

    // ------------------------------------------------------------------ consuming

    @PostConstruct
    void start() {
        if (!props.enabled() || redis.mode() == RedisGateway.Mode.OFF) {
            return;
        }
        running = true;
        worker = new Thread(this::run, "click-stream-consumer");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // let sends that are already on the wire finish (briefly)
        long deadline = System.nanoTime() + 1_000_000_000L;
        while (inFlight.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void run() {
        while (running) {
            try {
                if (paused || !redis.available()) {
                    Thread.sleep(500);
                    continue;
                }
                int processed = processOnce();
                if (processed == 0 || lastBatchFailed) {
                    Thread.sleep(lastBatchFailed ? 1_000 : props.pollInterval().toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RedisGateway.RedisUnavailableException e) {
                sleepQuietly(1_000); // Redis is away: the gateway's probe notices when it is back
            } catch (RuntimeException e) {
                log.error("Click stream consumer error", e);
                sleepQuietly(1_000);
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** One round: retry this consumer's own unacknowledged entries, read new ones, and (when due) take over dead ones. */
    int processOnce() {
        lastBatchFailed = false;
        int processed = 0;
        try {
            ensureGroup();
            if (pendingMayExist) {
                List<StreamMessage<String, String>> own = read(XReadArgs.StreamOffset.from(streamKey, "0"));
                if (own.isEmpty()) {
                    pendingMayExist = false;
                } else {
                    processed += handle(own);
                }
            }
            processed += handle(readNew());
            if (claimDue()) {
                List<StreamMessage<String, String>> taken = claim();
                claimed.increment(taken.size());
                processed += handle(taken);
            }
            refreshGauges();
        } catch (RedisCommandExecutionException e) {
            if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
                // Redis lost the stream or the group (it restarted without persistence): make it again, replay from the start
                log.warn("Click stream group is missing in Redis; recreating it");
                groupReady = false;
                pendingMayExist = true;
                return processed;
            }
            throw e;
        }
        return processed;
    }

    private void ensureGroup() {
        if (groupReady) {
            return;
        }
        redis.executeBackground(c -> {
            try {
                c.xgroupCreate(XReadArgs.StreamOffset.from(streamKey, "0"), props.group(), XGroupCreateArgs.Builder.mkstream(true));
            } catch (RedisBusyException alreadyThere) {
                // another instance made it first: fine
            }
            return null;
        });
        groupReady = true;
    }

    /**
     * New entries. If this fails the reply may simply have been lost: Redis can have delivered the entries to this consumer
     * (they are now in its pending list) while we never saw them. So re-read the pending list next round instead of
     * waiting for the takeover timeout.
     */
    private List<StreamMessage<String, String>> readNew() {
        try {
            return read(XReadArgs.StreamOffset.lastConsumed(streamKey));
        } catch (RuntimeException e) {
            pendingMayExist = true;
            throw e;
        }
    }

    private List<StreamMessage<String, String>> read(XReadArgs.StreamOffset<String> offset) {
        long start = System.nanoTime();
        List<StreamMessage<String, String>> messages = redis.executeBackground(c -> c.xreadgroup(
                Consumer.from(props.group(), consumerName), XReadArgs.Builder.count(batchSize), offset));
        if (messages != null && !messages.isEmpty()) {
            readTime.record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
        return messages == null ? List.of() : messages;
    }

    private boolean claimDue() {
        long now = System.currentTimeMillis();
        if (now - lastClaimMillis < props.claimInterval().toMillis()) {
            return false;
        }
        lastClaimMillis = now;
        return true;
    }

    private List<StreamMessage<String, String>> claim() {
        var result = redis.executeBackground(c -> c.xautoclaim(streamKey,
                XAutoClaimArgs.Builder.xautoclaim(Consumer.from(props.group(), consumerName), props.claimIdle(), "0-0").count(batchSize)));
        return result == null ? List.of() : result.getMessages();
    }

    /** Stores a batch, then acknowledges it. Returns how many entries were dealt with (stored, or given up on). */
    private int handle(List<StreamMessage<String, String>> messages) {
        if (messages.isEmpty()) {
            return 0;
        }
        List<String> ids = new ArrayList<>(messages.size());
        List<PendingClick> clicks = new ArrayList<>(messages.size());
        for (StreamMessage<String, String> message : messages) {
            try {
                clicks.add(PendingClick.fromFields(message.getBody()));
                ids.add(message.getId());
            } catch (IllegalArgumentException e) {
                // will never become readable by retrying: set it aside rather than let it block everything behind it
                deadLetter(List.of(message.getId()), List.of(message.getBody()), "unreadable: " + e.getMessage());
            }
        }
        if (clicks.isEmpty()) {
            return messages.size();
        }

        int inserted;
        try {
            long parseStart = System.nanoTime();
            List<ClickRow> rows = new ArrayList<>(clicks.size());
            clicks.forEach(c -> rows.add(enricher.finish(c)));
            long writeStart = System.nanoTime();
            parseTime.record(writeStart - parseStart, java.util.concurrent.TimeUnit.NANOSECONDS);
            inserted = writer.write(rows);
            writeTime.record(System.nanoTime() - writeStart, java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            lastBatchFailed = true;
            pendingMayExist = true;
            retryOrGiveUp(messages, ids, clicks, e);
            return messages.size();
        }
        consumed.increment(clicks.size());
        written.increment(inserted);
        duplicates.increment(clicks.size() - inserted);
        ids.forEach(attempts::remove);
        long ackStart = System.nanoTime();
        try {
            acknowledge(ids); // after the commit: a failure here only means a harmless re-delivery
            ackTime.record(System.nanoTime() - ackStart, java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            pendingMayExist = true; // they are stored but still pending: re-read them next round, do not wait for a takeover
            throw e;
        }
        return messages.size();
    }

    /** Stored, so it is done: remove it from the pending list and from the stream. */
    void acknowledge(List<String> ids) {
        Runnable hook = beforeAcknowledge;
        if (hook != null) {
            hook.run();
        }
        String[] array = ids.toArray(String[]::new);
        redis.executeBackground(c -> {
            c.xack(streamKey, props.group(), array);
            c.xdel(streamKey, array);
            return null;
        });
    }

    private void retryOrGiveUp(List<StreamMessage<String, String>> messages, List<String> ids, List<PendingClick> clicks, RuntimeException error) {
        List<String> giveUp = new ArrayList<>();
        List<Map<String, String>> bodies = new ArrayList<>();
        for (StreamMessage<String, String> message : messages) {
            if (!ids.contains(message.getId())) {
                continue;
            }
            int tries = attempts.merge(message.getId(), 1, Integer::sum);
            if (tries >= props.maxAttempts()) {
                giveUp.add(message.getId());
                bodies.add(message.getBody());
            }
        }
        if (attempts.size() > 50_000) {
            attempts.clear(); // never let bookkeeping grow without bound
        }
        if (giveUp.isEmpty()) {
            log.warn("Could not store {} clicks ({}); they stay in the stream and will be retried", clicks.size(), error.toString());
        } else {
            deadLetter(giveUp, bodies, "gave up after " + props.maxAttempts() + " attempts: " + error);
            giveUp.forEach(attempts::remove);
        }
    }

    private void deadLetter(List<String> ids, List<Map<String, String>> bodies, String reason) {
        for (int i = 0; i < ids.size(); i++) {
            Map<String, String> record = new HashMap<>(bodies.get(i));
            record.put("dead-reason", reason.length() > 300 ? reason.substring(0, 300) : reason);
            record.put("dead-original-id", ids.get(i));
            redis.executeBackground(c -> c.xadd(deadLetterKey, XAddArgs.Builder.maxlen(DEAD_LETTER_MAX_LENGTH).approximateTrimming(), record));
        }
        log.error("Moved {} click(s) to the dead-letter stream {}: {}", ids.size(), deadLetterKey, reason);
        dead.increment(ids.size());
        acknowledge(ids);
    }

    private void refreshGauges() {
        long now = System.currentTimeMillis();
        if (now - lastGaugeMillis < GAUGE_REFRESH_MILLIS) {
            return;
        }
        lastGaugeMillis = now;
        streamLength.set(redis.executeBackground(c -> c.xlen(streamKey)));
        var pending = redis.executeBackground(c -> c.xpending(streamKey, props.group()));
        pendingCount.set(pending == null ? 0 : pending.getCount());
    }

    // ------------------------------------------------------------------ for tests and operations

    void pause() {
        paused = true;
    }

    void resume() {
        paused = false;
    }

    String consumerName() {
        return consumerName;
    }

    String streamKey() {
        return streamKey;
    }

    String deadLetterKey() {
        return deadLetterKey;
    }
}
