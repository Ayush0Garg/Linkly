package com.ayush.shortener.web;

import java.util.List;
import java.util.Set;

/**
 * The pages of the React app (frontend/src/App.tsx). Three places must agree on them: the controller that serves the
 * shell, the security rules that make them public, and {@code ReservedCodes}, because a short code is a single path
 * segment at the root and would otherwise shadow (or be shadowed by) a page. SpaRoutesTest compares this list with
 * App.tsx, so a new route cannot be added on one side only.
 */
public final class SpaRoutes {

    /** Pages served as the app shell. A page behind sign-in still gets the shell; the app itself redirects to /login. */
    public static final List<String> PAGES = List.of("/", "/login", "/register", "/links", "/links/{id}");

    /** The shell itself, and the files it loads. Hashed bundles live under /assets, so they can be cached forever. */
    public static final String INDEX = "/index.html";
    public static final String ASSETS = "/assets/**";
    public static final String FAVICON = "/favicon.svg";

    /** First path segments that belong to the app: no link may use one as its code. */
    public static final Set<String> TOP_LEVEL_SEGMENTS = Set.of("login", "register", "links");

    /** Everything the browser fetches to show the app (shell + static files), for permissions and headers. */
    public static String[] publicPaths() {
        return java.util.stream.Stream.of(PAGES.stream(), java.util.stream.Stream.of(INDEX, ASSETS, FAVICON))
                .flatMap(s -> s).toArray(String[]::new);
    }

    private SpaRoutes() {
    }
}
