package com.github.shikejing.codecompletionservicejava.document;

import com.github.shikejing.codecompletionservicejava.model.DocumentSnapshot;
import com.github.shikejing.codecompletionservicejava.model.Edit;

/**
 * One committed synchronization event delivered to listeners while the
 * document write lock is still held.
 *
 * @param uri      document URI
 * @param snapshot state after the commit (open=false on CLOSE)
 * @param edit     the exact edit that was committed
 */
public record CommitEvent(String uri, DocumentSnapshot snapshot, Edit edit) {
}
