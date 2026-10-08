package com.github.shikejing.codecompletionservicejava.persistence;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.github.shikejing.codecompletionservicejava.config.ServiceProperties;
import com.github.shikejing.codecompletionservicejava.document.CommitEvent;
import com.github.shikejing.codecompletionservicejava.document.DocumentManager;
import com.github.shikejing.codecompletionservicejava.model.DocumentSnapshot;

import jakarta.annotation.PostConstruct;

/**
 * Bridges committed events and the local file store.
 *
 * <p>Registered as a commit listener on {@link DocumentManager}, it runs
 * synchronously while the document write lock is held, so when an edit is
 * acknowledged its record is already durable (no partial state from an
 * unfinished request can survive):
 * <ul>
 *   <li>OPEN writes an authoritative snapshot;</li>
 *   <li>CHANGE appends one framed delta record containing the exact range
 *       replacement, and compacts into a fresh snapshot every
 *       {@link #COMPACT_EVERY} deltas;</li>
 *   <li>CLOSE deletes the document directory (a closed document has no
 *       recoverable state, matching the in-memory semantics).</li>
 * </ul>
 */
@Service
public class PersistenceService {

    static final int COMPACT_EVERY = 20;

    private final ServiceProperties properties;
    private final DocumentManager documentManager;
    private final DocumentStore store;
    private final RecoveryService recoveryService;
    private final Map<String, Integer> deltasSinceSnapshot = new ConcurrentHashMap<>();

    public PersistenceService(ServiceProperties properties,
                              DocumentManager documentManager,
                              DocumentStore store,
                              RecoveryService recoveryService) {
        this.properties = properties;
        this.documentManager = documentManager;
        this.store = store;
        this.recoveryService = recoveryService;
    }

    @PostConstruct
    void start() {
        if (!properties.getPersistence().isEnabled()) {
            return;
        }
        Map<String, DocumentSnapshot> recovered = recoveryService.recoverAll();
        recovered.forEach((uri, snapshot) -> {
            documentManager.restoreState(uri, snapshot.text(), snapshot.version());
            deltasSinceSnapshot.put(uri, 0);
        });
        documentManager.addCommitListener(this::onCommitted);
    }

    private void onCommitted(CommitEvent event) {
        if (!properties.getPersistence().isEnabled()) {
            return;
        }
        String uri = event.uri();
        switch (event.edit().kind()) {
            case OPEN -> {
                store.writeSnapshot(uri, event.snapshot().version(), event.snapshot().text());
                deltasSinceSnapshot.put(uri, 0);
            }
            case CHANGE -> {
                store.appendRecord(PersistedRecord.delta(uri, event.snapshot().version(),
                        List.of(event.edit())));
                int next = deltasSinceSnapshot.merge(uri, 1, Integer::sum);
                if (next >= COMPACT_EVERY) {
                    store.writeSnapshot(uri, event.snapshot().version(), event.snapshot().text());
                    deltasSinceSnapshot.put(uri, 0);
                }
            }
            case CLOSE -> {
                store.remove(uri);
                deltasSinceSnapshot.remove(uri);
            }
        }
    }
}
