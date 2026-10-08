package com.github.shikejing.codecompletionservicejava.parse;

import java.util.ArrayList;
import java.util.List;
import com.github.shikejing.codecompletionservicejava.analysis.Symbol;
import com.github.shikejing.codecompletionservicejava.analysis.ScopeNode;
import com.github.shikejing.codecompletionservicejava.model.SymbolKind;

/**
 * Recovering syntactic analyzer for a small Java-like language.
 *
 * Recognized structure (enough for scopes and completion symbols):
 *  - top-level: package/import lines, {@code class|interface|enum|record T
 *    [extends/implements ...] { ... }};
 *  - type body: field declarations, methods (signature + block), nested types;
 *  - method/block bodies: local declarations (incl. {@code var}), parameters,
 *    nested blocks, control statements with optional bodies;
 *  - expressions are skipped structurally by brace/paren/semicolon balance so
 *    statement boundaries stay correct even with syntax errors.
 *
 * Recovery rules:
 *  - an unclosed block is closed at EOF (no error cascades);
 *  - an unexpected '}' closes the current block rather than aborting;
 *  - anything unrecognized is advanced one token and recorded as an error;
 *  - parsing never throws; all problems land in the returned error list.
 *
 * The same grammar is used by {@link #parseRegion} to re-analyze a single
 * affected scope incrementally; tokens outside [startOffset,endOffset) are
 * never inspected.
 */
public final class Parser {

    public enum RegionMode {
        /** File root: package/import and type declarations only. */
        ROOT,
        /** Type body: members between { and }. */
        TYPE,
        /** Method or plain block: statements between { and }. */
        BLOCK
    }

    private static final java.util.Set<String> MODIFIERS = java.util.Set.of(
            "public", "private", "protected", "static", "final", "abstract",
            "default", "synchronized", "native", "transient", "volatile", "strictfp");

    private List<Token> tokens;
    private List<ParseError> parseErrors;
    private String text;
    private int pos;
    private int hardEnd; // stop consuming tokens at/after this offset

    public Parser() {
    }

    public ScopeNode parse(String text, List<Token> tokens, List<String> errorsOut) {
        List<ParseError> collected = new ArrayList<>();
        ScopeNode root = parseDocument(text, tokens, collected);
        for (ParseError e : collected) {
            errorsOut.add(e.format());
        }
        return root;
    }

    /** Full document parse; returns structured errors. */
    public ScopeNode parseDocument(String text, List<Token> tokens, List<ParseError> errorsOut) {
        init(text, tokens, errorsOut, text.length());
        ScopeNode root = new ScopeNode(0, text.length(), null, false);
        parseTopLevel(root);
        return root;
    }

    private void init(String text, List<Token> tokens, List<ParseError> errorsOut, int hardEnd) {
        this.text = text;
        this.tokens = tokens;
        this.parseErrors = errorsOut;
        this.pos = 0;
        this.hardEnd = hardEnd;
    }

    /**
     * Parse only tokens contained in [startOffset, endOffset) and populate a
     * freshly created replacement scope. The replacement scope must already be
     * wired to its parent; offsets are absolute in the new document.
     *
     * @param mode  grammar valid inside the region
     * @param start first offset belonging to the scope (its opening brace)
     * @param end   offset just past the scope's closing brace (or text length)
     */
    public void parseRegion(String newText, List<Token> allTokens, RegionMode mode,
                            ScopeNode replacement, int start, int end,
                            List<ParseError> errorsOut) {
        init(newText, allTokens, errorsOut, end);
        // advance to first token starting at/after start
        while (pos < tokens.size() && tokens.get(pos).startOffset() < start) {
            pos++;
        }
        if (mode == RegionMode.TYPE) {
            if (atPunct("{")) {
                advance();
            }
            parseTypeBody(replacement);
        } else if (mode == RegionMode.BLOCK) {
            if (atPunct("{")) {
                advance();
            }
            parseStatements(replacement);
            // closing brace (if any) is consumed by caller's offset bookkeeping
            if (atPunct("}")) {
                replacement.setEndOffset(peek().endOffset());
                advance();
            } else {
                error(start, "unclosed block opened at offset " + start);
                replacement.setEndOffset(Math.min(end, newText.length()));
            }
        } else {
            parseTopLevel(replacement);
        }
    }

    private void error(int offset, String message) {
        parseErrors.add(new ParseError(offset, message));
    }

    private Token peek() {
        if (pos >= tokens.size() || tokens.get(pos).startOffset() >= hardEnd) {
            int off = Math.min(hardEnd, text.length());
            return new Token(TokenType.EOF, "", off, off);
        }
        return tokens.get(pos);
    }

    private boolean atPunct(String s) {
        Token t = peek();
        return t.type() == TokenType.PUNCTUATION && t.text().equals(s);
    }

    private boolean atKeyword(String s) {
        Token t = peek();
        return t.type() == TokenType.KEYWORD && t.text().equals(s);
    }

    private Token advance() {
        Token t = peek();
        if (t.type() != TokenType.EOF) {
            pos++;
        }
        return t;
    }

    private void parseTopLevel(ScopeNode root) {
        while (peek().type() != TokenType.EOF) {
            Token t = peek();
            if (t.type() == TokenType.PUNCTUATION && t.text().equals("}")) {
                error(t.startOffset(), "unmatched '}'");
                advance();
                continue;
            }
            if (t.type() == TokenType.KEYWORD
                    && (t.text().equals("class") || t.text().equals("interface")
                    || t.text().equals("enum") || t.text().equals("record"))) {
                parseTypeDeclaration(root);
                continue;
            }
            if (t.type() == TokenType.KEYWORD && MODIFIERS.contains(t.text())) {
                advance();
                if (atKeyword("class") || atKeyword("interface")
                        || atKeyword("enum") || atKeyword("record")) {
                    parseTypeDeclaration(root);
                    continue;
                }
                continue;
            }
            if (t.type() == TokenType.KEYWORD
                    && (t.text().equals("package") || t.text().equals("import"))) {
                skipToSemicolonOrBrace();
                continue;
            }
            error(t.startOffset(), "unexpected token '" + t.text() + "' at top level");
            advance();
        }
    }

    private void skipToSemicolonOrBrace() {
        while (peek().type() != TokenType.EOF && !atPunct(";")
                && !atPunct("{") && !atPunct("}")) {
            advance();
        }
        if (atPunct(";")) {
            advance();
        }
    }

    /** Parses a type declaration starting at the class/interface keyword. */
    private void parseTypeDeclaration(ScopeNode parent) {
        Token kindToken = advance(); // class/interface/enum/record
        Token nameToken = peek();
        if (nameToken.type() != TokenType.IDENTIFIER) {
            error(kindToken.startOffset(),
                    "expected type name after '" + kindToken.text() + "'");
            recoverToBodyOrEnd();
            return;
        }
        advance(); // name
        ScopeNode typeScope = parent.addChild(nameToken.startOffset(), true);
        parent.addSymbol(new Symbol(nameToken.text(), SymbolKind.TYPE,
                kindToken.text(), nameToken.startOffset(), parent.startOffset()));
        skipHeaderToBody();
        if (!atPunct("{")) {
            if (atPunct(";")) {
                advance();
            }
            typeScope.setEndOffset(nameToken.endOffset());
            return;
        }
        int braceStart = peek().startOffset();
        advance(); // '{'
        typeScope.setStartOffsetPublic(braceStart);
        parseTypeBody(typeScope);
    }

    private void recoverToBodyOrEnd() {
        while (peek().type() != TokenType.EOF && !atPunct("{") && !atPunct(";")) {
            advance();
        }
        if (atPunct("{")) {
            ScopeNode dummy = new ScopeNode(peek().startOffset(), text.length(), null, false);
            int brace = peek().startOffset();
            advance();
            dummy.setStartOffsetPublic(brace);
            parseTypeBody(dummy);
        } else if (atPunct(";")) {
            advance();
        }
    }

    private void skipHeaderToBody() {
        int depth = 0;
        while (peek().type() != TokenType.EOF) {
            if (atPunct("(") || atPunct("<")) {
                depth++;
                advance();
                continue;
            }
            if (atPunct(")") || atPunct(">")) {
                depth--;
                advance();
                continue;
            }
            if (depth == 0 && (atPunct("{") || atPunct(";"))) {
                return;
            }
            advance();
        }
    }

    private void parseTypeBody(ScopeNode typeScope) {
        while (peek().type() != TokenType.EOF && !atPunct("}")) {
            parseMember(typeScope);
        }
        if (atPunct("}")) {
            typeScope.setEndOffset(peek().endOffset());
            advance();
        } else {
            error(typeScope.startOffset(),
                    "unclosed type body opened at offset " + typeScope.startOffset());
            typeScope.setEndOffset(Math.min(text.length(), hardEnd));
        }
    }

    private void parseMember(ScopeNode typeScope) {
        boolean sawModifier = false;
        while (peek().type() == TokenType.KEYWORD && MODIFIERS.contains(peek().text())) {
            advance();
            sawModifier = true;
        }
        Token t = peek();
        if (t.type() == TokenType.EOF) {
            if (sawModifier) {
                error(t.startOffset(), "dangling modifiers");
            }
            return;
        }
        if (atPunct(";")) {
            advance();
            return;
        }
        if (atPunct("}")) {
            return;
        }
        if (atPunct("{")) {
            ScopeNode block = typeScope.addChild(t.startOffset(), false);
            int brace = t.startOffset();
            advance();
            block.setStartOffsetPublic(brace);
            parseStatements(block);
            closeBlock(block);
            return;
        }
        if (t.type() == TokenType.KEYWORD
                && (t.text().equals("class") || t.text().equals("interface")
                || t.text().equals("enum") || t.text().equals("record"))) {
            parseTypeDeclaration(typeScope);
            return;
        }
        if (!isTypeStart(t)) {
            error(t.startOffset(), "expected member declaration");
            skipToSemicolonOrBrace();
            return;
        }
        parseMemberDeclarator(typeScope);
    }

    private boolean isTypeStart(Token t) {
        if (t.type() == TokenType.IDENTIFIER) {
            return true;
        }
        return t.type() == TokenType.KEYWORD
                && (t.text().equals("var") || isPrimitive(t.text()));
    }

    private boolean isPrimitive(String s) {
        switch (s) {
            case "boolean", "byte", "char", "short", "int", "long",
                 "float", "double", "void", "String":
                return true;
            default:
                return false;
        }
    }

    private void parseMemberDeclarator(ScopeNode typeScope) {
        String typeName = parseTypeName();
        while (atPunct("[")) {
            advance();
            if (atPunct("]")) {
                advance();
            }
        }
        // Constructor: the type identifier is immediately followed by '('.
        if (atPunct("(")) {
            String detail = typeName + "(...)";
            int brace = peek().startOffset();
            ScopeNode methodScope = typeScope.addChild(brace, false);
            typeScope.addSymbol(new Symbol(typeName, SymbolKind.METHOD,
                    detail, brace, typeScope.startOffset()));
            parseParametersInto(methodScope);
            skipUntilToken("{");
            if (atPunct("{")) {
                int b = peek().startOffset();
                advance();
                methodScope.setStartOffsetPublic(b);
                parseStatements(methodScope);
                closeBlock(methodScope);
            } else if (atPunct(";")) {
                advance();
            }
            return;
        }
        Token nameToken = peek();
        if (nameToken.type() != TokenType.IDENTIFIER) {
            error(nameToken.startOffset(), "expected member name");
            skipToSemicolonOrBrace();
            return;
        }
        advance();
        if (atPunct("(")) {
            String detail = (typeName == null || typeName.isEmpty()
                    ? "" : typeName + " ") + nameToken.text() + "(...)";
            typeScope.addSymbol(new Symbol(nameToken.text(), SymbolKind.METHOD,
                    detail, nameToken.startOffset(), typeScope.startOffset()));
            int brace = peek().startOffset();
            ScopeNode methodScope = typeScope.addChild(brace, false);
            parseParametersInto(methodScope);
            skipUntilToken("{");
            if (atPunct("{")) {
                int b = peek().startOffset();
                advance();
                methodScope.setStartOffsetPublic(b);
                parseStatements(methodScope);
                closeBlock(methodScope);
            } else if (atPunct(";")) {
                advance();
            }
            return;
        }
        String detail = typeName.isEmpty() ? "field" : typeName;
        typeScope.addSymbol(new Symbol(nameToken.text(), SymbolKind.FIELD,
                detail, nameToken.startOffset(), typeScope.startOffset()));
        skipFieldRest(typeScope, detail);
    }

    /** Parses e.g. {@code java.util.List}, primitives, var, annotations. */
    private String parseTypeName() {
        StringBuilder sb = new StringBuilder();
        while (atPunct("@")) {
            advance();
            if (peek().type() == TokenType.IDENTIFIER) {
                advance();
            }
        }
        Token first = peek();
        if (first.type() == TokenType.IDENTIFIER
                || (first.type() == TokenType.KEYWORD
                && (isPrimitive(first.text()) || first.text().equals("var")))) {
            sb.append(advance().text());
            while (atPunct(".")) {
                advance();
                if (peek().type() == TokenType.IDENTIFIER) {
                    sb.append('.').append(advance().text());
                }
            }
            if (atPunct("<")) {
                sb.append(skipGenerics());
            }
        }
        return sb.toString();
    }

    private String skipGenerics() {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        do {
            Token t = peek();
            if (atPunct("<")) {
                depth++;
            } else if (atPunct(">")) {
                depth--;
            }
            sb.append(t.text());
            advance();
        } while (depth > 0 && peek().type() != TokenType.EOF);
        return sb.toString();
    }

    private void skipFieldRest(ScopeNode typeScope, String typeName) {
        while (true) {
            int depth = 0;
            while (peek().type() != TokenType.EOF) {
                if (depth == 0 && atPunct(";")) {
                    advance();
                    return;
                }
                if (depth == 0 && atPunct(",")) {
                    break;
                }
                if (atPunct("(") || atPunct("[")) {
                    depth++;
                } else if (atPunct(")") || atPunct("]")) {
                    depth--;
                }
                advance();
            }
            if (atPunct(",")) {
                advance();
                if (peek().type() == TokenType.IDENTIFIER) {
                    Token next = advance();
                    typeScope.addSymbol(new Symbol(next.text(), SymbolKind.FIELD,
                            typeName, next.startOffset(), typeScope.startOffset()));
                    continue;
                }
            } else {
                return;
            }
        }
    }

    /** Assumes '(' is next. Adds PARAMETER symbols and consumes to matching ')'. */
    private void parseParametersInto(ScopeNode methodScope) {
        advance(); // '('
        int depth = 1;
        while (peek().type() != TokenType.EOF && depth > 0) {
            Token t = peek();
            if (atPunct("(")) {
                depth++;
                advance();
                continue;
            }
            if (atPunct(")")) {
                depth--;
                advance();
                continue;
            }
            if (t.type() == TokenType.PUNCTUATION
                    && (t.text().equals(",") || t.text().equals("..."))) {
                advance();
                continue;
            }
            if (atPunct("@")) {
                advance();
                if (peek().type() == TokenType.IDENTIFIER) {
                    advance();
                }
                continue;
            }
            if (isTypeStart(t)) {
                String typeName = parseTypeName();
                while (atPunct("[")) {
                    advance();
                    if (atPunct("]")) {
                        advance();
                    }
                }
                Token pName = peek();
                if (pName.type() == TokenType.IDENTIFIER) {
                    advance();
                    methodScope.addSymbol(new Symbol(pName.text(), SymbolKind.PARAMETER,
                            typeName, pName.startOffset(), methodScope.startOffset()));
                }
                continue;
            }
            advance();
        }
    }

    private void skipUntilToken(String punct) {
        int depth = 0;
        while (peek().type() != TokenType.EOF) {
            if (atPunct(";") && depth == 0 && !punct.equals(";")) {
                advance();
                return;
            }
            if (atPunct(punct) && depth == 0) {
                return;
            }
            if (atPunct("(")) {
                depth++;
            } else if (atPunct(")")) {
                depth--;
            }
            advance();
        }
    }

    private void closeBlock(ScopeNode scope) {
        if (atPunct("}")) {
            scope.setEndOffset(peek().endOffset());
            advance();
        } else {
            error(scope.startOffset(),
                    "unclosed block opened at offset " + scope.startOffset());
            scope.setEndOffset(Math.min(text.length(), hardEnd));
        }
    }

    private void parseStatements(ScopeNode scope) {
        while (peek().type() != TokenType.EOF && !atPunct("}")) {
            parseStatement(scope);
        }
    }

    private void parseStatement(ScopeNode scope) {
        Token t = peek();
        if (t.type() == TokenType.PUNCTUATION && t.text().equals("{")) {
            ScopeNode block = scope.addChild(t.startOffset(), false);
            advance();
            parseStatements(block);
            closeBlock(block);
            return;
        }
        if (t.type() == TokenType.PUNCTUATION && t.text().equals(";")) {
            advance();
            return;
        }
        if (t.type() == TokenType.KEYWORD) {
            switch (t.text()) {
                case "if", "while", "for", "switch", "catch", "synchronized", "try":
                    parseControlStatement(scope);
                    return;
                case "return", "throw", "break", "continue", "yield":
                    skipToSemicolonOrBrace();
                    return;
                case "do":
                    advance();
                    parseStatement(scope);
                    return;
                case "else":
                    advance();
                    parseStatement(scope);
                    return;
                case "finally":
                    advance();
                    if (atPunct("{")) {
                        parseStatement(scope);
                    }
                    return;
                case "class", "interface", "enum", "record":
                    parseTypeDeclaration(scope);
                    return;
                default:
                    break;
            }
        }
        if (isTypeStart(t)) {
            parseLocalDeclaration(scope);
            return;
        }
        if (t.type() == TokenType.IDENTIFIER) {
            skipToSemicolonOrBrace();
            return;
        }
        error(t.startOffset(), "unexpected token '" + t.text() + "' in block");
        advance();
    }

    private void parseControlStatement(ScopeNode scope) {
        Token kw = advance(); // control keyword
        if (atPunct("(")) {
            ScopeNode headerScope = null;
            if (kw.text().equals("for")) {
                headerScope = scope.addChild(kw.endOffset(), false);
                headerScope.setStartOffsetPublic(kw.endOffset());
            }
            advance();
            int depth = 1;
            while (peek().type() != TokenType.EOF && depth > 0) {
                Token t = peek();
                if (atPunct("(")) {
                    depth++;
                    advance();
                    continue;
                }
                if (atPunct(")")) {
                    depth--;
                    if (depth == 0) {
                        if (headerScope != null) {
                            headerScope.setEndOffset(t.endOffset());
                        }
                        advance();
                        break;
                    }
                    advance();
                    continue;
                }
                if (headerScope != null && isTypeStart(t)) {
                    collectForVariables(headerScope);
                    continue;
                }
                advance();
            }
        }
        if (atPunct("{")) {
            parseStatement(scope);
        } else if (!atPunct(";") && !atKeyword("else") && !atKeyword("finally")
                && !atKeyword("catch") && peek().type() != TokenType.EOF
                && !atPunct("}")) {
            parseStatement(scope);
        } else if (atPunct(";")) {
            advance();
        }
        while (atKeyword("else") || atKeyword("catch") || atKeyword("finally")) {
            parseStatement(scope);
        }
    }

    /** Consumes one for-init variable declaration inside the header scope. */
    private void collectForVariables(ScopeNode headerScope) {
        String typeName = parseTypeName();
        while (true) {
            Token name = peek();
            if (name.type() != TokenType.IDENTIFIER) {
                break;
            }
            advance();
            headerScope.addSymbol(new Symbol(name.text(), SymbolKind.LOCAL_VARIABLE,
                    typeName, name.startOffset(), headerScope.startOffset()));
            int depth = 0;
            while (peek().type() != TokenType.EOF) {
                if (depth == 0 && (atPunct(",") || atPunct(";") || atPunct(":"))) {
                    break;
                }
                if (atPunct("(") || atPunct("[")) {
                    depth++;
                } else if (atPunct(")") || atPunct("]")) {
                    depth--;
                }
                advance();
            }
            if (atPunct(",")) {
                advance();
                continue;
            }
            break;
        }
    }

    private void parseLocalDeclaration(ScopeNode scope) {
        String typeName = parseTypeName();
        while (true) {
            while (atPunct("[")) {
                advance();
                if (atPunct("]")) {
                    advance();
                }
            }
            Token name = peek();
            if (name.type() != TokenType.IDENTIFIER) {
                error(name.startOffset(), "expected variable name");
                skipToSemicolonOrBrace();
                return;
            }
            advance();
            scope.addSymbol(new Symbol(name.text(), SymbolKind.LOCAL_VARIABLE,
                    typeName, name.startOffset(), scope.startOffset()));
            int depth = 0;
            while (peek().type() != TokenType.EOF) {
                if (depth == 0 && atPunct(";")) {
                    advance();
                    return;
                }
                if (depth == 0 && atPunct(",")) {
                    break;
                }
                if (atPunct("(") || atPunct("[")) {
                    depth++;
                } else if (atPunct(")") || atPunct("]")) {
                    depth--;
                }
                advance();
            }
            if (atPunct(",")) {
                advance();
            } else {
                return;
            }
        }
    }
}
