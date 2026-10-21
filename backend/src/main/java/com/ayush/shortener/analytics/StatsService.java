package com.ayush.shortener.analytics;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.ayush.shortener.analytics.StatsDtos.DailyPoint;
import com.ayush.shortener.analytics.StatsDtos.LinkStats;
import com.ayush.shortener.analytics.StatsDtos.Overview;
import com.ayush.shortener.analytics.StatsDtos.TopLink;
import com.ayush.shortener.analytics.StatsRepository.Dimension;
import com.ayush.shortener.analytics.StatsRepository.Scope;
import com.ayush.shortener.common.ApiException;
import com.ayush.shortener.config.AppProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatsService {

    public static final int MAX_DAYS = 90;
    private static final int TOP_LINKS = 5;

    private final StatsRepository repository;
    private final Clock clock;
    private final String baseUrl;

    StatsService(StatsRepository repository, Clock clock, AppProperties properties) {
        this.repository = repository;
        this.clock = clock;
        String base = properties.baseUrl();
        this.baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** One link's dashboard. Someone else's link is indistinguishable from a missing one. */
    @Transactional(readOnly = true)
    public LinkStats linkStats(long userId, long linkId, int days) {
        if (!repository.ownsLink(userId, linkId)) {
            throw ApiException.notFound("Link not found");
        }
        LocalDate from = windowStart(days);
        Scope scope = Scope.LINK;
        return new LinkStats(
                days,
                repository.totals(scope, linkId, from),
                series(repository.dailyClicks(scope, linkId, from), from, days),
                repository.breakdown(scope, linkId, from, Dimension.COUNTRY),
                repository.breakdown(scope, linkId, from, Dimension.DEVICE),
                repository.breakdown(scope, linkId, from, Dimension.BROWSER),
                repository.breakdown(scope, linkId, from, Dimension.OS),
                repository.breakdown(scope, linkId, from, Dimension.REFERRER));
    }

    /** Everything the signed-in user owns, rolled up. */
    @Transactional(readOnly = true)
    public Overview overview(long userId, int days) {
        LocalDate from = windowStart(days);
        List<TopLink> top = repository.topLinks(userId, from, TOP_LINKS).stream()
                .map(r -> new TopLink(r.linkId(), r.code(), baseUrl + "/" + r.code(), r.longUrl(), r.clicks()))
                .toList();
        return new Overview(
                days,
                repository.totals(Scope.USER, userId, from),
                series(repository.dailyClicks(Scope.USER, userId, from), from, days),
                top);
    }

    @Transactional(readOnly = true)
    public Map<Long, Long> totalClicks(Collection<Long> linkIds) {
        return repository.totalClicks(linkIds);
    }

    /** Window = today and the (days - 1) days before it, in UTC. */
    private LocalDate windowStart(int days) {
        return LocalDate.now(clock).minusDays(days - 1L);
    }

    /** Every day in the window appears, with 0 where nothing happened, so charts have no gaps. */
    private static List<DailyPoint> series(Map<LocalDate, Long> byDay, LocalDate from, int days) {
        List<DailyPoint> points = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            LocalDate day = from.plusDays(i);
            points.add(new DailyPoint(day, byDay.getOrDefault(day, 0L)));
        }
        return points;
    }
}
