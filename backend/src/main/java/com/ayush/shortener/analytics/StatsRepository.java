package com.ayush.shortener.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ayush.shortener.analytics.StatsDtos.Bucket;
import com.ayush.shortener.analytics.StatsDtos.Totals;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read queries. Totals and time series come from the small pre-aggregated link_daily_stats table; unique visitors and
 * breakdowns need the raw events, bounded by a time window and served by the (link_id, clicked_at) index.
 * SQL fragments are fixed constants chosen from the enums below - request values are only ever bound parameters.
 */
@Repository
class StatsRepository {

    /** Which links a query covers; :id is the link id or the owning user id respectively. */
    enum Scope {
        LINK("link_id = :id"),
        USER("link_id IN (SELECT id FROM links WHERE user_id = :id)");

        final String filter;

        Scope(String filter) {
            this.filter = filter;
        }
    }

    enum Dimension {
        COUNTRY("country", "Unknown"),
        DEVICE("device", "Unknown"),
        BROWSER("browser", "Unknown"),
        OS("os", "Unknown"),
        REFERRER("referrer_host", "Direct");

        final String column;
        final String missingLabel;

        Dimension(String column, String missingLabel) {
            this.column = column;
            this.missingLabel = missingLabel;
        }
    }

    private static final int BREAKDOWN_LIMIT = 10;

    private final JdbcClient jdbc;

    StatsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean ownsLink(long userId, long linkId) {
        return jdbc.sql("SELECT count(*) FROM links WHERE id = :link AND user_id = :user")
                .param("link", linkId).param("user", userId)
                .query(Long.class).single() > 0;
    }

    Totals totals(Scope scope, long id, LocalDate fromDay) {
        Instant from = fromDay.atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        long[] rollup = jdbc.sql("SELECT coalesce(sum(clicks), 0), coalesce(sum(bot_clicks), 0) FROM link_daily_stats "
                        + "WHERE " + scope.filter + " AND day >= :fromDay")
                .param("id", id).param("fromDay", fromDay)
                .query((rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}).single();
        long unique = jdbc.sql("SELECT count(DISTINCT visitor_hash) FROM click_events "
                        + "WHERE " + scope.filter + " AND clicked_at >= :from AND NOT bot")
                .param("id", id).param("from", java.sql.Timestamp.from(from))
                .query(Long.class).single();
        return new Totals(rollup[0], unique, rollup[1]);
    }

    Map<LocalDate, Long> dailyClicks(Scope scope, long id, LocalDate fromDay) {
        Map<LocalDate, Long> byDay = new HashMap<>();
        jdbc.sql("SELECT day, sum(clicks) AS clicks FROM link_daily_stats WHERE " + scope.filter
                        + " AND day >= :fromDay GROUP BY day")
                .param("id", id).param("fromDay", fromDay)
                .query((rs, i) -> {
                    byDay.put(rs.getObject("day", LocalDate.class), rs.getLong("clicks"));
                    return null;
                }).list();
        return byDay;
    }

    List<Bucket> breakdown(Scope scope, long id, LocalDate fromDay, Dimension dimension) {
        Instant from = fromDay.atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        return jdbc.sql("SELECT coalesce(" + dimension.column + ", :missing) AS key, count(*) AS clicks FROM click_events "
                        + "WHERE " + scope.filter + " AND clicked_at >= :from AND NOT bot "
                        + "GROUP BY 1 ORDER BY clicks DESC, key LIMIT " + BREAKDOWN_LIMIT)
                .param("id", id).param("from", java.sql.Timestamp.from(from)).param("missing", dimension.missingLabel)
                .query((rs, i) -> new Bucket(rs.getString("key"), rs.getLong("clicks")))
                .list();
    }

    record TopLinkRow(long linkId, String code, String longUrl, long clicks) {
    }

    List<TopLinkRow> topLinks(long userId, LocalDate fromDay, int limit) {
        return jdbc.sql("SELECT l.id, l.code, l.long_url, sum(s.clicks) AS clicks FROM link_daily_stats s "
                        + "JOIN links l ON l.id = s.link_id WHERE l.user_id = :user AND s.day >= :fromDay "
                        + "GROUP BY l.id, l.code, l.long_url HAVING sum(s.clicks) > 0 "
                        + "ORDER BY clicks DESC, l.id DESC LIMIT :limit")
                .param("user", userId).param("fromDay", fromDay).param("limit", limit)
                .query((rs, i) -> new TopLinkRow(rs.getLong("id"), rs.getString("code"), rs.getString("long_url"),
                        rs.getLong("clicks")))
                .list();
    }

    /** All-time human clicks for a set of links, in one query (no N+1 when listing links). */
    Map<Long, Long> totalClicks(Collection<Long> linkIds) {
        Map<Long, Long> result = new HashMap<>();
        if (linkIds.isEmpty()) {
            return result;
        }
        jdbc.sql("SELECT link_id, sum(clicks) AS clicks FROM link_daily_stats WHERE link_id IN (:ids) GROUP BY link_id")
                .param("ids", linkIds)
                .query((rs, i) -> {
                    result.put(rs.getLong("link_id"), rs.getLong("clicks"));
                    return null;
                }).list();
        return result;
    }
}
