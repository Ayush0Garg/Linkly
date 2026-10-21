package com.ayush.shortener.analytics;

import com.ayush.shortener.analytics.StatsDtos.LinkStats;
import com.ayush.shortener.analytics.StatsDtos.Overview;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only and always scoped to the signed-in user (id comes from the verified token). */
@RestController
@RequestMapping("/api")
@Tag(name = "Analytics", description = "Click statistics for your links (UTC days; bots are reported separately)")
class StatsController {

    private final StatsService stats;

    StatsController(StatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/links/{id}/stats")
    @Operation(summary = "Statistics for one link",
            description = "Totals, a zero-filled daily series, and the top 10 countries, devices, browsers, operating systems and referrers. Unique visitors are counted per day.")
    @ApiResponse(responseCode = "400", description = "days outside 1-90")
    @ApiResponse(responseCode = "404", description = "No such link, or it belongs to someone else")
    LinkStats linkStats(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                        @RequestParam(defaultValue = "30") @Min(1) @Max(StatsService.MAX_DAYS) int days) {
        return stats.linkStats(Long.parseLong(jwt.getSubject()), id, days);
    }

    @GetMapping("/stats/overview")
    @Operation(summary = "Statistics across all my links", description = "Totals, a daily series and the top 5 links by clicks.")
    @ApiResponse(responseCode = "400", description = "days outside 1-90")
    Overview overview(@AuthenticationPrincipal Jwt jwt,
                      @RequestParam(defaultValue = "30") @Min(1) @Max(StatsService.MAX_DAYS) int days) {
        return stats.overview(Long.parseLong(jwt.getSubject()), days);
    }
}
