package com.github.shikejing.codecompletionservicejava.parse;

import java.util.ArrayList;
import java.util.List;
import com.github.shikejing.codecompletionservicejava.analysis.ScopeNode;

/**
 * Full (from-scratch) analysis: lex -> recover -> parse.
 */
public final class SyntaxAnalyzer {

    private final Lexer lexer;
    private final Parser parser;
    private final long artificialDelayMs;

    public SyntaxAnalyzer(Lexer lexer, Parser parser) {
        this(lexer, parser, 0L);
    }

    public SyntaxAnalyzer(Lexer lexer, Parser parser, long artificialDelayMs) {
        this.lexer = lexer;
        this.parser = parser;
        this.artificialDelayMs = Math.max(0L, artificialDelayMs);
    }

    public Parser parser() {
        return parser;
    }

    public Lexer lexer() {
        return lexer;
    }

    public SyntaxModel analyze(String uri, int version, String text) {
        maybeDelay();
        LineIndex lineIndex = new LineIndex(text);
        List<Token> tokens = lexer.tokenize(text);
        List<ParseError> parseErrors = new ArrayList<>();
        ScopeNode root = parser.parseDocument(text, tokens, parseErrors);
        List<String> errors = new ArrayList<>(parseErrors.size());
        for (ParseError e : parseErrors) {
            errors.add(e.format());
        }
        return new SyntaxModel(uri, version, text, lineIndex, tokens, root,
                List.copyOf(errors), false);
    }

    private void maybeDelay() {
        if (artificialDelayMs > 0) {
            try {
                Thread.sleep(artificialDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
