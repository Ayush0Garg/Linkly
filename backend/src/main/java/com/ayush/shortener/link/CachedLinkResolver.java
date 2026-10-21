package com.ayush.shortener.link;

import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.ayush.shortener.common.ApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

/**
 * The redirect's way to find a link: cache first, Postgres second, and the answer goes back into the cache.
 * With Redis off or down it is simply Postgres every time, exactly as before the cache existed.
 */
@Service
public class CachedLinkResolver {

    /** Concurrent misses for one code share a single database read (see {@link #loadOnce}). */
    private final ConcurrentHashMap<String, CompletableFuture<LinkLookup>> inFlight = new ConcurrentHashMap<>();
    private static final long FOLLOWER_WAIT_MILLIS = 2_000;

    private final LinkCache cache;
    private final LinkService links;
    private final Clock clock;
    private final Counter hit;
    private final Counter negative;
    private final Counter miss;
    private final Counter unavailable;

    CachedLinkResolver(LinkCache cache, LinkService links, Clock clock, MeterRegistry metrics) {
        this.cache = cache;
        this.links = links;
        this.clock = clock;
        this.hit = metrics.counter("cache.link", "result", "hit");
        this.negative = metrics.counter("cache.link", "result", "negative");
        this.miss = metrics.counter("cache.link", "result", "miss");
        this.unavailable = metrics.counter("cache.link", "result", "unavailable");
    }

    /** 404 if unknown, 410 if deactivated or expired. Never counts a click: that is the caller's decision. */
    public ResolvedLink resolve(String code) {
        LinkCache.Cached cached = cache.get(code);
        switch (cached) {
            case LinkCache.Cached.Found found -> {
                if (found.expiresAtMillis() > 0 && clock.millis() >= found.expiresAtMillis()) {
                    throw ApiException.gone("This link is no longer available"); // expired since it was cached
                }
                hit.increment();
                return found.link();
            }
            case LinkCache.Cached.Absent absent -> {
                negative.increment();
                throw ApiException.notFound("Link not found");
            }
            case LinkCache.Cached.Gone gone -> {
                negative.increment();
                throw ApiException.gone("This link is no longer available");
            }
            case LinkCache.Cached.Miss m -> miss.increment();
            case LinkCache.Cached.Unavailable u -> unavailable.increment();
        }
        return answer(loadOnce(code, !(cached instanceof LinkCache.Cached.Unavailable)));
    }

    /**
     * One database read per code at a time. When a link goes viral while its cache entry is cold, hundreds of requests
     * miss together; without this they would all hit Postgres for the same row. The first one reads, the rest wait for
     * its answer (briefly: if it is slow or fails they read for themselves).
     */
    private LinkLookup loadOnce(String code, boolean store) {
        CompletableFuture<LinkLookup> mine = new CompletableFuture<>();
        CompletableFuture<LinkLookup> leader = inFlight.putIfAbsent(code, mine);
        if (leader != null) {
            try {
                return leader.get(FOLLOWER_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException e) {
                return links.lookup(code);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return links.lookup(code);
            }
        }
        try {
            LinkLookup result = links.lookup(code);
            if (store) {
                remember(code, result);
            }
            mine.complete(result);
            return result;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(code, mine);
        }
    }

    private void remember(String code, LinkLookup result) {
        switch (result) {
            case LinkLookup.Found found -> cache.putFound(code, found.link(), found.expiresAt());
            case LinkLookup.NotFound notFound -> cache.putAbsent(code);
            case LinkLookup.Gone gone -> cache.putGone(code);
        }
    }

    private static ResolvedLink answer(LinkLookup result) {
        return switch (result) {
            case LinkLookup.Found found -> found.link();
            case LinkLookup.NotFound notFound -> throw ApiException.notFound("Link not found");
            case LinkLookup.Gone gone -> throw ApiException.gone("This link is no longer available");
        };
    }
}
