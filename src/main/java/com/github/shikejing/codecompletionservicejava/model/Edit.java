package com.github.shikejing.codecompletionservicejava.model;

/**
 * A single document synchronization event: open / incremental change / close.
 *
 * <p>Incremental changes are expressed as range replacement. {@code range} is
 * {@code null} for open/close and denotes a full-document replacement when it
 * is {@code null} on a change event together with {@code fullText != null}.</p>
 */
public record Edit(
        long version,
        String editId,
        Kind kind,
        TextRange range,
        String text,
        String fullText
) {
    public enum Kind { OPEN, CHANGE, CLOSE }

    public static Edit open(long version, String text) {
        return new Edit(version, "open-v" + version, Kind.OPEN, null, null, text);
    }

    public static Edit change(long version, String editId, TextRange range, String text) {
        // record components: (version, editId, kind, range, text, fullText)
        return new Edit(version, editId, Kind.CHANGE, range, text, null);
    }

    public static Edit close(long version) {
        return new Edit(version, "close-v" + version, Kind.CLOSE, null, null, null);
    }
}
