package com.ayush.shortener.link;

import java.net.URI;
import java.util.Map;

import com.ayush.shortener.analytics.StatsService;
import com.ayush.shortener.ratelimit.Policies;
import com.ayush.shortener.ratelimit.RateLimited;
import com.ayush.shortener.link.LinkDtos.CreateLinkRequest;
import com.ayush.shortener.link.LinkDtos.LinkResponse;
import com.ayush.shortener.link.LinkDtos.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Every endpoint is scoped to the signed-in user; the user id always comes from the verified JWT, never the request. */
@RestController
@RequestMapping("/api/links")
@Tag(name = "Links", description = "Create and manage your short links")
class LinkController {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final LinkService linkService;
    private final StatsService stats;

    LinkController(LinkService linkService, StatsService stats) {
        this.linkService = linkService;
        this.stats = stats;
    }

    @PostMapping
    @RateLimited(policy = Policies.LINKS_CREATE, by = RateLimited.By.USER)
    @ApiResponse(responseCode = "429", description = "Too many links created in a short time; see Retry-After")
    @Operation(summary = "Create a short link",
            description = "Without an alias you get a generated code like linkly-KZrNBm. Aliases are 3-32 characters (letters, digits, - _) and cannot start with linkly-.")
    @ApiResponse(responseCode = "201", description = "Link created")
    @ApiResponse(responseCode = "400", description = "Invalid URL (only http/https, up to 8192 characters, no private addresses), alias or expiry")
    @ApiResponse(responseCode = "409", description = "Alias taken or reserved")
    ResponseEntity<LinkResponse> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateLinkRequest request) {
        Link link = linkService.create(userId(jwt), request.toCommand());
        return ResponseEntity.created(URI.create("/api/links/" + link.getId())).body(toResponse(link, 0));
    }

    @GetMapping
    @Operation(summary = "List my links", description = "Newest first, with each link's all-time human click count.")
    PageResponse<LinkResponse> list(@AuthenticationPrincipal Jwt jwt,
                                    @RequestParam(defaultValue = "0") @Min(0) int page,
                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Page<Link> links = linkService.listForUser(userId(jwt), PageRequest.of(page, size, NEWEST_FIRST));
        Map<Long, Long> clicks = stats.totalClicks(links.map(Link::getId).getContent()); // one query for the whole page
        return PageResponse.from(links.map(l -> toResponse(l, clicks.getOrDefault(l.getId(), 0L))));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one of my links")
    @ApiResponse(responseCode = "404", description = "No such link, or it belongs to someone else")
    LinkResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        Link link = linkService.getOwned(userId(jwt), id);
        return toResponse(link, stats.totalClicks(java.util.List.of(link.getId())).getOrDefault(link.getId(), 0L));
    }

    /** Soft delete: the code stops redirecting (410) but is never reused. */
    @DeleteMapping("/{id}")
    @Operation(summary = "Deactivate a link", description = "The short link stops redirecting (410 Gone). The code is never reused.")
    @ApiResponse(responseCode = "204", description = "Deactivated")
    @ApiResponse(responseCode = "404", description = "No such link, or it belongs to someone else")
    ResponseEntity<Void> deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        linkService.deactivate(userId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    private LinkResponse toResponse(Link link, long totalClicks) {
        return LinkResponse.from(link, linkService.shortUrl(link), totalClicks);
    }

    private static long userId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
