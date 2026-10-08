package com.github.shikejing.codecompletionservicejava.parse;

import java.util.ArrayList;
import java.util.List;
import com.github.shikejing.codecompletionservicejava.model.Position;

/**
 * Immutable mapping between zero-based offsets and {@link Position}s for one
 * specific text version.
 *
 * Line terminators are treated the same way the LSP does for the common
 * cases: '\n', '\r' and "\r\n" each start a new line, and '\r' not followed
 * by '\n' is a line break of length 1. Offsets/positions are deterministically
 * clamped to the document instead of throwing on out-of-range input.
 */
public final class LineIndex {

    private final String text;
    private final int length;
    private final int[] lineStarts;

    public LineIndex(String text) {
        this.text = text;
        this.length = text.length();
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                starts.add(i + 1);
            } else if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    starts.add(i + 2);
                    i++;
                } else {
                    starts.add(i + 1);
                }
            }
        }
        int[] arr = new int[starts.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = starts.get(i);
        }
        this.lineStarts = arr;
    }

    /** Convert a position to an absolute offset, clamping to valid bounds. */
    public int offset(Position position) {
        int line = Math.max(0, Math.min(position.line(), lineStarts.length - 1));
        int lineStart = lineStarts[line];
        int lineEnd = (line + 1 < lineStarts.length) ? lineStarts[line + 1] : length;
        // Strip exactly one line terminator (CRLF length 2, otherwise 0/1),
        // detected from the text rather than guessed from line length.
        int terminatorLength = 0;
        if (lineEnd > lineStart && safeChar(lineEnd - 1) == '\n') {
            terminatorLength = (lineEnd - 1 > lineStart && safeChar(lineEnd - 2) == '\r') ? 2 : 1;
        }
        int contentEnd = lineEnd - terminatorLength;
        int character = Math.max(0, Math.min(position.character(), contentEnd - lineStart));
        return lineStart + character;
    }

    private char safeChar(int index) {
        return (index >= 0 && index < text.length()) ? text.charAt(index) : '\0';
    }

    /** Convert an absolute offset to a position, clamping to valid bounds. */
    public Position position(int offset) {
        int clamped = Math.max(0, Math.min(offset, length));
        int lo = 0;
        int hi = lineStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= clamped) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return Position.of(lo, clamped - lineStarts[lo]);
    }

    public int lineCount() {
        return lineStarts.length;
    }

    public List<Integer> lineStartOffsets() {
        List<Integer> out = new ArrayList<>(lineStarts.length);
        for (int s : lineStarts) {
            out.add(s);
        }
        return List.copyOf(out);
    }
}
