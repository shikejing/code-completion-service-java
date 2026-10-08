package com.github.shikejing.codecompletionservicejava.persistence;

import java.util.List;
import com.github.shikejing.codecompletionservicejava.document.DocumentSnapshot;

/** Persistence disabled (empty dataDir). Restart starts empty. */
public final class NoopDocumentStore implements DocumentStore {

    @Override
    public void saveSnapshot(DocumentSnapshot snapshot, List<String> recencyNames) {
    }

    @Override
    public List<StoredDocument> loadAll() {
        return List.of();
    }

    @Override
    public void delete(String uri) {
    }

    @Override
    public void clear() {
    }

    @Override
    public boolean enabled() {
        return false;
    }
}
