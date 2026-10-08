package com.github.shikejing.codecompletionservicejava.persistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.github.shikejing.codecompletionservicejava.document.LineIndex;
import com.github.shikejing.codecompletionservicejava.model.DocumentSnapshot;
import com.github.shikejing.codecompletionservicejava.model.Edit;
import com.github.shikejing.codecompletionservicejava.model.TextRange;

/**
 * Rebuilds committed document state at process start.
 *
 * <p>Replay order is the last snapshot followed by its delta records in file
 * order; each delta's range replacement is applied to the current text. A
 * snapshot is authoritative on its own. Only fully framed (CRC/length
 * validated) records take part, so torn writes from a crashed process can
 * never surface as partial data.</p>
 */
@Component
public class RecoveryService {

    private final DocumentStore store;

    public RecoveryService(DocumentStore store) {
        this.store = store;
    }

    public Map<String, DocumentSnapshot> recoverAll() {
        Map<String, DocumentSnapshot> result = new LinkedHashMap<>();
        for (String uri : store.recoverUris()) {
            DocumentSnapshot snapshot = recoverOne(uri);
            if (snapshot != null) {
                result.put(uri, snapshot);
            }
        }
        return result;
    }

    public DocumentSnapshot recoverOne(String uri) {
        List<PersistedRecord> records = store.recover(uri);
        if (records.isEmpty()) {
            return null;
        }
        String text = "";
        long version = -1;
        for (PersistedRecord record : records) {
            if (record.isSnapshot()) {
                text = record.fullText() == null ? "" : record.fullText();
                version = record.version();
                continue;
            }
            for (Edit edit : record.edits()) {
                String next = applyRangeReplace(text, edit);
                if (next != null) {
                    text = next;
                }
                // version always advances with the record (even a malformed
                // range that replay tolerates) to preserve commit ordering
                version = Math.max(version, record.version());
            }
            version = Math.max(version, record.version());
        }
        return new DocumentSnapshot(uri, text, version, true, 0L);
    }

    private String applyRangeReplace(String current, Edit edit) {
        if (edit.range() == null) {
            return edit.fullText() == null ? current : edit.fullText();
        }
        TextRange range = edit.range();
        LineIndex index = new LineIndex(current);
        int start = index.offset(range.start());
        int end = index.offset(range.end());
        if (start < 0 || end < start || end > current.length()) {
            return null;
        }
        String replacement = edit.text() == null ? "" : edit.text();
        return current.substring(0, start) + replacement + current.substring(end);
    }
}
