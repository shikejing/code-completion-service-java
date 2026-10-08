package com.github.shikejing.codecompletionservicejava.model;

/**
 * Result of attempting to apply a document change.
 */
public enum ChangeStatus {
    APPLIED,
    DUPLICATE,
    REJECTED_STALE,
    REBASED,
    BUFFERED,
    REJECTED_OVERLAP,
    REJECTED_FUTURE_LIMIT,
    CLOSED
}
