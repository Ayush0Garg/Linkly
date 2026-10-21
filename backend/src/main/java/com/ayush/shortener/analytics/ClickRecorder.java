package com.ayush.shortener.analytics;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import com.ayush.shortener.config.AppProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The bridge between the redirect and the database.
 *
 * record() is the only thing on the redirect path. It captures the click (an offline lookup and a hash; the IP address is
 * used here and then forgotten) and hands it off without waiting:
 * <ul>
 *   <li>Redis up: sent to the click stream, which is durable and shared by all instances (see {@link ClickStream});</li>
 *   <li>Redis off or down, or the send fails: kept in a bounded in-process queue and written by a scheduled task.
 *       This is the old behaviour and the safety net. Its limit: a hard crash loses up to a flush interval of clicks.</li>
 * </ul>
 * It never blocks and never throws, so analytics trouble can't slow down or break a redirect. If the in-process queue is
 * full the click is dropped and counted (clicks.dropped): losing a count beats losing availability.
 *
 * Both paths end in {@link ClickWriter}, which stores each click exactly once, so it never matters which path (or how
 * many times) a click travelled.
 */
@Component
public class ClickRecorder {

    private static final Logger log = LoggerFactory.getLogger(ClickRecorder.class);
    private static final int MAX_FAILED_ATTEMPTS = 3;
    private static final int MAX_BATCHES_PER_FLUSH = 50;
    private static final int MAX_HEADER_CHARS = 1024;

    private final BlockingQueue<PendingClick> queue;
    private final int batchSize;
    private final ClickEnricher enricher;
    private final ClickPublisher stream;
    private final ClickWriter writer;
    private final Clock clock;
    private final Counter recorded;
    private final Counter dropped;
    private final Counter written;
    private final Counter lost;
    private final Counter duplicates;

    /** A batch that failed to write is retried on the next flush before anything else is processed. */
    private List<ClickRow> pending;
    private int pendingAttempts;

    ClickRecorder(AppProperties properties, ClickEnricher enricher, ClickPublisher stream, ClickWriter writer, Clock clock,
                  MeterRegistry metrics) {
        AppProperties.Analytics config = properties.analytics();
        this.queue = new ArrayBlockingQueue<>(config.queueCapacity());
        this.batchSize = config.batchSize();
        this.enricher = enricher;
        this.stream = stream;
        this.writer = writer;
        this.clock = clock;
        this.recorded = metrics.counter("clicks.recorded");
        this.dropped = metrics.counter("clicks.dropped");
        this.written = metrics.counter("clicks.written");
        this.lost = metrics.counter("clicks.lost");
        this.duplicates = metrics.counter("clicks.duplicates");
        Gauge.builder("clicks.queue.size", queue, BlockingQueue::size).register(metrics);
    }

    /** Hot path. Only real GET requests count: HEAD is what many link-preview and uptime checkers send. */
    public void record(long linkId, HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) {
            return;
        }
        try {
            PendingClick click = enricher.capture(linkId, clock.instant(), request.getRemoteAddr(),
                    truncate(request.getHeader("User-Agent")), truncate(request.getHeader("Referer")));
            // Stream first; if it cannot take the click now, or fails later, the in-process queue keeps it.
            if (stream.publish(click, () -> keepLocally(click)) || keepLocally(click)) {
                recorded.increment();
            }
        } catch (RuntimeException e) {
            dropped.increment();
            log.warn("Could not record click", e);
        }
    }

    /** @return false if the in-process queue is full and the click had to be dropped */
    private boolean keepLocally(PendingClick click) {
        if (queue.offer(click)) {
            return true;
        }
        dropped.increment();
        return false;
    }

    @Scheduled(fixedDelayString = "${app.analytics.flush-interval}")
    public void flushScheduled() {
        try {
            flush();
        } catch (RuntimeException e) {
            log.error("Click flush failed", e); // never let one bad tick kill the schedule
        }
    }

    /** Drains the queue into the database. Synchronized: the schedule, shutdown and tests may all call it. */
    public synchronized int flush() {
        int total = 0;
        if (pending != null && !writePending()) {
            return 0; // still failing: leave the queue alone and try again next tick
        }
        for (int batches = 0; batches < MAX_BATCHES_PER_FLUSH; batches++) {
            List<PendingClick> raw = new ArrayList<>(batchSize);
            queue.drainTo(raw, batchSize);
            if (raw.isEmpty()) {
                break;
            }
            List<ClickRow> rows = new ArrayList<>(raw.size());
            for (PendingClick click : raw) {
                try {
                    rows.add(enricher.finish(click));
                } catch (RuntimeException e) {
                    lost.increment(); // one malformed click must not sink its whole batch
                    log.warn("Skipping a click that could not be enriched", e);
                }
            }
            pending = rows;
            pendingAttempts = 0;
            if (!writePending()) {
                break;
            }
            total += rows.size();
        }
        return total;
    }

    private boolean writePending() {
        try {
            int inserted = writer.write(pending);
            written.increment(inserted);
            duplicates.increment(pending.size() - inserted);
            pending = null;
            return true;
        } catch (RuntimeException e) {
            pendingAttempts++;
            if (pendingAttempts >= MAX_FAILED_ATTEMPTS) {
                lost.increment(pending.size());
                log.error("Giving up on a batch of {} clicks after {} failed attempts", pending.size(), pendingAttempts, e);
                pending = null;
            } else {
                log.warn("Writing {} clicks failed (attempt {}), will retry", pending.size(), pendingAttempts, e);
            }
            return false;
        }
    }

    @PreDestroy
    void flushOnShutdown() {
        try {
            flush();
        } catch (RuntimeException e) {
            log.error("Final click flush failed", e);
        }
    }

    private static String truncate(String header) {
        return header != null && header.length() > MAX_HEADER_CHARS ? header.substring(0, MAX_HEADER_CHARS) : header;
    }
}
