package com.ayush.shortener.web;

import java.time.Duration;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Static files of the React app, with the cache rules that make a deploy safe and repeat visits fast. */
@Configuration
@ConditionalOnProperty(name = "app.web.spa-enabled", havingValue = "true")
class SpaWebConfig implements WebMvcConfigurer {

    private final WebProperties web;
    private final ResourceLoader loader;

    SpaWebConfig(WebProperties web, ResourceLoader loader) {
        this.web = web;
        this.loader = loader;
    }

    /** Refuse to start with the web app switched on but not built in: better than serving 500s to every visitor. */
    @PostConstruct
    void requireTheBuiltApp() {
        if (!loader.getResource(web.indexLocation()).exists()) {
            throw new IllegalStateException("app.web.spa-enabled is true but " + web.indexLocation() + " does not exist. "
                    + "Build the frontend into the jar (see the Dockerfile) or point app.web.static-location at its dist folder.");
        }
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // File names under /assets contain a hash of their content, so a given URL never changes: cache it for good.
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(web.folder() + "assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler(SpaRoutes.FAVICON)
                .addResourceLocations(web.folder())
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic());
    }
}
