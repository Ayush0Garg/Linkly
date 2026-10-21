package com.ayush.shortener.analytics;

import java.time.Instant;
import java.util.UUID;

/**
 * One enriched click, exactly as stored in click_events. Null dimensions mean "unknown".
 *
 * @param eventId made when the click happened and never changed afterwards: storing the same id twice is a no-op
 */
public record ClickRow(UUID eventId, long linkId, Instant at, String country, String device, String browser, String os,
                       String referrerHost, String visitorHash, boolean bot) {
}
