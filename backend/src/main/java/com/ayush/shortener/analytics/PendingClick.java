package com.ayush.shortener.analytics;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A click as it travels between the redirect and the database, in memory or through the Redis stream.
 *
 * <p>Deliberately holds no IP address: the country and the (daily-rotating) visitor hash are worked out on the redirect
 * itself, and the address is dropped right there, so it can never end up in a durable stream. The User-Agent text does
 * travel (it is parsed later, off the redirect path) and is deleted from the stream once the click is stored.
 *
 * @param eventId made once, at the redirect; the database ignores a second delivery of the same id
 */
record PendingClick(UUID eventId, long linkId, Instant at, String country, String visitorHash, String userAgent,
                    String referrerHost) {

    private static final String VERSION = "1";

    /** The stream entry body. Absent fields mean "unknown". */
    Map<String, String> toFields() {
        Map<String, String> fields = new HashMap<>(8);
        fields.put("v", VERSION);
        fields.put("id", eventId.toString());
        fields.put("link", Long.toString(linkId));
        fields.put("at", Long.toString(at.toEpochMilli()));
        fields.put("vh", visitorHash);
        putIfPresent(fields, "c", country);
        putIfPresent(fields, "ua", userAgent);
        putIfPresent(fields, "ref", referrerHost);
        return fields;
    }

    /** @throws IllegalArgumentException for an entry that cannot be read: it will never become readable by retrying */
    static PendingClick fromFields(Map<String, String> fields) {
        try {
            if (!VERSION.equals(fields.get("v"))) {
                throw new IllegalArgumentException("unknown entry version: " + fields.get("v"));
            }
            return new PendingClick(
                    UUID.fromString(required(fields, "id")),
                    Long.parseLong(required(fields, "link")),
                    Instant.ofEpochMilli(Long.parseLong(required(fields, "at"))),
                    fields.get("c"),
                    required(fields, "vh"),
                    fields.get("ua"),
                    fields.get("ref"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("malformed number in entry", e);
        }
    }

    private static String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("entry has no '" + name + "'");
        }
        return value;
    }

    private static void putIfPresent(Map<String, String> fields, String name, String value) {
        if (value != null && !value.isEmpty()) {
            fields.put(name, value);
        }
    }
}
