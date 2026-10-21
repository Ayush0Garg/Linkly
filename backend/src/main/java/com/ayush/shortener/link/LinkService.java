package com.ayush.shortener.link;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.ayush.shortener.common.ApiException;
import com.ayush.shortener.common.Transactions;
import com.ayush.shortener.config.AppProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LinkService {

    private static final int MAX_CODE_ATTEMPTS = 5;

    private final LinkRepository links;
    private final CodeGenerator codeGenerator;
    private final UrlValidator urlValidator;
    private final AppProperties properties;
    private final Clock clock;
    private final LinkCache cache;

    public LinkService(LinkRepository links, CodeGenerator codeGenerator, UrlValidator urlValidator,
                       AppProperties properties, Clock clock, LinkCache cache) {
        this.cache = cache;
        this.links = links;
        this.codeGenerator = codeGenerator;
        this.urlValidator = urlValidator;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Deliberately not @Transactional: a unique-violation inside a transaction would poison it, and the
     * generated-code path wants to retry. Each repository call is its own short transaction instead.
     */
    public Link create(long userId, CreateLinkCommand command) {
        String longUrl = urlValidator.validate(command.longUrl());
        Instant now = clock.instant();
        if (command.expiresAt() != null && !command.expiresAt().isAfter(now)) {
            throw ApiException.badRequest("Expiry must be in the future");
        }
        boolean hasAlias = command.alias() != null && !command.alias().isBlank();
        Link link = hasAlias
                ? createWithAlias(userId, longUrl, command.alias().trim(), command.expiresAt(), now)
                : createWithGeneratedCode(userId, longUrl, command.expiresAt(), now);
        // Someone may have asked for this code before it existed, and "not found" is cached for a short while.
        cache.evict(link.getCode());
        return link;
    }

    private Link createWithAlias(long userId, String longUrl, String alias, Instant expiresAt, Instant now) {
        if (!ReservedCodes.VALID_CODE.matcher(alias).matches()) {
            throw ApiException.badRequest("Alias must be 3-32 characters: letters, digits, '-' or '_'");
        }
        if (ReservedCodes.isReserved(alias) || codeGenerator.usesReservedPrefix(alias)) {
            throw ApiException.conflict("That alias is reserved");
        }
        if (links.existsByCode(alias)) {
            throw ApiException.conflict("That alias is already taken");
        }
        try {
            return links.save(new Link(links.nextId(), alias, longUrl, userId, true, expiresAt, now));
        } catch (DataIntegrityViolationException e) {
            // Lost a race with another request for the same alias (the unique constraint is the real guard).
            if (links.existsByCode(alias)) {
                throw ApiException.conflict("That alias is already taken");
            }
            throw e;
        }
    }

    private Link createWithGeneratedCode(long userId, String longUrl, Instant expiresAt, Instant now) {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            long id = links.nextId();
            String code = codeGenerator.encode(id);
            // Aliases can't use our prefix, so this is purely defensive (e.g. rows written by hand or an old prefix).
            if (ReservedCodes.isReserved(code) || links.existsByCode(code)) {
                continue;
            }
            try {
                return links.save(new Link(id, code, longUrl, userId, false, expiresAt, now));
            } catch (DataIntegrityViolationException e) {
                if (!links.existsByCode(code)) {
                    throw e;
                }
            }
        }
        throw new IllegalStateException("Could not allocate a unique short code");
    }

    /**
     * The redirect hot path. 404 if unknown, 410 if deactivated or expired.
     * (A cache layer is added in front of this later; keep it free of side effects.)
     */
    @Transactional(readOnly = true)
    public LinkLookup lookup(String code) {
        return links.findByCode(code)
                .<LinkLookup>map(link -> link.isUsableAt(clock.instant())
                        ? new LinkLookup.Found(new ResolvedLink(link.getId(), link.getLongUrl()), link.getExpiresAt())
                        : new LinkLookup.Gone())
                .orElseGet(LinkLookup.NotFound::new);
    }

    /** Straight from the database, throwing 404 / 410. The redirect goes through {@link CachedLinkResolver} instead. */
    public ResolvedLink resolve(String code) {
        return switch (lookup(code)) {
            case LinkLookup.Found found -> found.link();
            case LinkLookup.NotFound notFound -> throw ApiException.notFound("Link not found");
            case LinkLookup.Gone gone -> throw ApiException.gone("This link is no longer available");
        };
    }

    @Transactional(readOnly = true)
    public Page<Link> listForUser(long userId, Pageable pageable) {
        return links.findByUserId(userId, pageable);
    }

    @Transactional(readOnly = true)
    public Link getOwned(long userId, long linkId) {
        return links.findById(linkId)
                .filter(l -> l.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Link not found"));
    }

    /** Ownership is enforced here: someone else's link looks exactly like a missing one (no IDOR leak). */
    @Transactional
    public void deactivate(long userId, long linkId) {
        Link link = getOwned(userId, linkId);
        link.deactivate();
        String code = link.getCode();
        // After the commit, so a reader cannot reload the still-active row in between; and once more a moment later.
        Transactions.afterCommit(() -> {
            cache.evict(code);
            cache.evictLater(code, Duration.ofSeconds(1));
        });
    }

    public String shortUrl(Link link) {
        String base = properties.baseUrl();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/" + link.getCode();
    }
}
