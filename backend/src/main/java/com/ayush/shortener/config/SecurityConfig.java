package com.ayush.shortener.config;

import java.util.Set;

import com.ayush.shortener.web.SpaRoutes;
import com.ayush.shortener.web.WebProperties;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.util.WebUtils;

/**
 * - Stateless: identity comes from a short-lived JWT in an HttpOnly cookie (no server session).
 * - Because the credential is a cookie, the browser attaches it automatically, so mutating requests are
 *   CSRF-protected (double-submit XSRF-TOKEN cookie + X-XSRF-TOKEN header) on top of SameSite cookies.
 * - The public surface is only: the redirect, health, and the sign-in endpoints.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    static final String ACCESS_COOKIE = "access_token";

    private static final String API_CSP = "default-src 'none'; frame-ancestors 'none'";
    private static final String DOCS_CSP = "default-src 'self'; img-src 'self' data:; font-src 'self' data:; "
            + "style-src 'self' 'unsafe-inline'; frame-ancestors 'none'";
    private static final RequestMatcher DOCS_PAGES = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher("/swagger-ui.html"),
            PathPatternRequestMatcher.withDefaults().matcher("/swagger-ui/**"));

    /** Endpoints that must work without (or despite a stale) access token, so we never even try to parse it. */
    private static final Set<String> TOKENLESS_PATHS = Set.of(
            "/api/auth/csrf", "/api/auth/register", "/api/auth/login", "/api/auth/google",
            "/api/auth/refresh", "/api/auth/logout");

    /**
     * Content-Security-Policy of the web app's own pages (only when the app is served from here). Same-origin for
     * everything, nothing inline for scripts. Style attributes are allowed because the charts position things with them.
     * Google's sign-in script, frame and stylesheet are added only when Google sign-in is configured, because only then
     * does the frontend load them.
     */
    static String spaCsp(boolean googleSignIn) {
        String google = googleSignIn ? " https://accounts.google.com/gsi/" : "";
        return "default-src 'self'; "
                + "script-src 'self'" + (googleSignIn ? " https://accounts.google.com/gsi/client" : "") + "; "
                + "style-src 'self' 'unsafe-inline'" + (googleSignIn ? " https://accounts.google.com/gsi/style" : "") + "; "
                + "img-src 'self' data:; font-src 'self' data:; "
                + "connect-src 'self'" + google + "; "
                + (googleSignIn ? "frame-src https://accounts.google.com/gsi/; " : "")
                + "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder, AppProperties properties,
                                            WebProperties web,
                                            @Value("${app.health.full-public:true}") boolean fullHealthPublic) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse(); // the SPA must read it
        csrfRepository.setCookieCustomizer(c -> c.secure(properties.auth().cookieSecure()).sameSite("Lax").path("/"));

        // Paths that get a page-style policy instead of the API's. With the web app off these are the docs pages only.
        RequestMatcher spaPages = web.spaEnabled() ? matchAny(SpaRoutes.publicPaths()) : request -> false;
        RequestMatcher webPages = new OrRequestMatcher(DOCS_PAGES, spaPages);

        http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        // Spring's default strategy deletes the CSRF cookie whenever a request authenticates. With a
                        // server session that happens once, at login. Here the JWT cookie authenticates EVERY request,
                        // so the token vanished after each write and every second POST/DELETE came back 403.
                        .sessionAuthenticationStrategy((authentication, request, response) -> { }))
                .authorizeHttpRequests(auth -> {
                    if (web.spaEnabled()) {
                        // the web app's pages and files are public (the app itself sends signed-out users to /login)
                        auth.requestMatchers(HttpMethod.GET, SpaRoutes.publicPaths()).permitAll();
                        auth.requestMatchers(HttpMethod.HEAD, SpaRoutes.publicPaths()).permitAll();
                    }
                    auth
                        .requestMatchers(HttpMethod.GET, "/").permitAll()      // the web app, or a redirect to the API docs
                        .requestMatchers(HttpMethod.HEAD, "/").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // HEAD too: link previews, uptime checks and `curl -I` use it (served by the GET handler)
                        .requestMatchers(HttpMethod.GET, "/{code:[A-Za-z0-9_-]{3,32}}").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/{code:[A-Za-z0-9_-]{3,32}}").permitAll()
                        // The aggregate /actuator/health also asks the database, so on a public deployment with a database
                        // that sleeps when idle (Neon), anyone calling it would keep that database awake. There only the
                        // probes (which never touch the database) are public: app.health.full-public=false.
                        .requestMatchers(fullHealthPublic ? new String[] {"/actuator/health/**"}
                                : new String[] {"/actuator/health/liveness", "/actuator/health/readiness"}).permitAll()
                        // Public only where the endpoint is exposed at all. Production exposes just health,info
                        // (MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE, set in the Dockerfile and render.yaml), so there it is a 404.
                        .requestMatchers("/actuator/prometheus").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/google",
                                "/api/auth/refresh", "/api/auth/logout").permitAll()
                        .anyRequest().authenticated();
                })
                .addFilter(jwtCookieFilter(jwtDecoder))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .headers(h -> h
                        // The API only returns JSON / redirects, so lock everything down. Swagger UI and the web app are
                        // real web pages that need their own scripts, styles and fonts, so each gets a looser
                        // (still same-origin) policy, and only for its own paths.
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(DOCS_PAGES,
                                new StaticHeadersWriter("Content-Security-Policy", DOCS_CSP)))
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(new NegatedRequestMatcher(webPages),
                                new StaticHeadersWriter("Content-Security-Policy", API_CSP)))
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(spaPages,
                                new StaticHeadersWriter("Content-Security-Policy",
                                        spaCsp(StringUtils.hasText(properties.auth().googleClientId())))))
                        .frameOptions(f -> f.deny()));
        return http.build();
    }

    private static RequestMatcher matchAny(String... patterns) {
        return new OrRequestMatcher(java.util.Arrays.stream(patterns)
                .map(p -> (RequestMatcher) PathPatternRequestMatcher.withDefaults().matcher(p)).toList());
    }

    /**
     * Authenticates requests from the JWT cookie.
     * This is wired by hand instead of using http.oauth2ResourceServer() on purpose: that configurer exempts every
     * request carrying a bearer token from CSRF checks, which is right for an Authorization header (not sent
     * automatically by browsers) but would switch CSRF protection off for our cookie, which IS sent automatically.
     */
    private static BearerTokenAuthenticationFilter jwtCookieFilter(JwtDecoder jwtDecoder) {
        AuthenticationEntryPoint unauthorized = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
        BearerTokenAuthenticationFilter filter =
                new BearerTokenAuthenticationFilter(new ProviderManager(new JwtAuthenticationProvider(jwtDecoder)));
        filter.setBearerTokenResolver(cookieTokenResolver());
        filter.setAuthenticationEntryPoint(unauthorized);
        filter.setAuthenticationFailureHandler((request, response, ex) -> unauthorized.commence(request, response, ex));
        filter.setSecurityContextRepository(new RequestAttributeSecurityContextRepository());
        return filter;
    }

    /** Reads the JWT from the access_token cookie. The Authorization header is intentionally not accepted. */
    private static BearerTokenResolver cookieTokenResolver() {
        return request -> {
            String path = request.getRequestURI();
            // Only the API looks at credentials: public routes (the redirect!) must never fail because of a cookie.
            if (!path.startsWith("/api/") || TOKENLESS_PATHS.contains(path)) {
                return null;
            }
            Cookie cookie = WebUtils.getCookie(request, ACCESS_COOKIE);
            return (cookie == null || cookie.getValue().isBlank()) ? null : cookie.getValue();
        };
    }
}
