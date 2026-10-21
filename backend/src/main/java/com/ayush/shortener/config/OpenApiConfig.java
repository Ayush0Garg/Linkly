package com.ayush.shortener.config;

import java.net.URI;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("Linkly API")
                .version("v1")
                .description("""
                        URL shortener with click analytics.

                        **Trying the protected endpoints from this page**
                        1. Run `GET /api/auth/csrf` (sets the CSRF cookie; this page then sends it with every request).
                        2. Run `POST /api/auth/register` or `POST /api/auth/login`. Your browser stores the session cookies.
                        3. Everything else (`/api/links`, `/api/stats`, ...) now works as you.

                        Sessions are HttpOnly cookies, so there is no token to paste into an "Authorize" box.
                        Short links themselves (`GET /{code}`) are public."""));
    }

    /**
     * Every error response (4xx and 5xx) has the same body, RFC 9457 problem+json. Without this, the docs show each
     * error with the endpoint's success body, which is plainly wrong (a 429 on sign-in "returns" a user).
     */
    @Bean
    OpenApiCustomizer problemJsonForErrors() {
        return openApi -> {
            Schema<?> problem = new ObjectSchema()
                    .description("An error. Always the same shape, whatever went wrong.")
                    .addProperty("title", new StringSchema().description("The HTTP status in words"))
                    .addProperty("status", new IntegerSchema().description("The HTTP status code"))
                    .addProperty("detail", new StringSchema().description("A sentence that can be shown to the user"))
                    .addProperty("instance", new StringSchema().description("The path that was called"))
                    .addProperty("errors", new ObjectSchema().additionalProperties(new StringSchema())
                            .description("Validation failures only: field name to message"))
                    .addProperty("retryAfterSeconds", new IntegerSchema().description("429 only: how long to wait (also the Retry-After header)"));
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents().addSchemas("Problem", problem);

            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                if (operation.getResponses() == null) {
                    return;
                }
                operation.getResponses().forEach((code, response) -> {
                    if (code.startsWith("4") || code.startsWith("5")) {
                        response.setContent(new Content().addMediaType("application/problem+json",
                                new MediaType().schema(new Schema<>().$ref("#/components/schemas/Problem"))));
                    }
                });
            }));
        };
    }

    /**
     * "/" lands on the API docs unless the web app is served from here (app.web.spa-enabled), which then owns "/".
     * Disappears together with the docs.
     */
    @RestController
    @Hidden
    @ConditionalOnExpression("${springdoc.swagger-ui.enabled:true} and !${app.web.spa-enabled:false}")
    static class DocsLandingController {

        @GetMapping("/")
        ResponseEntity<Void> root() {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/swagger-ui/index.html")).build();
        }
    }
}
