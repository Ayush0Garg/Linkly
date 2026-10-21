package com.ayush.shortener.redis;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisClient;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.Delay;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The one place that talks to Redis, and the one place that knows whether Redis is there.
 *
 * <p>The application must work without Redis, so callers never get an exception from here: they pass a fallback and
 * get it back whenever Redis is off or down. Three modes, always reported out loud (startup banner, log lines on every
 * change, /actuator/health, the redis.up gauge):
 * <ul>
 *   <li>OFF: no URL configured. Nothing is attempted.</li>
 *   <li>UP: connected and answering.</li>
 *   <li>DOWN: configured but unreachable. Commands are refused immediately (no waiting on timeouts) until a periodic
 *       probe succeeds again.</li>
 * </ul>
 */
@Component
public class RedisGateway {

    public enum Mode { OFF, UP, DOWN }

    /** Redis cannot be used right now (off, down, or just failed). Never means "the command was wrong". */
    public static class RedisUnavailableException extends RuntimeException {
        public RedisUnavailableException(String message) {
            super(message);
        }
    }

    /** What stops working while Redis is off, for the features that predate {@link DegradedEffects}. */
    static final List<String> DEGRADED_EFFECTS = List.of(
            "redirect cache is off: every redirect reads Postgres",
            "click events are buffered in this process only: a crash loses up to about a second of clicks");

    /**
     * A command that merely takes longer than the (tight, 100 ms) hot-path timeout is a slow reply, not an outage: a GC
     * pause, a big command, or a busy box can cause one. Found by the load test: a single such timeout used to switch the
     * whole application to degraded mode for several seconds. A lost connection still trips immediately; timeouts must
     * come this many in a row, with no success in between, which still catches a hung Redis within ~100 ms under load.
     */
    static final int TIMEOUT_STRIKES = 5;

    private static final Logger log = LoggerFactory.getLogger(RedisGateway.class);

    private final RedisProperties props;
    private final Counter fallbacks;
    private final Counter timeouts;
    private final AtomicInteger timeoutsInARow = new AtomicInteger();
    private final Duration backgroundTimeout;
    private RedisClient client;
    private RedisClient backgroundClient;
    private volatile StatefulRedisConnection<String, String> backgroundConnection;
    private ClientResources resources;
    private volatile StatefulRedisConnection<String, String> connection;
    private volatile Mode mode;
    private volatile String reason = "";
    private ObjectProvider<DegradedEffects> contributors;

    public RedisGateway(RedisProperties props, MeterRegistry metrics) {
        this(props, metrics, Duration.ofSeconds(5));
    }

    /** @param backgroundTimeout the command timeout for background work (stream consumers): far more generous than the hot path's */
    @Autowired
    public RedisGateway(RedisProperties props, MeterRegistry metrics,
                        @Value("${app.redis.background-timeout:5s}") Duration backgroundTimeout) {
        this.backgroundTimeout = backgroundTimeout;
        this.props = props;
        this.mode = StringUtils.hasText(props.url()) ? Mode.DOWN : Mode.OFF;
        this.reason = mode == Mode.OFF ? "REDIS_URL is not set" : "not connected yet";
        this.fallbacks = metrics.counter("redis.fallbacks");
        this.timeouts = metrics.counter("redis.timeouts");
        Gauge.builder("redis.up", this, g -> g.mode == Mode.UP ? 1 : 0).register(metrics);
    }

    /** Features that behave differently while Redis is off add their own lines to the banner. */
    @Autowired(required = false)
    void contributors(ObjectProvider<DegradedEffects> contributors) {
        this.contributors = contributors;
    }

    @PostConstruct
    public void start() {
        if (mode == Mode.OFF) {
            return;
        }
        resources = DefaultClientResources.builder()
                .reconnectDelay(Delay.constant(Duration.ofSeconds(1))) // default backs off to 30 s: too slow to recover
                .build();
        client = RedisClient.create(resources, props.url());
        client.setOptions(ClientOptions.builder()
                .autoReconnect(true)
                // Fail fast while disconnected instead of queueing commands until they time out.
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(1)).build())
                .timeoutOptions(TimeoutOptions.enabled(props.commandTimeout()))
                .build());
        backgroundClient = RedisClient.create(resources, props.url());
        backgroundClient.setOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(1)).build())
                .timeoutOptions(TimeoutOptions.enabled(backgroundTimeout))
                .build());
        connect(false);
        if (props.required() && mode != Mode.UP) {
            throw new IllegalStateException("REDIS_REQUIRED is true but Redis is not reachable at " + safeUrl() + ": " + reason);
        }
    }

    @PreDestroy
    public void stop() {
        if (backgroundConnection != null) {
            backgroundConnection.close();
        }
        if (backgroundClient != null) {
            backgroundClient.shutdown();
        }
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
        if (resources != null) {
            resources.shutdown();
        }
    }

    // ------------------------------------------------------------------ using Redis

    public Mode mode() {
        return mode;
    }

    public boolean available() {
        return mode == Mode.UP;
    }

    /** The reason Redis is not UP (empty when it is). */
    public String reason() {
        return mode == Mode.UP ? "" : reason;
    }

    public String key(String suffix) {
        return props.keyPrefix() + suffix;
    }

    /**
     * Runs a command against Redis, or returns the fallback if Redis is off, down, or fails right now.
     * A failure flips the gateway to DOWN at once, so the next requests skip Redis instead of each waiting for a timeout.
     */
    public <T> T call(Function<RedisCommands<String, String>, T> operation, T fallback) {
        StatefulRedisConnection<String, String> current = connection;
        if (mode != Mode.UP || current == null) {
            fallbacks.increment();
            return fallback;
        }
        try {
            T result = operation.apply(current.sync());
            answered();
            return result;
        } catch (RedisCommandExecutionException e) {
            // Redis answered, with an error for this command: it is up, the command (or its data) is the problem.
            fallbacks.increment();
            answered();
            log.warn("Redis rejected a command: {}", describe(e));
            return fallback;
        } catch (RuntimeException e) {
            fallbacks.increment();
            failed(e);
            return fallback;
        }
    }

    /**
     * For work that has to tell "Redis is away" from "Redis said no" (stream consumers): throws
     * {@link RedisUnavailableException} for the first, and lets {@link RedisCommandExecutionException} (an error reply
     * from the server, such as NOGROUP) through untouched for the second.
     */
    public <T> T execute(Function<RedisCommands<String, String>, T> operation) {
        StatefulRedisConnection<String, String> current = connection;
        if (mode != Mode.UP || current == null) {
            throw new RedisUnavailableException(mode == Mode.OFF ? "Redis is off" : reason);
        }
        try {
            T result = operation.apply(current.sync());
            answered();
            return result;
        } catch (RedisCommandExecutionException e) {
            answered();
            throw e;
        } catch (RuntimeException e) {
            failed(e);
            throw new RedisUnavailableException(describe(e));
        }
    }

    /**
     * For background work that moves a lot of data (stream consumers reading batches of hundreds of entries): its own
     * connection and a generous command timeout, because the hot path's 100 ms is far too tight for it. A slow batch is
     * not an outage, so a failure here does NOT flip the application into degraded mode; it only drops this connection
     * so the next call opens a fresh one. (If Redis really is away, the regular probe notices and says so.)
     *
     * @throws RedisUnavailableException if Redis is off or down, or the call failed or timed out
     * @throws RedisCommandExecutionException if the server answered with an error reply
     */
    public <T> T executeBackground(Function<RedisCommands<String, String>, T> operation) {
        if (mode != Mode.UP || backgroundClient == null) {
            throw new RedisUnavailableException(mode == Mode.OFF ? "Redis is off" : reason);
        }
        try {
            return operation.apply(backgroundConnection().sync());
        } catch (RedisCommandExecutionException e) {
            throw e;
        } catch (RuntimeException e) {
            dropBackgroundConnection();
            throw new RedisUnavailableException(describe(e));
        }
    }

    private StatefulRedisConnection<String, String> backgroundConnection() {
        StatefulRedisConnection<String, String> current = backgroundConnection;
        if (current == null || !current.isOpen()) {
            synchronized (this) {
                current = backgroundConnection;
                if (current == null || !current.isOpen()) {
                    current = backgroundClient.connect();
                    backgroundConnection = current;
                }
            }
        }
        return current;
    }

    private synchronized void dropBackgroundConnection() {
        StatefulRedisConnection<String, String> old = backgroundConnection;
        backgroundConnection = null;
        if (old != null) {
            try {
                old.closeAsync();
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
    }

    /**
     * Sends a command without waiting for the answer (Lettuce pipelines these on the shared connection).
     *
     * @param done called later, on another thread, with the result or the failure
     * @return false if Redis is not usable and nothing was sent
     */
    public <T> boolean callAsync(Function<RedisAsyncCommands<String, String>, RedisFuture<T>> operation, BiConsumer<T, Throwable> done) {
        StatefulRedisConnection<String, String> current = connection;
        if (mode != Mode.UP || current == null) {
            return false;
        }
        RedisFuture<T> future;
        try {
            future = operation.apply(current.async());
        } catch (RuntimeException e) {
            failed(e);
            return false;
        }
        future.whenComplete((value, error) -> {
            if (error == null || isServerError(error)) {
                answered();
            } else {
                failed(error);
            }
            done.accept(value, error);
        });
        return true;
    }

    private static boolean isServerError(Throwable error) {
        return error instanceof RedisCommandExecutionException || error.getCause() instanceof RedisCommandExecutionException;
    }

    /** For code that talks to Redis through its own connection (e.g. the rate limiter): report that it failed. */
    public void reportFailure(Throwable e) {
        failed(e);
    }

    /** Redis replied (even with an error): it is alive, so slow replies stop counting against it. */
    private void answered() {
        if (timeoutsInARow.get() != 0) {
            timeoutsInARow.set(0);
        }
    }

    /** A lost connection or protocol failure switches to DEGRADED at once; timeouts only after {@link #TIMEOUT_STRIKES} in a row. */
    private void failed(Throwable e) {
        if (!isTimeout(e)) {
            changeTo(Mode.DOWN, describe(e), true);
            return;
        }
        timeouts.increment();
        int strikes = timeoutsInARow.incrementAndGet();
        if (strikes >= TIMEOUT_STRIKES) {
            changeTo(Mode.DOWN, strikes + " commands in a row timed out (" + describe(e) + ")", true);
        } else {
            log.debug("Redis command timed out ({} in a row, degraded mode at {})", strikes, TIMEOUT_STRIKES);
        }
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof RedisCommandTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** For later features that need a connection of their own (blocking stream reads). Null when Redis is off. */
    public RedisClient client() {
        return client;
    }

    // ------------------------------------------------------------------ keeping track

    /** Pings a healthy connection; reconnects a dead one. Runs every probe-interval. */
    @Scheduled(fixedDelayString = "${app.redis.probe-interval}")
    public void probe() {
        if (client == null) {
            return;
        }
        try {
            StatefulRedisConnection<String, String> current = connection;
            if (current == null || !current.isOpen()) {
                connect(true);
                return;
            }
            current.sync().ping();
            changeTo(Mode.UP, "", true);
        } catch (RuntimeException e) {
            failed(e);
        }
    }

    private synchronized void connect(boolean announce) {
        try {
            connection = client.connect();
            changeTo(Mode.UP, "", announce);
        } catch (RuntimeException e) {
            changeTo(Mode.DOWN, describe(e), announce);
        }
    }

    private synchronized void changeTo(Mode next, String why, boolean announce) {
        Mode before = mode;
        mode = next;
        if (next == Mode.UP) {
            timeoutsInARow.set(0);
        }
        reason = why;
        if (before == next || !announce) {
            return;
        }
        if (next == Mode.UP) {
            log.info("Redis is reachable again at {}: NORMAL mode restored (cache and shared features back on)", safeUrl());
        } else {
            log.warn("Redis connection LOST ({}): running in DEGRADED mode until it is back.{}", why, effects());
        }
    }

    // ------------------------------------------------------------------ saying so out loud

    /** Printed once, right before "Started ...", so nobody can miss it. */
    @EventListener(ApplicationReadyEvent.class)
    void announceStartupMode() {
        if (mode == Mode.UP) {
            log.info("Redis connected at {}: NORMAL mode", safeUrl());
        } else {
            log.warn(banner());
        }
    }

    String banner() {
        String bar = "=".repeat(78);
        StringBuilder text = new StringBuilder("\n").append(bar)
                .append("\n REDIS IS OFF: running in DEGRADED mode")
                .append("\n   reason: ").append(mode == Mode.OFF ? "REDIS_URL is not set" : "cannot reach " + safeUrl() + " (" + reason + ")")
                .append("\n   while Redis is off:");
        allEffects().forEach(e -> text.append("\n     - ").append(e));
        text.append("\n   Set REDIS_URL to run normally, and REDIS_REQUIRED=true in production so a missing Redis fails the start.\n")
                .append(bar);
        return text.toString();
    }

    private String effects() {
        StringBuilder text = new StringBuilder();
        allEffects().forEach(e -> text.append("\n  - ").append(e));
        return text.toString();
    }

    private List<String> allEffects() {
        java.util.ArrayList<String> all = new java.util.ArrayList<>(DEGRADED_EFFECTS);
        if (contributors != null) {
            contributors.orderedStream().forEach(c -> all.addAll(c.effects()));
        }
        return all;
    }

    /** The configured URL without credentials, safe to log and to show. */
    String safeUrl() {
        try {
            URI uri = URI.create(props.url().trim());
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (RuntimeException e) {
            return "(invalid REDIS_URL)";
        }
    }

    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        return message.length() > 160 ? message.substring(0, 160) : message;
    }
}
