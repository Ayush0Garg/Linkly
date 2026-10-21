package com.ayush.shortener.link;

import java.time.Instant;

/** @param alias optional custom code; null/blank means "generate one" */
public record CreateLinkCommand(String longUrl, String alias, Instant expiresAt) {
}
