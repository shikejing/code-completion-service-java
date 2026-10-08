package com.github.shikejing.codecompletionservicejava.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Fault-tolerant lexer for the small Java-like language. Unrecognized
 * characters become single PUNCTUATION tokens instead of aborting, so the
 * parser always receives a complete token stream even for broken text.
 *
 * Recognized: identifiers/keywords, integer/decimal numbers, "..." strings
 * with escapes, // and &#47;* *&#47; comments, and punctuation runs. Strings
 * and block comments that stay unterminated simply run to EOF.
 *
 * Besides a full {@link #tokenize(String)} scan it supports state-aware
 * region lexing ({@link #lexRegion}), which the incremental analyzer uses to
 * re-lex only the window around an edit while reusing tokens outside it.
 */
public final class Lexer {

    static final Set<String> KEYWORDS = Set.of(
            "abstract", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
            "int", "interface", "long", "native", "new", "null", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp", "super",
            "switch", "synchronized", "this", "throw", "throws", "transient", "try",
            "void", "volatile", "while", "true", "false", "var", "record", "enum",
            "String");

    /** Lexer state at an offset: CODE, or inside a line/block comment or string. */
    public enum LexState { CODE, LINE_COMMENT, BLOCK_COMMENT, STRING }

    public Lexer() {
    }

    public List<Token> tokenize(String text) {
        List<Token> out = new ArrayList<>();
        lexInto(text, 0, text.length(), out);
        return List.copyOf(out);
    }

    /**
     * Lex exactly the region [start,end). The scan begins at {@code start},
     * which must be a point where the lexer is in {@link LexState#CODE} in
     * both the old and new text (the incremental analyzer guarantees this by
     * widening the window). Tokens that start inside the region are returned;
     * a token straddling {@code end} is still included (the caller widens
     * until no token straddles). No EOF token is appended.
     */
    public List<Token> lexRegion(String text, int start, int end) {
        List<Token> raw = new ArrayList<>();
        lexInto(text, start, Integer.MAX_VALUE, raw);
        List<Token> region = new ArrayList<>();
        for (Token t : raw) {
            if (t.type() == TokenType.EOF) {
                break;
            }
            if (t.startOffset() >= start && t.startOffset() < end) {
                region.add(t);
            } else if (t.startOffset() >= end) {
                break;
            }
        }
        return List.copyOf(region);
    }

    /**
     * Classify the lexer state at a candidate boundary offset by checking the
     * token that contains it. CODE means a safe splice point.
     */
    public LexState stateAt(String text, int offset, List<Token> tokens) {
        for (Token t : tokens) {
            if (t.type() == TokenType.EOF) {
                break;
            }
            if (t.startOffset() <= offset && offset < t.endOffset()) {
                return switch (t.type()) {
                    case COMMENT -> t.text().startsWith("//")
                            ? LexState.LINE_COMMENT : LexState.BLOCK_COMMENT;
                    case STRING -> LexState.STRING;
                    default -> LexState.CODE;
                };
            }
            if (t.startOffset() >= offset) {
                break;
            }
        }
        return LexState.CODE;
    }

    private void lexInto(String text, int start, int hardEnd, List<Token> tokens) {
        int n = Math.min(text.length(), hardEnd);
        int i = start;
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                int s = i;
                i += 2;
                while (i < n && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
                    i++;
                }
                tokens.add(new Token(TokenType.COMMENT, text.substring(s, i), s, i));
                continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int s = i;
                i += 2;
                while (i < n && !(text.charAt(i) == '*' && i + 1 < n
                        && text.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                tokens.add(new Token(TokenType.COMMENT, text.substring(s, i), s, i));
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int s = i;
                i++;
                while (i < n && Character.isJavaIdentifierPart(text.charAt(i))) {
                    i++;
                }
                String word = text.substring(s, i);
                TokenType type = KEYWORDS.contains(word)
                        ? TokenType.KEYWORD : TokenType.IDENTIFIER;
                tokens.add(new Token(type, word, s, i));
                continue;
            }
            if (Character.isDigit(c)
                    || (c == '.' && i + 1 < n && Character.isDigit(text.charAt(i + 1)))) {
                int s = i;
                i++;
                while (i < n && (Character.isDigit(text.charAt(i))
                        || text.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token(TokenType.NUMBER, text.substring(s, i), s, i));
                continue;
            }
            if (c == '"' || c == '\'') {
                int s = i;
                char quote = c;
                i++;
                while (i < n && text.charAt(i) != quote) {
                    if (text.charAt(i) == '\\' && i + 1 < n) {
                        i += 2;
                    } else {
                        i++;
                    }
                }
                if (i < n) {
                    i++;
                }
                tokens.add(new Token(TokenType.STRING, text.substring(s, i), s, i));
                continue;
            }
            tokens.add(new Token(TokenType.PUNCTUATION, String.valueOf(c), i, i + 1));
            i++;
        }
        tokens.add(new Token(TokenType.EOF, "", n, n));
    }
}
