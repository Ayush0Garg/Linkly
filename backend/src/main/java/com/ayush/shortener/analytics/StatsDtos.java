package com.ayush.shortener.analytics;

import java.time.LocalDate;
import java.util.List;

/** Response shapes for the analytics API. Days are UTC calendar days. */
public final class StatsDtos {

    private StatsDtos() {
    }

    /**
     * @param clicks         human clicks (bots excluded)
     * @param uniqueVisitors distinct visitors, counted per day: the visitor hash rotates daily on purpose, so the
     *                       same person on two days counts twice
     * @param botClicks      clicks identified as crawlers, previews or scripts
     */
    public record Totals(long clicks, long uniqueVisitors, long botClicks) {
    }

    public record DailyPoint(LocalDate date, long clicks) {
    }

    public record Bucket(String key, long count) {
    }

    public record TopLink(long linkId, String code, String shortUrl, String longUrl, long clicks) {
    }

    public record LinkStats(int days, Totals totals, List<DailyPoint> timeseries, List<Bucket> countries,
                            List<Bucket> devices, List<Bucket> browsers, List<Bucket> operatingSystems,
                            List<Bucket> referrers) {
    }

    public record Overview(int days, Totals totals, List<DailyPoint> timeseries, List<TopLink> topLinks) {
    }
}
