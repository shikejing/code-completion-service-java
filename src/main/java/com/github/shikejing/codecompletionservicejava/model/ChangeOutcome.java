package com.github.shikejing.codecompletionservicejava.model;

import java.util.List;

/**
 * Result of processing one change notification.
 *
 * @param status         terminal handling decision
 * @param version        document version visible after processing
 * @param baseVersion    base claimed by the notification
 * @param appliedVersions versions committed during this call (chain drain)
 * @param replacedStart  absolute start offset of the union of applied edits in
 *                       the pre-commit text (-1 if nothing applied)
 * @param replacedOldEnd absolute end offset of that union pre-commit
 * @param replacedNewEnd absolute end offset of that union post-commit
 */
public record ChangeOutcome(String uri,
                            ChangeStatus status,
                            int version,
                            int baseVersion,
                            List<Integer> appliedVersions,
                            int replacedStart,
                            int replacedOldEnd,
                            int replacedNewEnd) {

    public static ChangeOutcome noop(String uri, ChangeStatus status, int version,
                                     int baseVersion) {
        return new ChangeOutcome(uri, status, version, baseVersion, List.of(), -1, -1, -1);
    }
}
