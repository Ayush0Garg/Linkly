package com.ayush.shortener.analytics;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persists a batch of clicks exactly once, however many times it is handed over.
 *
 * <p>One SQL statement does everything, atomically: insert the events that are new (an id already stored is skipped), then
 * add to the daily totals using ONLY the rows that were really inserted. So a retry, a message recovered after a crash, or
 * two instances processing the same click at the same moment all leave both the events and the totals correct. Two
 * transactions racing on the same id serialise on the unique index; the loser inserts nothing and counts nothing.
 *
 * <p>Plain SQL with array parameters rather than JPA on purpose: this is a high-volume append path, and one round trip
 * per batch is the fastest way to do it.
 */
@Component
public class ClickWriter {

    private static final String UPSERT = """
            WITH incoming AS (
                SELECT * FROM unnest(?::uuid[], ?::bigint[], ?::bigint[], ?::text[], ?::text[], ?::text[], ?::text[], ?::text[], ?::text[], ?::boolean[])
                    AS t(event_id, link_id, clicked_ms, country, device, browser, os, referrer_host, visitor_hash, bot)
            ),
            inserted AS (
                INSERT INTO click_events (event_id, link_id, clicked_at, country, device, browser, os, referrer_host, visitor_hash, bot)
                SELECT event_id, link_id, timestamptz 'epoch' + clicked_ms * interval '1 millisecond',
                       country, device, browser, os, referrer_host, visitor_hash, bot
                FROM incoming
                ON CONFLICT (event_id, clicked_at) DO NOTHING
                RETURNING link_id, clicked_at, bot
            ),
            rolled AS (
                -- ORDER BY: every instance takes the row locks in the same order, so concurrent writers cannot deadlock
                INSERT INTO link_daily_stats (link_id, day, clicks, bot_clicks)
                SELECT link_id, (clicked_at AT TIME ZONE 'UTC')::date,
                       count(*) FILTER (WHERE NOT bot), count(*) FILTER (WHERE bot)
                FROM inserted GROUP BY 1, 2 ORDER BY 1, 2
                ON CONFLICT (link_id, day) DO UPDATE
                SET clicks = link_daily_stats.clicks + EXCLUDED.clicks,
                    bot_clicks = link_daily_stats.bot_clicks + EXCLUDED.bot_clicks
            )
            SELECT count(*) FROM inserted""";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PartitionManager partitions;

    ClickWriter(JdbcTemplate jdbc, TransactionTemplate tx, PartitionManager partitions) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.partitions = partitions;
    }

    /** @return how many of the rows were new; the rest were already stored and were left alone */
    public int write(List<ClickRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        // DDL (if a month is new) happens before, and outside, the data transaction.
        Set<YearMonth> months = new HashSet<>();
        rows.forEach(r -> months.add(PartitionManager.monthOf(r.at())));
        partitions.ensure(months);

        Integer inserted = tx.execute(status -> jdbc.execute((ConnectionCallback<Integer>) connection -> insert(connection, rows)));
        return inserted == null ? 0 : inserted;
    }

    private static int insert(Connection connection, List<ClickRow> rows) throws SQLException {
        int n = rows.size();
        UUID[] ids = new UUID[n];
        Long[] links = new Long[n];
        Long[] millis = new Long[n];
        String[] country = new String[n];
        String[] device = new String[n];
        String[] browser = new String[n];
        String[] os = new String[n];
        String[] referrer = new String[n];
        String[] visitor = new String[n];
        Boolean[] bot = new Boolean[n];
        for (int i = 0; i < n; i++) {
            ClickRow r = rows.get(i);
            ids[i] = r.eventId();
            links[i] = r.linkId();
            millis[i] = r.at().toEpochMilli();
            country[i] = r.country();
            device[i] = r.device();
            browser[i] = r.browser();
            os[i] = r.os();
            referrer[i] = r.referrerHost();
            visitor[i] = r.visitorHash();
            bot[i] = r.bot();
        }
        try (var statement = connection.prepareStatement(UPSERT)) {
            statement.setArray(1, connection.createArrayOf("uuid", ids));
            statement.setArray(2, connection.createArrayOf("bigint", links));
            statement.setArray(3, connection.createArrayOf("bigint", millis));
            statement.setArray(4, connection.createArrayOf("text", country));
            statement.setArray(5, connection.createArrayOf("text", device));
            statement.setArray(6, connection.createArrayOf("text", browser));
            statement.setArray(7, connection.createArrayOf("text", os));
            statement.setArray(8, connection.createArrayOf("text", referrer));
            statement.setArray(9, connection.createArrayOf("text", visitor));
            statement.setArray(10, connection.createArrayOf("boolean", bot));
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }
}
