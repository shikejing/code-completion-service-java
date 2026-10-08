package com.github.shikejing.codecompletionservicejava.document;

import java.util.ArrayList;
import java.util.List;
import com.github.shikejing.codecompletionservicejava.model.TextEdit;
import com.github.shikejing.codecompletionservicejava.parse.LineIndex;

/**
 * Pure functions for applying range-replacement edits to text.
 *
 * Edits are validated against one common base text and applied as a single
 * atomic transformation: either all are valid and the new text is produced,
 * or none are applied. Multiple edits in one notification are applied in
 * reverse document order so earlier offsets stay valid; overlapping edits
 * (beyond touching boundaries) are rejected before any mutation.
 */
public final class EditApplier {

    /** Thrown when an edit does not fit the base text; message is deterministic. */
    public static final class InvalidEditException extends RuntimeException {
        public InvalidEditException(String message) {
            super(message);
        }
    }

    /** One range replacement expressed in absolute offsets. */
    public record AbsoluteRange(int start, int end, String newText) {
    }

    private EditApplier() {
    }

    public static String apply(String base, List<TextEdit> edits, LineIndex lineIndex) {
        if (edits == null || edits.isEmpty()) {
            return base;
        }
        List<TextEdit> sorted = TextEdit.sorted(edits);
        for (int i = 1; i < sorted.size(); i++) {
            if (TextEdit.overlaps(sorted.get(i - 1), sorted.get(i))) {
                throw new InvalidEditException("overlapping edits in one change at indices "
                        + (i - 1) + " and " + i);
            }
        }
        List<AbsoluteRange> absolutes = new ArrayList<>();
        for (TextEdit edit : sorted) {
            int start = lineIndex.offset(edit.range().start());
            int end = lineIndex.offset(edit.range().end());
            if (start < 0 || end < start || end > base.length()) {
                throw new InvalidEditException("edit range out of bounds: ["
                        + start + ", " + end + ") length=" + base.length());
            }
            absolutes.add(new AbsoluteRange(start, end, edit.newText()));
        }
        return applyAbsolute(base, absolutes);
    }

    /**
     * Translate an absolute offset across one prior replacement, as used when
     * rebasing a stale edit onto a newer base:
     * <pre>
     *   offset &lt;= oldStart  -&gt; unchanged
     *   offset &gt;= oldEnd    -&gt; shifted by (newLength - (oldEnd-oldStart))
     *   inside removed text -&gt; clamped to oldStart
     * </pre>
     */
    public static int translateOffset(int offset, int oldStart, int oldEnd,
                                      int newLength) {
        if (offset <= oldStart) {
            return offset;
        }
        if (offset >= oldEnd) {
            return offset + newLength - (oldEnd - oldStart);
        }
        return oldStart;
    }

    /**
     * Rebase one edit against an intervening replacement. Returns translated
     * absolute offsets; {@code null} means the edit fell strictly inside text
     * that was removed and cannot be applied deterministically.
     */
    public static AbsoluteRange rebase(TextEdit edit, LineIndex oldIndex,
                                       int priorStart, int priorOldEnd,
                                       int priorNewLength) {
        int s = oldIndex.offset(edit.range().start());
        int e = oldIndex.offset(edit.range().end());
        if ((s > priorStart && s < priorOldEnd)
                || (e > priorStart && e < priorOldEnd)) {
            return null;
        }
        int ns = translateOffset(s, priorStart, priorOldEnd, priorNewLength);
        int ne = translateOffset(e, priorStart, priorOldEnd, priorNewLength);
        return new AbsoluteRange(ns, ne, edit.newText());
    }

    /** Apply edits expressed in absolute offsets, in reverse document order. */
    public static String applyAbsolute(String base, List<AbsoluteRange> edits) {
        List<AbsoluteRange> all = new ArrayList<>(edits);
        all.sort((a, b) -> Integer.compare(b.start, a.start));
        String result = base;
        for (AbsoluteRange a : all) {
            if (a.start < 0 || a.end < a.start || a.end > result.length()) {
                throw new InvalidEditException("edit out of bounds: ["
                        + a.start + ", " + a.end + ") length=" + result.length());
            }
            result = result.substring(0, a.start) + a.newText + result.substring(a.end);
        }
        return result;
    }
}
