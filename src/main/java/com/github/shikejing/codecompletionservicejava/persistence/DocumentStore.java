package com.github.shikejing.codecompletionservicejava.persistence;

import java.util.List;
import com.github.shikejing.codecompletionservicejava.document.DocumentSnapshot;

/**
 * Durable storage of committed document state.
 *
 * Contracts:
 *  - only fully committed snapshots are persisted, atomically
 *    (temp file + fsync + atomic move); an unfinished write leaves the
 *    previous snapshot intact;
 *  - on restart the recovered texts/versions equal the last commit;
 *  - derived models/caches are NOT persisted: they are rebuilt lazily;
 *  - fixed input replayed after restart yields identical completion output.
 */
public interface DocumentStore {

    /** Atomically persist one document snapshot plus its recency names. */
    void saveSnapshot(DocumentSnapshot snapshot, List<String> recencyNames);

    record StoredDocument(DocumentSnapshot snapshot, List<String> recencyNames) {
    }

    List<StoredDocument> loadAll();

    void delete(String uri);

    void clear();

    boolean enabled();
}
