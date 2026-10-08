package com.github.shikejing.codecompletionservicejava.analysis;

import java.util.List;
import java.util.Set;

/**
 * Fault-tolerant lexer for the Java subset understood by the completion
 * engine. Unterminated strings, comments and stray characters are emitted as
 * ordinary {@link Kind#STRING}/{@link Kind#BAD} tokens running to EOF rather
 * than aborting the scan, so downstream parsing always has a complete token
 * stream to work with.
 */
public final class Lexer {

    public enum TokenKind {
        IDENTIFIER, KEYWORD, NUMBER, STRING, CHAR,
        LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET,
        SEMICOLON, COMMA, DOT, ASSIGN, OPERATOR, EOF, BAD
    }

    public record Token(TokenKind kind, String text, int start, int end, int line) {
        /** Provided for readability in tests/diagnostics. */
        @Override
        public String toString() {
            return kind + "('" + text + "'@" + start + ".."+ end + ",L" + line + ")";
        }
    }

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while", "var", "record", "yield",
            "sealed", "permits", "non-sealed", "true", "false", "null"
    );

    private final String text;

    public Lexer(String text) {
        this.text = text == null ? "" : text;
    }

    public List<Token> tokenize() {
        java.util.List<Token> tokens = new java.util.ArrayList<>();
        int n = text.length();
        int i = 0;
        int line = 0;
        while (i < n) {
            if ((i & 0x3FFF) == 0) {
                com.github.shikejing.codecompletionservicejava.completion.CancellationProbe.checkPoint();
            }
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
                i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int start = i;
            int startLine = line;
            // comments
            if (c == '/' && i + 1 < n) {
                if (text.charAt(i + 1) == '/') {
                    i += 2;
                    while (i < n && text.charAt(i) != '\n') {
                        i++;
                    }
                    continue;
                }
                if (text.charAt(i + 1) == '*') {
                    i += 2;
                    boolean closed = false;
                    while (i < n) {
                        if (text.charAt(i) == '\n') {
                            line++;
                        }
                        if (text.charAt(i) == '*' && i + 1 < n && text.charAt(i + 1) == '/') {
                            i += 2;
                            closed = true;
                            break;
                        }
                        i++;
                    }
                    if (!closed) {
                        // untermined block comment: tolerate silently (no token)
                    }
                    continue;
                }
            }
            // string literal (fault-tolerant: runs to EOF if unclosed)
            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < n) {
                    char d = text.charAt(i);
                    if (d == '\\' && i + 1 < n) {
                        i += 2;
                        continue;
                    }
                    if (d == '\n') {
                        line++;
                    }
                    i++;
                    if (d == quote) {
                        break;
                    }
                }
                tokens.add(new Token(quote == '"' ? TokenKind.STRING : TokenKind.CHAR,
                        text.substring(start, i), start, i, startLine));
                continue;
            }
            // identifier / keyword
            if (Character.isJavaIdentifierStart(c)) {
                i++;
                while (i < n && Character.isJavaIdentifierPart(text.charAt(i))) {
                    i++;
                }
                String word = text.substring(start, i);
                TokenKind kind = KEYWORDS.contains(word) ? TokenKind.KEYWORD : TokenKind.IDENTIFIER;
                tokens.add(new Token(kind, word, start, i, startLine));
                continue;
            }
            // number
            if (Character.isDigit(c)) {
                i++;
                while (i < n && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token(TokenKind.NUMBER, text.substring(start, i), start, i, startLine));
                continue;
            }
            // punctuation / operators
            switch (c) {
                case '(' -> { tokens.add(new Token(TokenKind.LPAREN, "(", start, i + 1, startLine)); i++; continue; }
                case ')' -> { tokens.add(new Token(TokenKind.RPAREN, ")", start, i + 1, startLine)); i++; continue; }
                case '{' -> { tokens.add(new Token(TokenKind.LBRACE, "{", start, i + 1, startLine)); i++; continue; }
                case '}' -> { tokens.add(new Token(TokenKind.RBRACE, "}", start, i + 1, startLine)); i++; continue; }
                case '[' -> { tokens.add(new Token(TokenKind.LBRACKET, "[", start, i + 1, startLine)); i++; continue; }
                case ']' -> { tokens.add(new Token(TokenKind.RBRACKET, "]", start, i + 1, startLine)); i++; continue; }
                case ';' -> { tokens.add(new Token(TokenKind.SEMICOLON, ";", start, i + 1, startLine)); i++; continue; }
                case ',' -> { tokens.add(new Token(TokenKind.COMMA, ",", start, i + 1, startLine)); i++; continue; }
                case '.' -> { tokens.add(new Token(TokenKind.DOT, ".", start, i + 1, startLine)); i++; continue; }
                case '=' -> {
                    if (i + 1 < n && text.charAt(i + 1) == '=') {
                        tokens.add(new Token(TokenKind.OPERATOR, "==", start, i + 2, startLine));
                        i += 2;
                    } else {
                        tokens.add(new Token(TokenKind.ASSIGN, "=", start, i + 1, startLine));
                        i++;
                    }
                    continue;
                }
                default -> {
                    // consume one char of any operator; multi-char ops are still tokenized fine
                    tokens.add(new Token(TokenKind.OPERATOR, String.valueOf(c), start, i + 1, startLine));
                    i++;
                }
            }
        }
        tokens.add(new Token(TokenKind.EOF, "", n, n, line));
        return List.copyOf(tokens);
    }

    public static boolean isKeyword(String word) {
        return word != null && KEYWORDS.contains(word);
    }
}
