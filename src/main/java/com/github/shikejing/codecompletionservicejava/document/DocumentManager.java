package com.github.shikejing.codecompletionservicejava.document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import com.github.shikejing.codecompletionservicejava.config.CompletionProperties;
import com.github.shikejing.codecompletionservicejava.document.EditApplier.AbsoluteRange;
import com.github.shikejing.codecompletionservicejava.model.ChangeOutcome;
import com.github.shikejing.codecompletionservicejava.model.ChangeStatus;
import com.github.shikejing.codecompletionservicejava.model.DocumentChangeRequest;
import com.github.shikejing.codecompletionservicejava.model.DocumentCloseRequest;
import com.github.shikejing.codecompletionservicejava.model.DocumentOpenRequest;
import com.github.shikejing.codecompletionservicejava.model.OutOfOrderPolicy;
import com.github.shikejing.codecompletionservicejava.model.StalePolicy;
import com.github.shikejing.codecompletionservicejava.model.SyncKind;
import com.github.shikejing.codecompletionservicejava.model.TextEdit;
import com.github.shikejing.codecompletionservicejava.parse.LineIndex;

/**
 * Owns open documents and their committed versions.
 *
 * Per-document locking serializes all mutations; readers always receive an
 * immutable {@link DocumentSnapshot} swapped in only after the whole change
 * commits, so half states are impossible.
 *
 * Version/ordering rules:
 * <ul>
 *   <li>{@code baseVersion == current}: applied, producing request.version.</li>
 *   <li>identical notification already committed: {@code DUPLICATE}, idempotent;
 *       same version with different content: rejected, never silently kept.</li>
 *   <li>{@code baseVersion < current} (stale): REJECT (default) leaves content
 *       untouched; REBASE translates each range edit through every intervening
 *       commit using recorded absolute spans and applies the translated edit.
 *       An edit intersecting removed text, or any intervening whole-text sync,
 *       makes rebase impossible and is rejected.</li>
 *   <li>{@code baseVersion > current} (future): BUFFER parks it and commits
 *       contiguous chains in version order when the gap closes; REJECT refuses.
 *       The buffer is bounded.</li>
 *   <li>overlapping edits within one notification fail atomically.</li>
 * </ul>
 */
public class DocumentManager {

    private static final class Entry {
        DocumentSnapshot snapshot;
        final Map<Integer, DocumentChangeRequest> rawByVersion = new LinkedHashMap<>();
        final Map<Integer, CommittedChange> commits = new LinkedHashMap<>();
        final Map<Integer, DocumentChangeRequest> pending = new LinkedHashMap<>();
        // LineIndex of each commit's base text, used to translate stale edits.
        final Map<Integer, LineIndex> baseIndexes = new LinkedHashMap<>();
        final ReentrantLock lock = new ReentrantLock();

        Entry(DocumentSnapshot snapshot) {
            this.snapshot = snapshot;
        }
    }

    private final CompletionProperties properties;
    private final ConcurrentMap<String, Entry> documents = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, String> lruOrder = new LinkedHashMap<>(64, 0.75f, true);
    private final Object lruLock = new Object();

    public DocumentManager(CompletionProperties properties) {
        this.properties = properties;
    }

    public void open(DocumentOpenRequest request) {
        Entry entry = documents.computeIfAbsent(request.uri(),
                u -> new Entry(snapshotOf(u, request.version(), request.text())));
        entry.lock.lock();
        try {
            entry.snapshot = snapshotOf(request.uri(), request.version(), request.text());
            entry.rawByVersion.clear();
            entry.commits.clear();
            entry.pending.clear();
            entry.baseIndexes.clear();
            touch(request.uri());
        } finally {
            entry.lock.unlock();
        }
    }

    private static DocumentSnapshot snapshotOf(String uri, int version, String text) {
        return DocumentSnapshot.initial(uri, version, text == null ? "" : text);
    }

    public ChangeOutcome change(DocumentChangeRequest request) {
        Entry entry = documents.get(request.uri());
        if (entry == null) {
            return ChangeOutcome.noop(request.uri(), ChangeStatus.CLOSED,
                    0, request.baseVersion());
        }
        entry.lock.lock();
        try {
            touch(request.uri());
            ChangeOutcome outcome = tryApplyOrPark(entry, request);
            if (outcome == null) {
                return ChangeOutcome.noop(request.uri(), ChangeStatus.BUFFERED,
                        entry.snapshot.version(), request.baseVersion());
            }
            if (outcome.status() == ChangeStatus.APPLIED
                    || outcome.status() == ChangeStatus.REBASED) {
                int from = outcome.version();
                drainPending(entry);
                return new ChangeOutcome(request.uri(), outcome.status(),
                        entry.snapshot.version(), request.baseVersion(),
                        committedVersions(entry, from),
                        outcome.replacedStart(), outcome.replacedOldEnd(),
                        outcome.replacedNewEnd());
            }
            return outcome;
        } finally {
            entry.lock.unlock();
        }
    }

    private List<Integer> committedVersions(Entry entry, int firstVersion) {
        List<Integer> versions = new ArrayList<>();
        for (Integer v : entry.commits.keySet()) {
            if (v >= firstVersion) {
                versions.add(v);
            }
        }
        Collections.sort(versions);
        return List.copyOf(versions);
    }

    private ChangeOutcome tryApplyOrPark(Entry entry, DocumentChangeRequest request) {
        int current = entry.snapshot.version();
        DocumentChangeRequest prior = entry.rawByVersion.get(request.version());
        if (prior != null) {
            return ChangeOutcome.noop(request.uri(),
                    sameNotification(prior, request)
                            ? ChangeStatus.DUPLICATE : ChangeStatus.REJECTED_STALE,
                    current, request.baseVersion());
        }
        if (request.baseVersion() == current) {
            return commit(entry, request, false, null);
        }
        if (request.baseVersion() < current) {
            return handleStale(entry, request, current);
        }
        return handleFuture(entry, request, current);
    }

    private ChangeOutcome commit(Entry entry, DocumentChangeRequest request,
                                 boolean rebased, List<AbsoluteRange> forcedSpans) {
        DocumentSnapshot base = entry.snapshot;
        try {
            List<AbsoluteRange> spans;
            String newText;
            if (request.kind() == SyncKind.FULL) {
                newText = request.fullText() == null ? "" : request.fullText();
                spans = List.of(new AbsoluteRange(0, base.text().length(), newText));
            } else if (rebased) {
                spans = List.copyOf(forcedSpans);
                newText = EditApplier.applyAbsolute(base.text(), spans);
            } else {
                List<TextEdit> edits = request.edits() == null
                        ? List.of() : request.edits();
                if (hasOverlap(edits)) {
                    return ChangeOutcome.noop(request.uri(),
                            ChangeStatus.REJECTED_OVERLAP, base.version(),
                            request.baseVersion());
                }
                spans = toAbsolute(edits, base.lineIndex());
                newText = EditApplier.apply(base.text(), edits, base.lineIndex());
            }
            DocumentSnapshot next = new DocumentSnapshot(request.uri(),
                    request.version(), newText, new LineIndex(newText));
            entry.snapshot = next;
            entry.rawByVersion.put(request.version(), request);
            entry.baseIndexes.put(request.version(), base.lineIndex());
            entry.commits.put(request.version(), new CommittedChange(
                    request.version(), request.baseVersion(),
                    request.kind() == SyncKind.FULL, spans));
            int[] u = unionSpan(spans);
            return new ChangeOutcome(request.uri(),
                    rebased ? ChangeStatus.REBASED : ChangeStatus.APPLIED,
                    next.version(), request.baseVersion(), List.of(request.version()),
                    u[0], u[1], u[2]);
        } catch (EditApplier.InvalidEditException e) {
            return ChangeOutcome.noop(request.uri(), ChangeStatus.REJECTED_STALE,
                    entry.snapshot.version(), request.baseVersion());
        }
    }

    private List<AbsoluteRange> toAbsolute(List<TextEdit> edits, LineIndex lineIndex) {
        List<AbsoluteRange> out = new ArrayList<>(edits.size());
        for (TextEdit edit : edits) {
            out.add(new AbsoluteRange(lineIndex.offset(edit.range().start()),
                    lineIndex.offset(edit.range().end()), edit.newText()));
        }
        return List.copyOf(out);
    }

    private boolean hasOverlap(List<TextEdit> edits) {
        if (edits.size() < 2) {
            return false;
        }
        List<TextEdit> sorted = TextEdit.sorted(edits);
        for (int i = 1; i < sorted.size(); i++) {
            if (TextEdit.overlaps(sorted.get(i - 1), sorted.get(i))) {
                return true;
            }
        }
        return false;
    }

    private ChangeOutcome handleStale(Entry entry, DocumentChangeRequest request,
                                      int current) {
        if (properties.getStalePolicy() == StalePolicy.REJECT
                || request.kind() == SyncKind.FULL
                || request.edits() == null) {
            return rejectedStale(request, current);
        }
        List<CommittedChange> between = commitsBetween(entry,
                request.baseVersion(), current);
        if (between.isEmpty()) {
            return rejectedStale(request, current);
        }
        for (CommittedChange c : between) {
            if (c.fullSync()) {
                return rejectedStale(request, current);
            }
        }
        // The stale edits are anchored to the text at request.baseVersion.
        // That text is the snapshot *before* the first intervening commit,
        // i.e. commit(baseVersion). We retain no old snapshots, so exact
        // translation starts from the first commit's spans, which are absolute
        // offsets in exactly that base text; positions in the request are
        // converted via the first commit's stored base index when present.
        CommittedChange first = between.get(0);
        if (first.baseVersion() != request.baseVersion()) {
            return rejectedStale(request, current);
        }
        // We need absolute offsets of the stale edit in the base text. The
        // base LineIndex is kept on the first commit for this purpose.
        LineIndex baseIndex = entry.baseIndexes.get(first.version());
        if (baseIndex == null) {
            return rejectedStale(request, current);
        }
        List<AbsoluteRange> translated = new ArrayList<>();
        for (TextEdit edit : request.edits()) {
            int s = baseIndex.offset(edit.range().start());
            int e = baseIndex.offset(edit.range().end());
            for (CommittedChange done : between) {
                for (AbsoluteRange span : done.spans()) {
                    if ((s > span.start() && s < span.end())
                            || (e > span.start() && e < span.end())) {
                        return rejectedStale(request, current);
                    }
                    s = EditApplier.translateOffset(s, span.start(), span.end(),
                            span.start() + span.newText().length());
                    e = EditApplier.translateOffset(e, span.start(), span.end(),
                            span.start() + span.newText().length());
                }
            }
            if (e < s) {
                return rejectedStale(request, current);
            }
            translated.add(new AbsoluteRange(s, e, edit.newText()));
        }
        // Guard against overlapping translated edits (e.g. converging offsets).
        for (int i = 1; i < translated.size(); i++) {
            AbsoluteRange a = translated.get(i - 1);
            AbsoluteRange b = translated.get(i);
            if (b.start() < a.end()) {
                return rejectedStale(request, current);
            }
        }
        // Record the rebased notification under its version to keep the chain.
        ChangeOutcome outcome = commit(entry, request, true, translated);
        return outcome;
    }

    private List<CommittedChange> commitsBetween(Entry entry, int from, int to) {
        List<CommittedChange> list = new ArrayList<>();
        for (CommittedChange c : entry.commits.values()) {
            if (c.version() > from && c.version() <= to) {
                list.add(c);
            }
        }
        list.sort((a, b) -> Integer.compare(a.version(), b.version()));
        return list;
    }

    private ChangeOutcome rejectedStale(DocumentChangeRequest request, int current) {
        return ChangeOutcome.noop(request.uri(), ChangeStatus.REJECTED_STALE,
                current, request.baseVersion());
    }

    /**
     * Union span of a commit's edits. Returns
     * [start, oldEnd, newEnd] in absolute coordinates: the post-commit end is
     * derived from the accumulated replacement delta.
     */
    private int[] unionSpan(List<AbsoluteRange> spans) {
        if (spans == null || spans.isEmpty()) {
            return new int[]{-1, -1, -1};
        }
        int start = Integer.MAX_VALUE;
        int oldEnd = Integer.MIN_VALUE;
        int newEnd = 0;
        int shift = 0; // cumulative delta before the rightmost edited point
        for (AbsoluteRange span : spans) {
            start = Math.min(start, span.start());
            // spans are applied reverse order; compute new end from the
            // rightmost edit: oldEnd + totalDelta
            oldEnd = Math.max(oldEnd, span.end());
            newEnd = span.start() + span.newText().length();
            shift += span.newText().length() - (span.end() - span.start());
        }
        return new int[]{start, oldEnd, oldEnd + shift};
    }

    private ChangeOutcome handleFuture(Entry entry, DocumentChangeRequest request,
                                       int current) {
        if (properties.getOutOfOrderPolicy() == OutOfOrderPolicy.REJECT) {
            return rejectedStale(request, current);
        }
        if (entry.pending.size() >= properties.getMaxBufferedChanges()) {
            return ChangeOutcome.noop(request.uri(), ChangeStatus.REJECTED_FUTURE_LIMIT,
                    current, request.baseVersion());
        }
        entry.pending.put(request.version(), request);
        return null;
    }

    private void drainPending(Entry entry) {
        boolean progress = true;
        while (progress) {
            progress = false;
            Integer nextVersion = null;
            for (Map.Entry<Integer, DocumentChangeRequest> e : entry.pending.entrySet()) {
                if (e.getValue().baseVersion() == entry.snapshot.version()
                        && (nextVersion == null || e.getKey() < nextVersion)) {
                    nextVersion = e.getKey();
                }
            }
            if (nextVersion == null) {
                continue;
            }
            DocumentChangeRequest parked = entry.pending.remove(nextVersion);
            commit(entry, parked, false, null);
            progress = true;
        }
    }

    private boolean sameNotification(DocumentChangeRequest a, DocumentChangeRequest b) {
        if (a.version() != b.version() || a.baseVersion() != b.baseVersion()
                || a.kind() != b.kind()) {
            return false;
        }
        return a.kind() == SyncKind.FULL
                ? Objects.equals(a.fullText(), b.fullText())
                : Objects.equals(a.edits(), b.edits());
    }

    public boolean close(DocumentCloseRequest request) {
        Entry removed = documents.remove(request.uri());
        synchronized (lruLock) {
            lruOrder.remove(request.uri());
        }
        return removed != null;
    }

    public Optional<DocumentSnapshot> snapshot(String uri) {
        Entry entry = documents.get(uri);
        if (entry == null) {
            return Optional.empty();
        }
        entry.lock.lock();
        try {
            return Optional.of(entry.snapshot);
        } finally {
            entry.lock.unlock();
        }
    }

    public int openDocumentCount() {
        return documents.size();
    }

    /** Replace committed state on restart recovery. */
    public void restore(DocumentSnapshot snapshot) {
        Entry entry = new Entry(snapshot);
        documents.put(snapshot.uri(), entry);
        touch(snapshot.uri());
    }

    private void touch(String uri) {
        synchronized (lruLock) {
            lruOrder.put(uri, uri);
            while (documents.size() > properties.getMaxOpenDocuments()) {
                Iterator<String> it = lruOrder.keySet().iterator();
                if (!it.hasNext()) {
                    break;
                }
                String oldest = it.next();
                it.remove();
                documents.remove(oldest);
            }
        }
    }
}
