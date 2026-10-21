package com.ayush.shortener.ratelimit;

import java.util.List;

/** The names of the policies. Their numbers live in configuration (app.rate-limit.policies.*). */
public final class Policies {

    /** Sign-in, register and Google sign-in, together, per client address. */
    public static final String AUTH_IP = "auth-ip";
    /** Sign-in attempts per account, whichever address they come from: stops credential stuffing. */
    public static final String AUTH_EMAIL = "auth-email";
    public static final String LINKS_CREATE = "links-create";
    public static final String QR = "qr";
    /** Following short links, per client address; counted per instance only. */
    public static final String REDIRECT = "redirect";

    public static final List<String> ALL = List.of(AUTH_IP, AUTH_EMAIL, LINKS_CREATE, QR, REDIRECT);

    private Policies() {
    }
}
