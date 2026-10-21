package com.ayush.shortener.link;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.ayush.shortener.common.ApiException;
import com.ayush.shortener.config.AppProperties;
import org.springframework.stereotype.Component;

/**
 * Decides which destinations we are willing to shorten. This is an abuse guard (no javascript: links,
 * no pointing at internal networks, no loops back to ourselves) - we never fetch the URL server-side.
 * It deliberately does no DNS lookups: they'd be slow, and DNS rebinding makes them unreliable anyway.
 */
@Component
public class UrlValidator {

    static final int MAX_LENGTH = 8192;

    private static final Pattern STRICT_IPV4 =
            Pattern.compile("(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");
    /** Decimal/hex/short forms like 2130706433, 0x7f.1 or 127.1 that browsers happily treat as IPv4. */
    private static final Pattern NUMERIC_HOST =
            Pattern.compile("(?:0x[0-9a-f]+|\\d+)(?:\\.(?:0x[0-9a-f]+|\\d+))*", Pattern.CASE_INSENSITIVE);
    private static final List<String> INTERNAL_SUFFIXES = List.of(".localhost", ".local", ".internal", ".lan", ".home.arpa");

    private final String ownHost;

    public UrlValidator(AppProperties properties) {
        this.ownHost = URI.create(properties.baseUrl()).getHost();
    }

    /** @return the normalized URL to store; throws a 400 {@link ApiException} if it is not acceptable */
    public String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("URL is required");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_LENGTH) {
            throw ApiException.badRequest("URL must be at most " + MAX_LENGTH + " characters");
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw ApiException.badRequest("URL is not valid");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw ApiException.badRequest("Only http and https URLs can be shortened");
        }
        if (uri.getRawUserInfo() != null) {
            // http://trusted.com@evil.com is a classic phishing trick.
            throw ApiException.badRequest("URLs containing credentials are not allowed");
        }
        String host = uri.getHost();
        if (host == null) {
            throw ApiException.badRequest("URL must include a host");
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (isBlockedHost(host)) {
            throw ApiException.badRequest("This destination is not allowed");
        }
        if (host.equals(ownHost)) {
            throw ApiException.badRequest("Links to this service itself cannot be shortened");
        }
        // Non-ASCII characters get percent-encoded (1 char -> up to 9), so the stored form can be much longer.
        String normalized = uri.toASCIIString();
        if (normalized.length() > MAX_LENGTH) {
            throw ApiException.badRequest("URL must be at most " + MAX_LENGTH + " characters once encoded");
        }
        return normalized;
    }

    private boolean isBlockedHost(String host) {
        if (host.startsWith("[")) {
            return isBlockedAddress(literal(host.substring(1, host.length() - 1)));
        }
        if (STRICT_IPV4.matcher(host).matches()) {
            return isBlockedAddress(literal(host));
        }
        if (NUMERIC_HOST.matcher(host).matches()) {
            return true; // non-canonical IP spelling: reject rather than guess how a client would parse it
        }
        if (!host.contains(".") || host.equals("localhost")) {
            return true; // single-label names only resolve inside private networks
        }
        return INTERNAL_SUFFIXES.stream().anyMatch(host::endsWith);
    }

    /** Only ever called with a string that is already a numeric IP literal, so this performs no DNS lookup. */
    private static InetAddress literal(String ip) {
        try {
            return InetAddress.getByName(ip);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static boolean isBlockedAddress(InetAddress address) {
        if (address == null) {
            return true;
        }
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first == 0                                   // 0.0.0.0/8
                    || (first == 100 && second >= 64 && second <= 127) // 100.64.0.0/10 carrier-grade NAT
                    || first >= 240;                            // reserved / broadcast
        }
        return (b[0] & 0xfe) == 0xfc;                           // fc00::/7 unique local addresses
    }
}
