package com.ayush.shortener.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import io.swagger.v3.oas.annotations.Hidden;

/**
 * Serves the React app's single HTML page for every client-side route (so a refresh on /links/42 works). HEAD is
 * answered by the same handler. Anything under /api that does not exist is NOT served this page: it falls through to
 * the normal JSON 404.
 */
@Controller
@Hidden
@ConditionalOnProperty(name = "app.web.spa-enabled", havingValue = "true")
class SpaController {

    private final Resource index;

    SpaController(WebProperties web, ResourceLoader loader) {
        this.index = loader.getResource(web.indexLocation());
    }

    @GetMapping({"/", "/login", "/register", "/links", "/links/{id}", "/index.html"})
    ResponseEntity<Resource> shell() {
        // no-cache = "revalidate every time": a new deploy is picked up at once, and it names the new hashed bundles
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noCache()).body(index);
    }
}
