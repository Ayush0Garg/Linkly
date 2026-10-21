package com.ayush.shortener.link;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;

/** Request/response shapes for /api/links. */
final class LinkDtos {

    private LinkDtos() {
    }

    record CreateLinkRequest(
            @NotBlank @Size(max = UrlValidator.MAX_LENGTH) String longUrl,
            @Size(max = 32) String alias,
            Instant expiresAt) {

        CreateLinkCommand toCommand() {
            return new CreateLinkCommand(longUrl, alias, expiresAt);
        }
    }

    /** @param totalClicks all-time human clicks (bots excluded) */
    record LinkResponse(long id, String code, String shortUrl, String longUrl, boolean custom, boolean active,
                        Instant expiresAt, Instant createdAt, long totalClicks) {
        static LinkResponse from(Link link, String shortUrl, long totalClicks) {
            return new LinkResponse(link.getId(), link.getCode(), shortUrl, link.getLongUrl(), link.isCustom(),
                    link.isActive(), link.getExpiresAt(), link.getCreatedAt(), totalClicks);
        }
    }

    record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
        static <T> PageResponse<T> from(Page<T> page) {
            return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                    page.getTotalElements(), page.getTotalPages());
        }
    }
}
