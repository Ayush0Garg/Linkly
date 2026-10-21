package com.ayush.shortener.analytics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import ua_parser.Client;
import ua_parser.Parser;

/** Turns a User-Agent header into browser / OS / device class, and decides whether it looks like a bot. */
@Component
public class UserAgentClassifier {

    /** Browser and OS as shown in the dashboard, plus the device class (desktop, mobile, tablet, bot or unknown). */
    public record Result(String browser, String os, String device, boolean bot) {
    }

    private static final int MAX_UA_LENGTH = 512;

    // Crawlers, link-preview fetchers, uptime monitors and scripted HTTP clients.
    private static final Pattern BOT = Pattern.compile(
            "bot|crawl|spider|slurp|preview|fetch|monitor|headless|facebookexternalhit|whatsapp|telegram|discord|"
                    + "curl/|wget|python-|java/|okhttp|go-http-client|libwww|httpclient|scrapy|axios|node-fetch|postman",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLET = Pattern.compile("ipad|tablet|kindle|silk/|android(?!.*mobile)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MOBILE = Pattern.compile("mobi|iphone|ipod|android|windows phone", Pattern.CASE_INSENSITIVE);

    /**
     * Parsing a User-Agent costs about 1.5 ms (dozens of regexes), which made it the whole cost of storing a click
     * (measured: 99% of consumer time). Real traffic repeats a few hundred distinct strings, so results are memoised.
     * The header is client-controlled: the map is capped, and simply restarted when full, so a client sending endless
     * unique strings can slow its own parsing down but cannot grow memory.
     */
    static final int CACHE_LIMIT = 5_000;

    private final Parser parser;
    private final Map<String, Result> cache = new ConcurrentHashMap<>();

    public UserAgentClassifier() {
        this.parser = new Parser();
    }

    public Result classify(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            // Real browsers always send one; a missing header means a script.
            return new Result(null, null, "bot", true);
        }
        String ua = userAgent.length() > MAX_UA_LENGTH ? userAgent.substring(0, MAX_UA_LENGTH) : userAgent;
        Result known = cache.get(ua);
        if (known != null) {
            return known;
        }
        Result result = parse(ua);
        if (cache.size() >= CACHE_LIMIT) {
            cache.clear();
        }
        cache.put(ua, result);
        return result;
    }

    int cachedEntries() {
        return cache.size();
    }

    private Result parse(String ua) {
        Client client = parser.parse(ua);

        boolean bot = BOT.matcher(ua).find() || "Spider".equalsIgnoreCase(client.device.family);
        String device = bot ? "bot"
                : TABLET.matcher(ua).find() ? "tablet"
                : MOBILE.matcher(ua).find() ? "mobile"
                : "desktop";
        return new Result(browser(client.userAgent.family), os(client.os.family), device, bot);
    }

    private static String browser(String family) {
        if (family == null || family.equals("Other")) {
            return null;
        }
        // Fold mobile variants into their desktop name so "Chrome" and "Chrome Mobile" are one bar in a chart.
        if (family.startsWith("Chrome")) return "Chrome";
        if (family.startsWith("Mobile Safari") || family.equals("Safari")) return "Safari";
        if (family.startsWith("Firefox")) return "Firefox";
        if (family.startsWith("Edge")) return "Edge";
        if (family.startsWith("Opera")) return "Opera";
        return family.length() > 64 ? family.substring(0, 64) : family;
    }

    private static String os(String family) {
        if (family == null || family.equals("Other")) {
            return null;
        }
        if (family.equals("Mac OS X")) return "macOS";
        return family.length() > 64 ? family.substring(0, 64) : family;
    }
}
