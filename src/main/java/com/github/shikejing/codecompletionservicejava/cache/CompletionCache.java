package com.github.shikejing.codecompletionservicejava.cache;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.github.shikejing.codecompletionservicejava.model.CompletionItem;
import com.github.shikejing.codecompletionservicejava.model.Position;

/**
 * Bounded, per-document completion cache with version gating.
 *
 * <ul>
 *   <li>keys are {@code (version, position)}; an entry is usable only while
 *       the document is still at exactly that version;</li>
 *   <li>every committed edit removes every entry older than the new
 *       version, so an invalidated result can never be served;</li>
 *   <li>{@code putIfAbsent} stores a computed entry only when (a) no entry for
 *       the key exists and (b) the document is still at the entry's version —
 *       a slow query that finished after a newer commit can never overwrite
 *       newer state, and its side effect is discarded;</li>
 *   <li>hits and misses return the exact same immutable item list;</li>
 *   <li>each document map is bounded LRU and whole maps are removed together
 *       with closed documents, so total occupancy stays bounded.</li>
 * </ul>
 */
public final class CompletionCache {

    private record Key(Position position, int version) {
    }

    private final int perDocumentCapacity;
    private final Map<String, LinkedHashMap<Key, List<CompletionItem>>> documents =
            new LinkedHashMap<>();
    private final Object lock = new Object();

    public CompletionCache(int perDocumentCapacity) {
        this.perDocumentCapacity = Math.max(1, perDocumentCapacity);
    }

    public Optional<List<CompletionItem>> get(String uri, int version, Position position) {
        synchronized (lock) {
            LinkedHashMap<Key, List<CompletionItem>> map = documents.get(uri);
            if (map == null) {
                return Optional.empty();
            }
            List<CompletionItem> items = map.get(new Key(position, version));
            return Optional.ofNullable(items).map(List::copyOf);
        }
    }

    /**
     * Stores only when the key is absent and the document is still at the
     * version the items were computed for.
     */
    public boolean putIfAbsent(String uri, int version, Position position,
                               List<CompletionItem> items, int currentVersion) {
        if (version != currentVersion) {
            return false;
        }
        synchronized (lock) {
            LinkedHashMap<Key, List<CompletionItem>> map =
                    documents.computeIfAbsent(uri, u -> new LinkedHashMap<>(64, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                Map.Entry<Key, List<CompletionItem>> eldest) {
                            return size() > perDocumentCapacity;
                        }
                    });
            Key key = new Key(position, version);
            if (map.containsKey(key)) {
                return false;
            }
            map.put(key, List.copyOf(items));
            return true;
        }
    }

    /** Drop every entry with a version older than the given one. */
    public void invalidateOlderThan(String uri, int version) {
        synchronized (lock) {
            LinkedHashMap<Key, List<CompletionItem>> map = documents.get(uri);
            if (map == null) {
                return;
            }
            Iterator<Map.Entry<Key, List<CompletionItem>>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getKey().version() < version) {
                    it.remove();
                }
            }
        }
    }

    public void invalidate(String uri) {
        synchronized (lock) {
            documents.remove(uri);
        }
    }

    public int size(String uri) {
        synchronized (lock) {
            LinkedHashMap<Key, List<CompletionItem>> map = documents.get(uri);
            return map == null ? 0 : map.size();
        }
    }

    /** Number of documents currently holding cache maps (diagnostics/tests). */
    public int documentCount() {
        synchronized (lock) {
            return documents.size();
        }
    }

    public List<String> cachedUris() {
        synchronized (lock) {
            return new ArrayList<>(documents.keySet());
        }
    }
}
