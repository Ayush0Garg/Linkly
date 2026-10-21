package com.ayush.shortener.link;

/** What the redirect hot path needs: where to send the visitor and which link to attribute the click to. */
public record ResolvedLink(long linkId, String longUrl) {
}
