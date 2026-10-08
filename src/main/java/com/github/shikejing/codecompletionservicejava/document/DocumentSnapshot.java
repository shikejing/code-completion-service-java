package com.github.shikejing.codecompletionservicejava.document;

import com.github.shikejing.codecompletionservicejava.parse.LineIndex;

/**
 * Immutable, committed document version. All readers see whole snapshots;
 * field-level mixing across versions is impossible.
 */
public record DocumentSnapshot(String uri, int version, String text, LineIndex lineIndex) {

    public static DocumentSnapshot initial(String uri, int version, String text) {
        return new DocumentSnapshot(uri, version, text, new LineIndex(text));
    }
}
