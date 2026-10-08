package com.github.shikejing.codecompletionservicejava.document;

import java.util.ArrayList;
import java.util.List;

import com.github.shikejing.codecompletionservicejava.model.Position;

/**
 * Maps between zero-based absolute character offsets and {@link Position}s.
 * Immutable; rebuilt from the full text of each committed version.
 *
 * <p>Lines are split on {@code '\n'}; a trailing {@code '\n'} creates one
 * final empty line. Positions past the end of a line are clamped to that
 * line's length so out-of-range edits can be detected by the caller via the
 * range validation rather than throwing here.</p>
 */
public final class LineIndex {

    private final String text;
    private final List<Integer> lineStarts = new ArrayList<>();

    public LineIndex(String text) {
        this.text = text;
        lineStarts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lineStarts.add(i + 1);
            }
        }
    }

    /**
     * Absolute offset of the position; character is clamped to the line
     * length. Positions on a not-yet-existing line resolve to EOF.
     */
    public int offset(Position position) {
        int line = Math.min(Math.max(position.line(), 0), lineStarts.size() - 1);
        int start = lineStarts.get(line);
        int end = (line + 1 < lineStarts.size()) ? lineStarts.get(line + 1) - 1 : text.length();
        int lineLength = end - start;
        int ch = Math.min(Math.max(position.character(), 0), lineLength);
        return start + ch;
    }

    /**
     * Strict conversion used for edit-range validation: returns the offset
     * only when both line and character are in range (half-open line ends
     * allowed), otherwise -1.
     */
    public int strictOffset(Position position) {
        int line = position.line();
        if (line < 0 || line >= lineStarts.size()) {
            return -1;
        }
        int start = lineStarts.get(line);
        int end = (line + 1 < lineStarts.size()) ? lineStarts.get(line + 1) - 1 : text.length();
        int lineLength = end - start;
        int ch = position.character();
        if (ch < 0 || ch > lineLength) {
            return -1;
        }
        return start + ch;
    }

    /** Position for an absolute offset (offset is clamped into {@code [0, length]}). */
    public Position position(int offset) {
        if (offset <= 0) {
            return new Position(0, 0);
        }
        if (offset > text.length()) {
            offset = text.length();
        }
        // binary search for the last line start <= offset
        int lo = 0;
        int hi = lineStarts.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts.get(mid) <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return new Position(lo, offset - lineStarts.get(lo));
    }

    public int lineCount() {
        return lineStarts.size();
    }
}
