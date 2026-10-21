package com.ayush.shortener.link;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.ayush.shortener.common.ApiException;
import com.ayush.shortener.ratelimit.Policies;
import com.ayush.shortener.ratelimit.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/links/{id}/qr")
@Tag(name = "Links")
class QrController {

    private final LinkService linkService;
    private final QrCodeService qr;

    QrController(LinkService linkService, QrCodeService qr) {
        this.linkService = linkService;
        this.qr = qr;
    }

    @GetMapping
    @RateLimited(policy = Policies.QR, by = RateLimited.By.USER)
    @ApiResponse(responseCode = "429", description = "Too many QR codes requested; see Retry-After")
    @Operation(summary = "QR code for a link",
            description = "A scannable QR code of the short URL, as a PNG or a scalable SVG for print. "
                    + "For PNG, size is the target in pixels: the image is the largest whole multiple of the module count that fits.")
    @ApiResponse(responseCode = "400", description = "Unknown format, or size outside 64-2048")
    @ApiResponse(responseCode = "404", description = "No such link, or it belongs to someone else")
    ResponseEntity<byte[]> qrCode(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                  @RequestParam(defaultValue = "png") String format,
                                  @RequestParam(defaultValue = "512") @Min(64) @Max(2048) int size) {
        Link link = linkService.getOwned(Long.parseLong(jwt.getSubject()), id);
        String url = linkService.shortUrl(link);

        boolean svg = "svg".equalsIgnoreCase(format);
        if (!svg && !"png".equalsIgnoreCase(format)) {
            throw ApiException.badRequest("format must be png or svg");
        }
        byte[] body = svg ? qr.svg(url, size).getBytes(StandardCharsets.UTF_8) : qr.png(url, size);

        return ResponseEntity.ok()
                .contentType(svg ? MediaType.parseMediaType("image/svg+xml") : MediaType.IMAGE_PNG)
                // The code is made only of [A-Za-z0-9_-], so it is safe as a file name.
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(link.getCode() + (svg ? ".svg" : ".png")).build().toString())
                // A link's code and the site's base URL never change, so the image is stable: let the browser keep it.
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(body);
    }
}
