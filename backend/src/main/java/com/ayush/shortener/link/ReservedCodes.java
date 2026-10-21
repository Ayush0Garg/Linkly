package com.ayush.shortener.link;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.ayush.shortener.web.SpaRoutes;

/** Rules for what may appear in a short code, shared by custom aliases and generated codes. */
final class ReservedCodes {

    /** Same shape the redirect route accepts. */
    static final Pattern VALID_CODE = Pattern.compile("[A-Za-z0-9_-]{3,32}");

    /** Paths the app itself serves (or may serve) at the root; a link must never shadow them. */
    private static final Set<String> RESERVED = reserved();

    private static Set<String> reserved() {
        Set<String> all = new HashSet<>(Set.of(
                "api", "admin", "login", "logout", "signin", "signup", "oauth2", "auth", "error",
                "actuator", "static", "assets", "health", "dashboard", "app", "www", "about",
                "terms", "privacy", "help", "support", "settings", "account", "docs", "swagger"));
        all.addAll(SpaRoutes.TOP_LEVEL_SEGMENTS); // the pages of the web app: /login, /register, /links
        return Set.copyOf(all);
    }

    private ReservedCodes() {
    }

    static boolean isReserved(String code) {
        return RESERVED.contains(code.toLowerCase(Locale.ROOT));
    }
}
