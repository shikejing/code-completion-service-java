package com.github.shikejing.codecompletionservicejava.document;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import com.github.shikejing.codecompletionservicejava.config.ServiceProperties;
import com.github.shikejing.codecompletionservicejava.model.DocumentSnapshot;
import com.github.shikejing.codecompletionservicejava.model.Edit;
import com.github.shikejing.codecompletionservicejava.model.EditOutcome;
import com.github.shikejing.codecompletionservicejava.model.Position;
import com.github.shikejing.codecompletionservicejava.model.TextRange;
import com.github.shikejing.codecompletionservicejava.model.VersionPolicy;

/**
 * Owns every open document and its synchronization state.
 *
 * <h3>Synchronization guarantees</h3>
 * <ul>
 *   <li>Each document has a {@link ReentrantReadWriteLock}; an edit takes the
 *       write lock for the whole validate+apply+publish sequence, so a query
 *       can only ever observe a fully committed version, never a half-applied
 *       edit or field-level mix.</li>
 *   <li>Edits are expressed as range replacement and applied independently to
 *       the last committed text; overlapping edits at the same version number
 *       are processed in the order they arrive, and the second one is rejected
 *       as a stale duplicate attempt unless its editId matches (idempotent).</li>
 *   <li>An edit whose version is below the committed version is stale and is
 *       rejected according to {@link VersionPolicy}; the exact same edit
 *       (same version + editId) delivered again is a no-op
 *       ({@link EditOutcome.Status#DUPLICATE}).</li>
 *   <li>Version gaps (skipped numbers) are rejected under {@code STRICT},
 *       tolerated under {@code LENIENT_GAP}/{@code ACCEPT_ALL}.</li>
 * </ul>
 */
@Component
public class DocumentManager {

    private static final class Entry {
        final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        final TextDocument document;

        Entry(TextDocument document) {
            this.document = document;
        }
    }

    private final ServiceProperties properties;
    private final ConcurrentHashMap<String, Entry> documents = new ConcurrentHashMap<>();
    private final List<Consumer<CommitEvent>> commitListeners = new ArrayList<>();
    private final Object listenersLock = new Object();

    public DocumentManager(ServiceProperties properties) {
        this.properties = properties;
    }

    /**
     * Registers a callback invoked after every newly committed event
     * (OPEN/CHANGE/CLOSE) while the per-document write lock is held.
     */
    public void addCommitListener(Consumer<CommitEvent> listener) {
        synchronized (listenersLock) {
            commitListeners.add(listener);
        }
    }

    /**
     * Applies one synchronization event. Never partially mutates state:
     * either the edit is fully committed or nothing changes.
     */
    public EditOutcome applyEdit(String uri, Edit edit) {
        if (uri == null || edit == null || edit.kind() == null) {
            return EditOutcome.rejected(EditOutcome.Status.REJECTED_INVALID_RANGE,
                    edit == null ? -1 : edit.version(), "null uri/edit/kind");
        }
        Entry entry = documents.computeIfAbsent(uri,
                u -> new Entry(new TextDocument(u)));
        entry.lock.writeLock().lock();
        try {
            TextDocument doc = entry.document;
            long current = doc.getVersion();

            // ---------- idempotency: exact same edit already applied ----------
            if (doc.containsEditId(edit.editId()) && edit.version() <= current) {
                return EditOutcome.duplicate(current);
            }

            // ---------- version ordering ----------
            if (current >= 0 && edit.version() < current) {
                // stale (or out-of-order) version
                if (properties.getVersionPolicy() == VersionPolicy.ACCEPT_ALL
                        && edit.version() == current) {
                    return EditOutcome.duplicate(current);
                }
                return EditOutcome.rejected(EditOutcome.Status.REJECTED_STALE, current,
                        "edit version " + edit.version() + " is older than committed version " + current);
            }
            if (current >= 0 && edit.version() == current) {
                return EditOutcome.rejected(EditOutcome.Status.REJECTED_STALE, current,
                        "version " + current + " already committed (different editId)");
            }
            if (current >= 0 && edit.version() > current + 1
                    && properties.getVersionPolicy() == VersionPolicy.STRICT) {
                return EditOutcome.rejected(EditOutcome.Status.REJECTED_VERSION_GAP, current,
                        "version gap detected: " + current + " -> " + edit.version());
            }

            // ---------- kind-specific application ----------
            long now = System.nanoTime();
            switch (edit.kind()) {
                case OPEN -> {
                    boolean isNew = current < 0 || !doc.isOpen();
                    if (isNew && countOpenDocuments() >= properties.getLimits().getMaxDocuments()) {
                        evictClosedIfNeeded();
                        if (countOpenDocuments() >= properties.getLimits().getMaxDocuments()) {
                            return EditOutcome.rejected(EditOutcome.Status.REJECTED_LIMIT, current,
                                    "max tracked documents reached ("
                                            + properties.getLimits().getMaxDocuments() + ")");
                        }
                    }
                    String full = edit.fullText() == null ? "" : edit.fullText();
                    if (full.length() > properties.getLimits().getMaxDocumentChars()) {
                        return EditOutcome.rejected(EditOutcome.Status.REJECTED_INVALID_RANGE, current,
                                "document exceeds max size");
                    }
                    doc.openWith(full, edit.version(), edit.editId(), now);
                    notifyListeners(uri, doc.snapshot(), edit);
                    return EditOutcome.applied(edit.version());
                }
                case CLOSE -> {
                    if (!doc.isOpen()) {
                        return EditOutcome.rejected(EditOutcome.Status.REJECTED_CLOSED, current,
                                "document already closed");
                    }
                    doc.markClosed(edit.version());
                    DocumentSnapshot closedSnapshot = doc.snapshot();
                    notifyListeners(uri, closedSnapshot, edit);
                    return EditOutcome.applied(edit.version());
                }
                case CHANGE -> {
                    if (!doc.isOpen()) {
                        return EditOutcome.rejected(EditOutcome.Status.REJECTED_CLOSED, current,
                                "document is not open");
                    }
                    String result = applyChange(doc.getText(), edit, properties);
                    if (result == null) {
                        return EditOutcome.rejected(EditOutcome.Status.REJECTED_INVALID_RANGE, current,
                                "edit range outside document bounds");
                    }
                    if (result.length() > properties.getLimits().getMaxDocumentChars()) {
                        return EditOutcome.rejected(EditOutcome.Status.REJECTED_INVALID_RANGE, current,
                                "document would exceed max size");
                    }
                    doc.commit(result, edit.version(), edit.editId(), now);
                    notifyListeners(uri, doc.snapshot(), edit);
                    return EditOutcome.applied(edit.version());
                }
                default -> {
                    return EditOutcome.rejected(EditOutcome.Status.REJECTED_INVALID_RANGE, current,
                            "unknown edit kind");
                }
            }
        } finally {
            entry.lock.writeLock().unlock();
        }
    }

    private void notifyListeners(String uri, DocumentSnapshot snapshot, Edit edit) {
        List<Consumer<CommitEvent>> listeners;
        synchronized (listenersLock) {
            listeners = new ArrayList<>(commitListeners);
        }
        CommitEvent event = new CommitEvent(uri, snapshot, edit);
        for (Consumer<CommitEvent> listener : listeners) {
            listener.accept(event);
        }
    }

    /**
     * Pure function: applies a range replacement to text. Returns
     * {@code null} when the range is invalid. A null range means a full
     * document replacement using {@code fullText}.
     */
    static String applyChange(String current, Edit edit, ServiceProperties props) {
        if (edit.range() == null) {
            return edit.fullText() == null ? "" : edit.fullText();
        }
        TextRange range = edit.range();
        LineIndex index = new LineIndex(current);
        int start = index.strictOffset(range.start());
        int end = index.strictOffset(range.end());
        if (start < 0 || end < start || end > current.length()) {
            return null;
        }
        String replacement = edit.text() == null ? "" : edit.text();
        return current.substring(0, start) + replacement + current.substring(end);
    }

    /** Returns the latest committed snapshot, or {@code null} if unknown/closed. */
    public DocumentSnapshot getSnapshot(String uri) {
        Entry entry = documents.get(uri);
        if (entry == null) {
            return null;
        }
        entry.lock.readLock().lock();
        try {
            return entry.document.isOpen() ? entry.document.snapshot() : null;
        } finally {
            entry.lock.readLock().unlock();
        }
    }

    /** Runs an action under the document's read lock against its current text. */
    public <T> T withReadLock(String uri, java.util.function.Function<DocumentSnapshot, T> action) {
        Entry entry = documents.get(uri);
        if (entry == null) {
            return null;
        }
        entry.lock.readLock().lock();
        try {
            if (!entry.document.isOpen()) {
                return null;
            }
            return action.apply(entry.document.snapshot());
        } finally {
            entry.lock.readLock().unlock();
        }
    }

    /** Test/recovery hook: installs a document state without listeners. */
    public void forceState(String uri, String text, long version, boolean open) {
        Entry entry = documents.computeIfAbsent(uri, u -> new Entry(new TextDocument(u)));
        entry.lock.writeLock().lock();
        try {
            if (open) {
                entry.document.restore(text, version, java.util.Set.of(), System.nanoTime());
            } else {
                entry.document.markClosed(version);
            }
        } finally {
            entry.lock.writeLock().unlock();
        }
    }

    /** Applies a recovered snapshot at startup. */
    public void restoreState(String uri, String text, long version) {
        Entry entry = documents.computeIfAbsent(uri, u -> new Entry(new TextDocument(u)));
        entry.lock.writeLock().lock();
        try {
            entry.document.restore(text, version, java.util.Set.of(), System.nanoTime());
        } finally {
            entry.lock.writeLock().unlock();
        }
    }

    public int trackedDocumentCount() {
        return documents.size();
    }

    private int countOpenDocuments() {
        int count = 0;
        for (Entry entry : documents.values()) {
            entry.lock.readLock().lock();
            try {
                if (entry.document.isOpen()) {
                    count++;
                }
            } finally {
                entry.lock.readLock().unlock();
            }
        }
        return count;
    }

    /** Enforces the bound on tracked documents (closed docs are evicted). */
    public void evictClosedIfNeeded() {
        Iterator<Map.Entry<String, Entry>> it = documents.entrySet().iterator();
        while (it.hasNext()) {
            Entry entry = it.next().getValue();
            entry.lock.readLock().lock();
            try {
                if (!entry.document.isOpen()) {
                    it.remove();
                }
            } finally {
                entry.lock.readLock().unlock();
            }
        }
    }
}
