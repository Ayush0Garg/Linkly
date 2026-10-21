package com.ayush.shortener.link;

import java.net.URI;

import com.ayush.shortener.analytics.ClickRecorder;
import com.ayush.shortener.ratelimit.Policies;
import com.ayush.shortener.ratelimit.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Redirect", description = "The public short-link endpoint (no sign-in needed)")
class RedirectController {

    private final CachedLinkResolver resolver;
    private final ClickRecorder clicks;

    RedirectController(CachedLinkResolver resolver, ClickRecorder clicks) {
        this.resolver = resolver;
        this.clicks = clicks;
    }

    /**
     * 302 (not 301) and no-store on purpose: a cached permanent redirect would stop the browser from ever
     * hitting us again, so clicks would go uncounted and a deactivated link would keep working.
     */
    @GetMapping("/{code:[A-Za-z0-9_-]{3,32}}")
    @RateLimited(policy = Policies.REDIRECT)
    @ApiResponse(responseCode = "429", description = "Too many requests from this address; see Retry-After")
    @Operation(summary = "Follow a short link", description = "Redirects to the destination and records a click. HEAD is supported and not counted.")
    @ApiResponse(responseCode = "302", description = "Redirect to the destination (Location header)")
    @ApiResponse(responseCode = "404", description = "Unknown code")
    @ApiResponse(responseCode = "410", description = "Deactivated or expired")
    ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        ResolvedLink link = resolver.resolve(code); // 404 / 410 end here, so only live links are counted
        clicks.record(link.linkId(), request);         // non-blocking: just enqueues
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(link.longUrl()))
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
