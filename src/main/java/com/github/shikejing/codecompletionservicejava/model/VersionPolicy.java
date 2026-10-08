package com.github.shikejing.codecompletionservicejava.model;

/**
 * How the synchronizer reacts to version anomalies.
 */
public enum VersionPolicy {
    /** Reject stale versions AND version gaps; exact duplicate edits are idempotent. */
    STRICT,
    /** Reject stale versions, accept version gaps (missing versions are skipped). */
    LENIENT_GAP,
    /** Accept anything with a non-decreasing version (not recommended). */
    ACCEPT_ALL
}
