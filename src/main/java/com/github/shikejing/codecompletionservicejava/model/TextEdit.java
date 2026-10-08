package com.github.shikejing.codecompletionservicejava.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A single range-replacement edit. Range is expressed against the
 * document version identified by the enclosing change.
 */
public record TextEdit(Range range, String newText) {

    public TextEdit {
        newText = newText == null ? "" : newText;
    }

    public static TextEdit of(Position start, Position end, String newText) {
        return new TextEdit(Range.of(start, end), newText);
    }

    private static int comparePositions(Position a, Position b) {
        int c = Integer.compare(a.line(), b.line());
        return c != 0 ? c : Integer.compare(a.character(), b.character());
    }

    /**
     * Multiple edits in one change must be non-overlapping and are applied
     * in reverse document order after validation against a common base.
     * Touching boundaries (one ends where the next begins) are allowed.
     */
    public static boolean overlaps(TextEdit a, TextEdit b) {
        // disjoint if a ends at/before b starts, or b ends at/before a starts
        boolean aBeforeB = comparePositions(a.range().end(), b.range().start()) <= 0;
        boolean bBeforeA = comparePositions(b.range().end(), a.range().start()) <= 0;
        return !aBeforeB && !bBeforeA;
    }

    /** Document order, earliest start first; stable on ties. */
    public static List<TextEdit> sorted(List<TextEdit> edits) {
        List<TextEdit> copy = new ArrayList<>(edits);
        copy.sort(Comparator.comparing(e -> e, (x, y) -> {
            int c = comparePositions(x.range().start(), y.range().start());
            if (c != 0) {
                return c;
            }
            return comparePositions(x.range().end(), y.range().end());
        }));
        return List.copyOf(copy);
    }
}
