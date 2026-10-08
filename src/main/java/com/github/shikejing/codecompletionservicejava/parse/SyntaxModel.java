package com.github.shikejing.codecompletionservicejava.parse;

import java.util.List;
import com.github.shikejing.codecompletionservicejava.analysis.ScopeNode;

/**
 * Immutable analysis result for one committed document version.
 */
public final class SyntaxModel {

    private final String uri;
    private final int version;
    private final String text;
    private final LineIndex lineIndex;
    private final List<Token> tokens;
    private final ScopeNode rootScope;
    private final List<String> errors;
    private final boolean incremental;

    public SyntaxModel(String uri, int version, String text, LineIndex lineIndex,
                       List<Token> tokens, ScopeNode rootScope, List<String> errors,
                       boolean incremental) {
        this.uri = uri;
        this.version = version;
        this.text = text;
        this.lineIndex = lineIndex;
        this.tokens = List.copyOf(tokens);
        this.rootScope = rootScope;
        this.errors = List.copyOf(errors);
        this.incremental = incremental;
    }

    public String uri() {
        return uri;
    }

    public int version() {
        return version;
    }

    public String text() {
        return text;
    }

    public LineIndex lineIndex() {
        return lineIndex;
    }

    public List<Token> tokens() {
        return tokens;
    }

    public ScopeNode rootScope() {
        return rootScope;
    }

    public List<String> errors() {
        return errors;
    }

    /** Whether this model was produced by incremental re-analysis. */
    public boolean incremental() {
        return incremental;
    }
}
