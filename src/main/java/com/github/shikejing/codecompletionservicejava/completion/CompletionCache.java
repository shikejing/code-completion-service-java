package com.github.shikejing.codecompletionservicejava.completion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.github.shikejing.codecompletionservicejava.config.ServiceProperties;
import com.github.shikejing.codecompletionservicejava.model.CompletionCandidate;

/**
 * Bounded LRU cache of completion results.
 *
 * <p>The key includes the document version and an offset, so an edit simply
 * makes every older key unreachable: stale results can never be served and
 * can never overwrite newer ones (there is no in-place update of a key).
 * {@link #invalidateVersionOlderThan(String, long)} additionally evicts old
 * versions of a document so the cache cannot grow with its edit history; the
 * global size is capped by {@code completion.cache.max-entries}.</p>
 */
@Component
public class CompletionCache {

    public record Key(String uri, long version, int offset, String configHash) {}

    private final ServiceProperties properties;
    private final LinkedHashMap<Key, List<CompletionCandidate>> map;
    private long hits;
    private long misses;

    public CompletionCache(ServiceProperties properties) {
        this.properties = properties;
        int capacity = Math.max(16, properties.getCache().getMaxEntries());
        this.map = new LinkedHashMap<>(Math.min(1024, capacity), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, List<CompletionCandidate>> eldest) {
                return size() > capacity;
            }
        };
    }

    public List<CompletionCandidate> get(Key key) {
        if (!properties.getCache().isEnabled()) {
            return null;
        }
        synchronized (map) {
            List<CompletionCandidate> value = map.get(key);
            if (value != null) {
                hits++;
                return copy(value);
            }
            misses++;
            return null;
        }
    }

    public void put(Key key, List<CompletionCandidate> value) {
        if (!properties.getCache().isEnabled()) {
            return;
        }
        synchronized (map) {
            // never overwrite a cached (immutable) entry with a different one
            if (map.containsKey(key)) {
                return;
            }
            map.put(key, copy(value));
        }
    }

    /** Removes every cached entry of a document (edit/close). */
    public void invalidate(String uri) {
        synchronized (map) {
            map.keySet().removeIf(k -> k.uri().equals(uri));
        }
    }

    /** Evicts entries belonging strictly to older versions of one document. */
    public void invalidateVersionOlderThan(String uri, long version) {
        synchronized (map) {
            map.keySet().removeIf(k -> k.uri().equals(uri) && k.version() < version);
        }
    }

    public int size() {
        synchronized (map) {
            return map.size();
        }
    }

    public void clear() {
        synchronized (map) {
            map.clear();
            hits = 0;
            misses = 0;
        }
    }

    public long hitCount() {
        synchronized (map) {
            return hits;
        }
    }

    public long missCount() {
        synchronized (map) {
            return misses;
        }
    }

    private static List<CompletionCandidate> copy(List<CompletionCandidate> value) {
        return List.copyOf(value);
    }

    /** Test/diagnostic snapshot view. */
    List<Key> keysView() {
        synchronized (map) {
            return new ArrayList<>(map.keySet());
        }
    }
}
