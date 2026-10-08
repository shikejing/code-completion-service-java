package com.github.shikejing.codecompletionservicejava.runtime;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.github.shikejing.codecompletionservicejava.completion.CancellationToken;

/**
 * Tracks in-flight requests by id so they can be cancelled from outside.
 * Registration is bounded: entries are removed as soon as the request ends,
 * so the map size never exceeds the true in-flight count.
 */
@Component
public class RequestRegistry {

    private final ConcurrentHashMap<String, CancellationToken> active = new ConcurrentHashMap<>();

    public String register(CancellationToken token) {
        String id = "req-" + Long.toHexString(System.nanoTime())
                + "-" + Integer.toHexString(System.identityHashCode(token));
        active.put(id, token);
        return id;
    }

    public void finish(String requestId) {
        if (requestId != null) {
            active.remove(requestId);
        }
    }

    /** Registers a client-chosen id unless it is already active; returns existing token on clash. */
    public CancellationToken putIfActive(String requestId, CancellationToken token) {
        var result = active.putIfAbsent(requestId, token);
        return result;
    }

    public boolean cancel(String requestId) {
        CancellationToken token = active.get(requestId);
        if (token == null) {
            return false;
        }
        token.cancel();
        return true;
    }

    public int inFlightCount() {
        return active.size();
    }

    public Set<String> activeRequestIds() {
        return Set.copyOf(active.keySet());
    }
}
