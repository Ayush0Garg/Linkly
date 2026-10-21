package com.ayush.shortener.analytics;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Turns a request into a stored click in two steps, split by cost and by privacy:
 * <ul>
 *   <li>{@link #capture}: on the redirect itself. Cheap (an offline lookup and one HMAC) and the only place the IP address
 *       is used: it is reduced to a country and a daily hash, then forgotten.</li>
 *   <li>{@link #finish}: later, off the redirect path. The expensive part, parsing the User-Agent.</li>
 * </ul>
 */
@Component
public class ClickEnricher {

    private static final int MAX_HOST_LENGTH = 255;
    private static final int MAX_UA_LENGTH = 512;

    private final UserAgentClassifier userAgents;
    private final GeoLocator geo;
    private final VisitorHasher hasher;

    ClickEnricher(UserAgentClassifier userAgents, GeoLocator geo, VisitorHasher hasher) {
        this.userAgents = userAgents;
        this.geo = geo;
        this.hasher = hasher;
    }

    PendingClick capture(long linkId, Instant at, String ip, String userAgent, String referer) {
        return new PendingClick(
                UUID.randomUUID(),
                linkId,
                at,
                geo.countryOf(ip).orElse(null),
                hasher.hash(ip, userAgent, at),
                userAgent == null || userAgent.length() <= MAX_UA_LENGTH ? userAgent : userAgent.substring(0, MAX_UA_LENGTH),
                referrerHost(referer));
    }

    ClickRow finish(PendingClick click) {
        UserAgentClassifier.Result ua = userAgents.classify(click.userAgent());
        return new ClickRow(click.eventId(), click.linkId(), click.at(), click.country(), ua.device(), ua.browser(), ua.os(),
                click.referrerHost(), click.visitorHash(), ua.bot());
    }

    /** Keeps only the host of the Referer: paths and query strings can carry tokens or personal data. */
    static String referrerHost(String referer) {
        if (referer == null || referer.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(referer.trim()).getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            return host.length() > MAX_HOST_LENGTH ? host.substring(0, MAX_HOST_LENGTH) : host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
