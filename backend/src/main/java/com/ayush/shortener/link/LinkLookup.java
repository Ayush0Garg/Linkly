package com.ayush.shortener.link;

import java.time.Instant;

/** What the database knows about a code, without throwing: the resolver caches each of the three outcomes. */
public sealed interface LinkLookup {

    /** A live link. {@code expiresAt} is null for links that never expire. */
    record Found(ResolvedLink link, Instant expiresAt) implements LinkLookup {
    }

    record NotFound() implements LinkLookup {
    }

    /** Existed once, but is deactivated or expired. */
    record Gone() implements LinkLookup {
    }
}
