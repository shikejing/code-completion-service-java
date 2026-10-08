package com.github.shikejing.codecompletionservicejava.model;

/**
 * Result of attempting to apply an {@link Edit}.
 */
public record EditOutcome(
        EditOutcome.Status status,
        long appliedVersion,
        String message
) {
    public enum Status {
        /** Edit applied and advanced the document state. */
        APPLIED,
        /** Same edit had already been applied; state untouched (idempotent). */
        DUPLICATE,
        /** Stale version or superseded edit; rejected according to policy. */
        REJECTED_STALE,
        /** Version number skipped; rejected according to policy. */
        REJECTED_VERSION_GAP,
        /** Range outside the current document bounds. */
        REJECTED_INVALID_RANGE,
        /** Document is closed. */
        REJECTED_CLOSED,
        /** Service-wide tracked-document limit reached. */
        REJECTED_LIMIT
    }

    public static EditOutcome applied(long version) {
        return new EditOutcome(Status.APPLIED, version, "applied");
    }

    public static EditOutcome duplicate(long version) {
        return new EditOutcome(Status.DUPLICATE, version, "duplicate edit ignored");
    }

    public static EditOutcome rejected(Status status, long version, String message) {
        return new EditOutcome(status, version, message);
    }
}
