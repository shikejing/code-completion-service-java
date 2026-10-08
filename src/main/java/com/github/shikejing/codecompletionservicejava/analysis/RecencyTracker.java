package com.github.shikejing.codecompletionservicejava.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Tracks the symbol names touched by the most recent edits per document,
 * bounded by a fixed window of edits. Used only for the recency ranking
 * boost; the state is deterministic given the same edit sequence.
 *
 * Within one edit the supplied names are de-duplicated preserving first-seen
 * order; across edits newer edits sort ahead. The structure never grows
 * beyond {@code windowEdits} edits (and their names).
 */
public final class RecencyTracker {

    private final int windowEdits;
    private final Deque<List<String>> batches = new ArrayDeque<>();

    public RecencyTracker(int windowEdits) {
        this.windowEdits = Math.max(1, windowEdits);
    }

    /** Record the symbol names touched by one committed edit. */
    public synchronized void recordEdit(List<String> touchedNames) {
        List<String> batch = new ArrayList<>();
        if (touchedNames != null) {
            for (String name : touchedNames) {
                if (name != null && !name.isEmpty() && !batch.contains(name)) {
                    batch.add(name);
                }
            }
        }
        batches.addFirst(List.copyOf(batch));
        while (batches.size() > windowEdits) {
            batches.removeLast();
        }
    }

    /** Bounded rank of a name among recent edits: 0 = most recent, -1 absent. */
    public synchronized int rankOf(String name) {
        int rank = 0;
        for (List<String> batch : batches) {
            if (batch.contains(name)) {
                return rank;
            }
            rank++;
        }
        return -1;
    }

    /** Names in recency order (most recent edit first, deduped). */
    public synchronized List<String> recentNames() {
        List<String> out = new ArrayList<>();
        for (List<String> batch : batches) {
            for (String name : batch) {
                if (!out.contains(name)) {
                    out.add(name);
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * Persistence view: each batch is prefixed with its length so restart can
     * restore the exact per-edit grouping (newest batch first).
     */
    public synchronized List<String> encodeBatches() {
        List<String> out = new ArrayList<>();
        for (List<String> batch : batches) {
            out.add("BATCH " + batch.size());
            out.addAll(batch);
        }
        return List.copyOf(out);
    }

    /** Restore from {@link #encodeBatches()} lines (newest batch first). */
    public synchronized void restoreBatches(List<String> encoded) {
        batches.clear();
        if (encoded == null) {
            return;
        }
        int i = 0;
        while (i < encoded.size()) {
            String line = encoded.get(i);
            if (!line.startsWith("BATCH ")) {
                i++;
                continue;
            }
            int n = Integer.parseInt(line.substring(6));
            List<String> batch = new ArrayList<>();
            for (int j = 0; j < n && i + 1 + j < encoded.size(); j++) {
                batch.add(encoded.get(i + 1 + j));
            }
            batches.addLast(List.copyOf(batch));
            i += 1 + n;
        }
    }

    /** Restore a single legacy batch. */
    public synchronized void reset(List<String> names) {
        batches.clear();
        if (names != null && !names.isEmpty()) {
            batches.addFirst(List.copyOf(names));
        }
    }
}
