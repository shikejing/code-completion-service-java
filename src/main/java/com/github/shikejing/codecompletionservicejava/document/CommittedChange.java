package com.github.shikejing.codecompletionservicejava.document;

import java.util.List;
import com.github.shikejing.codecompletionservicejava.document.EditApplier.AbsoluteRange;

/**
 * Record of one committed change, keeping the absolute spans it replaced so a
 * later stale edit can be deterministically rebased across it without
 * retaining old document snapshots.
 *
 * @param version   version produced by the commit
 * @param baseVersion version the commit was computed against
 * @param fullSync  true for whole-text syncs (they cannot be rebased across)
 * @param spans     replacements in pre-commit absolute offsets
 */
public record CommittedChange(int version, int baseVersion, boolean fullSync,
                              List<AbsoluteRange> spans) {
}
