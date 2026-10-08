package com.github.shikejing.codecompletionservicejava.model;

import java.util.List;

/**
 * One editor change notification.
 *
 * @param version     document version produced by this notification
 * @param baseVersion version the editor computed the edits against
 * @param kind        incremental (range edits) or full (whole text)
 * @param edits       range-replacement edits for the incremental kind
 * @param fullText    new whole text for the full kind
 */
public record DocumentChangeRequest(String uri,
                                    int version,
                                    int baseVersion,
                                    SyncKind kind,
                                    List<TextEdit> edits,
                                    String fullText) {

    public static DocumentChangeRequest incremental(String uri, int version, int baseVersion,
                                                    List<TextEdit> edits) {
        return new DocumentChangeRequest(uri, version, baseVersion, SyncKind.INCREMENTAL,
                List.copyOf(edits), null);
    }

    public static DocumentChangeRequest full(String uri, int version, String text) {
        return new DocumentChangeRequest(uri, version, version - 1, SyncKind.FULL, List.of(), text);
    }
}
