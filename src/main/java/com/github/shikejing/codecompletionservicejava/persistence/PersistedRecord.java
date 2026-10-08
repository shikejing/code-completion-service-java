package com.github.shikejing.codecompletionservicejava.persistence;

import java.util.List;

import com.github.shikejing.codecompletionservicejava.model.Edit;

/**
 * One durable journal record: either a committed open/full snapshot or a
 * batch of committed incremental edits.
 */
public record PersistedRecord(String uri, long version, String fullText, List<Edit> edits) {

    public static PersistedRecord snapshot(String uri, long version, String fullText) {
        return new PersistedRecord(uri, version, fullText, List.of());
    }

    public static PersistedRecord delta(String uri, long version, List<Edit> edits) {
        return new PersistedRecord(uri, version, null, edits);
    }

    public boolean isSnapshot() {
        return fullText != null;
    }
}
