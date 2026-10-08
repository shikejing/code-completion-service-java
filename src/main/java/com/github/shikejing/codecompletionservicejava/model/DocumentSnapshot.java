package com.github.shikejing.codecompletionservicejava.model;

/**
 * Immutable point-in-time state of an open document.
 */
public record DocumentSnapshot(
        String uri,
        String text,
        long version,
        boolean open,
        long lastEditNanos
) {
}
