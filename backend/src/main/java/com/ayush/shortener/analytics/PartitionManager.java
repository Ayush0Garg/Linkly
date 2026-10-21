package com.ayush.shortener.analytics;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ayush.shortener.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns the lifecycle of click_events partitions: creates the month a click belongs to (before it is needed) and
 * drops months past the retention window. Safe to run on several instances at once (advisory lock + IF NOT EXISTS).
 */
@Component
public class PartitionManager {

    private static final Logger log = LoggerFactory.getLogger(PartitionManager.class);
    private static final long LOCK_KEY = 7_243_001L;
    private static final Pattern PARTITION_NAME = Pattern.compile("click_events_y(\\d{4})m(\\d{2})");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int retentionDays;
    /** Months known to exist, so the hot path does not ask Postgres every batch. */
    private final Set<YearMonth> known = ConcurrentHashMap.newKeySet();

    PartitionManager(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock, AppProperties properties) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.retentionDays = properties.analytics().retentionDays();
    }

    static String tableName(YearMonth month) {
        return "click_events_y%04dm%02d".formatted(month.getYear(), month.getMonthValue());
    }

    public void ensure(YearMonth month) {
        if (known.contains(month)) {
            return;
        }
        // Built only from integers, never from user input.
        String sql = "CREATE TABLE IF NOT EXISTS %s PARTITION OF click_events FOR VALUES FROM ('%s 00:00:00+00') TO ('%s 00:00:00+00')"
                .formatted(tableName(month), month.atDay(1), month.plusMonths(1).atDay(1));
        tx.executeWithoutResult(status -> {
            jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> { }, LOCK_KEY); // serialise concurrent instances
            jdbc.execute(sql);
        });
        known.add(month);
    }

    public void ensure(Set<YearMonth> months) {
        months.forEach(this::ensure);
    }

    /** Current month plus the next two, so a month boundary never catches the writer without a partition. */
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "0 5 0 * * *", zone = "UTC")
    public void ensureUpcoming() {
        YearMonth now = YearMonth.now(clock);
        ensure(now);
        ensure(now.plusMonths(1));
        ensure(now.plusMonths(2));
    }

    /** Drops whole months that ended before the retention cutoff. Daily rollups are untouched. */
    @Scheduled(cron = "0 35 3 * * *", zone = "UTC")
    public int dropExpired() {
        LocalDate cutoff = LocalDate.now(clock).minusDays(retentionDays);
        List<String> tables = jdbc.queryForList(
                "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid "
                        + "WHERE i.inhparent = 'click_events'::regclass", String.class);
        int dropped = 0;
        for (String table : tables) {
            Matcher m = PARTITION_NAME.matcher(table); // also guarantees the name is safe to splice into DDL
            if (!m.matches()) {
                continue;
            }
            YearMonth month = YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            if (month.plusMonths(1).atDay(1).isBefore(cutoff.plusDays(1))) { // the whole month is older than the cutoff
                jdbc.execute("DROP TABLE IF EXISTS " + table);
                known.remove(month);
                dropped++;
                log.info("Dropped expired click partition {}", table);
            }
        }
        return dropped;
    }

    static YearMonth monthOf(Instant instant) {
        return YearMonth.from(instant.atZone(ZoneOffset.UTC));
    }
}
