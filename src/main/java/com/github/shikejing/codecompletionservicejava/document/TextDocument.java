package com.github.shikejing.codecompletionservicejava.document;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import com.github.shikejing.codecompletionservicejava.model.DocumentSnapshot;

/**
 * Mutable state of one editor document. Package-private; all access is
 * serialized by {@link DocumentManager} via a per-document lock, so none of
 * these methods synchronize on their own. Published state always takes the
 * form of an immutable {@link DocumentSnapshot}.
 */
final class TextDocument {

    private final String uri;
    private String text = "";
    private long version = -1;
    private boolean open;
    private final LinkedHashSet<String> appliedEditIds = new LinkedHashSet<>();
    private long lastEditNanos;

    TextDocument(String uri) {
        this.uri = uri;
    }

    DocumentSnapshot snapshot() {
        return new DocumentSnapshot(uri, text, version, open, lastEditNanos);
    }

    String getText() { return text; }
    long getVersion() { return version; }
    boolean isOpen() { return open; }
    long getLastEditNanos() { return lastEditNanos; }

    Set<String> appliedEditIdsView() {
        return Collections.unmodifiableSet(appliedEditIds);
    }

    boolean containsEditId(String editId) {
        return editId != null && appliedEditIds.contains(editId);
    }

    /** Initial open (or full-replace open) state. */
    void openWith(String newText, long newVersion, String editId, long editNanos) {
        this.text = newText == null ? "" : newText;
        this.version = newVersion;
        this.open = true;
        this.lastEditNanos = editNanos;
        addEditId(editId);
    }

    /** Commit a new text/version after a validated incremental edit. */
    void commit(String newText, long newVersion, String editId, long editNanos) {
        this.text = newText;
        this.version = newVersion;
        this.lastEditNanos = editNanos;
        addEditId(editId);
    }

    void markClosed(long newVersion) {
        this.open = false;
        this.version = newVersion;
        this.appliedEditIds.clear();
    }

    /** Restore durable state during recovery; replays the recorded edit ids. */
    void restore(String newText, long newVersion, Set<String> editIds, long editNanos) {
        this.text = newText;
        this.version = newVersion;
        this.open = true;
        this.lastEditNanos = editNanos;
        this.appliedEditIds.clear();
        if (editIds != null) {
            this.appliedEditIds.addAll(editIds);
        }
    }

    private void addEditId(String editId) {
        if (editId == null || editId.isEmpty()) {
            return;
        }
        appliedEditIds.add(editId);
        // bounded history: dedup metadata must not grow without limit
        while (appliedEditIds.size() > 1024) {
            var it = appliedEditIds.iterator();
            it.next();
            it.remove();
        }
    }
}
