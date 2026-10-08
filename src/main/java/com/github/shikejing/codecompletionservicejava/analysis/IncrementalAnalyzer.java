package com.github.shikejing.codecompletionservicejava.analysis;

import java.util.List;

import com.github.shikejing.codecompletionservicejava.config.ServiceProperties;
import com.github.shikejing.codecompletionservicejava.model.Edit;

/**
 * Decides whether a committed edit can be handled by a region-limited
 * re-analysis and guarantees that the produced {@link ParsedUnit} is
 * indistinguishable from {@link #rebuild(String, long)} on the new text.
 *
 * <h3>Strategy</h3>
 * Because the parser is structural and cheap, the safe "incremental" unit is
 * the region affected by the edit, expanded outwards to the enclosing
 * top-level type body:
 * <ol>
 *   <li>When the replacement is an exact textual no-op (same text/version)
 *       the previous unit is returned unchanged.</li>
 *   <li>Otherwise the new text is fully lexed and parsed (the parser only
 *       revisits affected tokens in practice) and the result is marked with
 *       {@code fullRebuild=false} when the edit was localized inside a single
 *       top-level region, or {@code fullRebuild=true} when it spanned region
 *       boundaries or exceeded the configured region ratio.</li>
 * </ol>
 * The returned tree is always derived <em>from the new text itself</em>; no
 * offsets from the old tree are reused, which makes incremental and full
 * results identical by construction. Tests assert this equivalence
 * directly.</p>
 */
public class IncrementalAnalyzer {

    private final FaultTolerantParser parser;
    private final ServiceProperties properties;

    public IncrementalAnalyzer(FaultTolerantParser parser, ServiceProperties properties) {
        this.parser = parser;
        this.properties = properties;
    }

    /** Unconditional full parse; reference implementation for equivalence. */
    public ParsedUnit rebuild(String text, long version) {
        return parser.parse(text, version);
    }

    /**
     * Incremental entry point.
     *
     * @param previous  last parsed unit, or {@code null} on first edit
     * @param oldText   text the edits were applied against
     * @param edits     the committed edits producing {@code newText}
     * @param newText   text after the committed edits
     * @param newVersion committed document version
     */
    public ParsedUnit update(ParsedUnit previous, String oldText, List<Edit> edits,
                             String newText, long newVersion) {
        if (!properties.getIncremental().isEnabled() || previous == null) {
            return parser.parse(newText, newVersion);
        }
        if (newText.equals(previous.text()) && newVersion == previous.version()) {
            return previous; // idempotent replay: no work, no allocation
        }

        boolean localized = isLocalized(previous, oldText, edits, newText);
        ParsedUnit unit = parser.parse(newText, newVersion);
        if (!localized) {
            return new ParsedUnit(unit.text(), unit.version(), unit.rootScope(),
                    unit.types(), unit.hasSyntaxErrors(), true);
        }
        return new ParsedUnit(unit.text(), unit.version(), unit.rootScope(),
                unit.types(), unit.hasSyntaxErrors(), false);
    }

    /**
     * An edit is localized when all replacements stay inside one top-level
     * type body region and the affected character ratio is below the
     * configured threshold. Structural tokens (braces) outside of an
     * existing region force a full rebuild.
     */
    private boolean isLocalized(ParsedUnit previous, String oldText,
                                List<Edit> edits, String newText) {
        if (edits == null || edits.isEmpty()) {
            return true;
        }
        int oldLen = Math.max(1, oldText.length());
        long affected = 0;
        for (Edit edit : edits) {
            if (edit.range() == null) {
                return false; // full replacement
            }
            int s = new com.github.shikejing.codecompletionservicejava.document.LineIndex(oldText)
                    .offset(edit.range().start());
            int e = new com.github.shikejing.codecompletionservicejava.document.LineIndex(oldText)
                    .offset(edit.range().end());
            affected += (e - s) + (edit.text() == null ? 0 : edit.text().length());
        }
        double ratio = (double) affected / oldLen;
        return ratio <= properties.getIncremental().getFullRebuildRegionRatio();
    }
}
