package com.github.shikejing.codecompletionservicejava.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.github.shikejing.codecompletionservicejava.analysis.Lexer.Token;
import com.github.shikejing.codecompletionservicejava.analysis.Lexer.TokenKind;
import com.github.shikejing.codecompletionservicejava.model.CompletionKind;

/**
 * Recovering parser for the Java subset needed by completion.
 *
 * <p>The parser is deliberately permissive rather than fully grammar-accurate:
 * its job is to produce a deterministic, best-effort scope tree and symbol
 * table used to rank completions.</p>
 *
 * <ul>
 *   <li>Every <code>{</code> opens a scope; every <code}</code> closes the
 *       innermost open scope and is recorded on the stack as a brace token.
 *       A stray <code>}</code> with no open scope is skipped and flagged.</li>
 *   <li>At EOF all still-open scopes are implicitly closed at the end of the
 *       text, so an unclosed block never loses declarations made before it.</li>
 *   <li>Class/interface/enum/record headers, method signatures, parameter
 *       lists, field and local variable declarations (including {@code var}
 *       and multi-declarator {@code int a, b;}) are recognized with small
 *       lookahead. Unrecognized tokens are consumed one at a time.</li>
 * </ul>
 */
@Component
public class FaultTolerantParser {

    private static final Set<String> MODIFIERS = Set.of(
            "public", "protected", "private", "static", "final", "abstract",
            "default", "synchronized", "native", "strictfp", "transient",
            "volatile", "sealed", "non-sealed");

    private static final Set<String> PRIMITIVE_TYPE_WORDS = Set.of(
            "boolean", "byte", "char", "short", "int", "long", "float",
            "double", "var", "void");

    private static final Set<String> TYPE_DECLARATION = Set.of(
            "class", "interface", "enum", "record");

    /** A scope currently pushed on the open-scope stack. */
    private static final class OpenScope {
        final Scope scope;
        /** Body kind: "type" (class body), "method" (method body) or "block". */
        final String kind;
        /** Owning type info when kind == "type". */
        final TypeInfo ownerType;
        /** Parameter scope to inherit when this is a method body. */
        final Scope parameterScope;

        OpenScope(Scope scope, String kind, TypeInfo ownerType, Scope parameterScope) {
            this.scope = scope;
            this.kind = kind;
            this.ownerType = ownerType;
            this.parameterScope = parameterScope;
        }
    }

    private List<Token> tokens;
    private List<TypeInfo> types;
    private boolean hasSyntaxErrors;
    private String text;

    public ParsedUnit parse(String source, long version) {
        this.text = source == null ? "" : source;
        this.tokens = new Lexer(this.text).tokenize();
        this.types = new ArrayList<>();
        this.hasSyntaxErrors = false;

        Scope root = new Scope(0, this.text.length(), null, "", 0);
        List<OpenScope> stack = new ArrayList<>();
        stack.add(new OpenScope(root, "root", null, null));

        int i = 0;
        while (tokens.get(i).kind() != TokenKind.EOF) {
            Token t = tokens.get(i);

            if (t.kind() == TokenKind.LBRACE) {
                OpenScope parent = stack.get(stack.size() - 1);
                Scope child = new Scope(t.start(), this.text.length(),
                        parent.scope, parent.scope.containerName(),
                        parent.scope.depth() + 1);
                parent.scope.addChild(child);
                String kind = "block";
                TypeInfo bodyType = null;
                Scope paramScope = null;
                PendingTypeBody pt = takePendingType(t.start());
                if (pt != null) {
                    bodyType = pt.info;
                    kind = "type";
                } else {
                    PendingMethod pm = takePendingMethod(t.start());
                    if (pm != null) {
                        paramScope = pm.parameterScope;
                        kind = "method";
                        child.setParameterOwner(paramScope);
                    }
                }
                stack.add(new OpenScope(child, kind, bodyType, paramScope));
                i++;
                continue;
            }
            if (t.kind() == TokenKind.RBRACE) {
                if (stack.size() == 1) {
                    hasSyntaxErrors = true; // stray '}'
                } else {
                    OpenScope closing = stack.remove(stack.size() - 1);
                    closing.scope.setEndOffset(t.end());
                }
                i++;
                continue;
            }

            OpenScope current = stack.get(stack.size() - 1);
            switch (current.kind) {
                case "root", "block", "method" -> i = parseStatementOrDecl(current, i, stack);
                case "type" -> i = parseTypeMember(current, i);
                default -> { i++; }
            }
        }

        // unclosed blocks: implicit close at EOF
        while (stack.size() > 1) {
            OpenScope closing = stack.remove(stack.size() - 1);
            closing.scope.setEndOffset(this.text.length());
            hasSyntaxErrors = true;
        }

        return new ParsedUnit(this.text, version, root, types,
                hasLexicalError() || hasSyntaxErrors, true);
    }

    // ------------------------------------------------------------------
    // Top-level / statement-level parsing
    // ------------------------------------------------------------------

    private int parseStatementOrDecl(OpenScope current, int idx, List<OpenScope> stack) {
        int start = skipModifiers(idx);
        Token t = tokens.get(start);

        if (isWord(t, TYPE_DECLARATION)) {
            return parseTypeHeader(current, start, stack, false);
        }

        // for (...) declaration: let the parenthesized variables be recorded
        if (t.text().equals("for") && tokens.get(start + 1).kind() == TokenKind.LPAREN) {
            parseForHeader(current.scope, start);
            // continue with normal scanning; the following '{' opens a block
            int j = skipToToken(start, TokenKind.RPAREN);
            return j + 1;
        }
        if (t.text().equals("catch") && tokens.get(start + 1).kind() == TokenKind.LPAREN) {
            parseCatchHeader(current.scope, start);
            int j = skipToToken(start, TokenKind.RPAREN);
            return j + 1;
        }
        if (t.text().equals("try") && tokens.get(start + 1).kind() == TokenKind.LPAREN) {
            parseForHeader(current.scope, start); // same parenthesized-decl grammar
            int j = skipToToken(start, TokenKind.RPAREN);
            return j + 1;
        }

        // local variable declaration: Type name (',' name)* ;
        if (isTypeStart(t)) {
            int typeEnd = skipTypeName(start);
            int p = typeEnd;
            if (tokens.get(p).kind() == TokenKind.IDENTIFIER) {
                String typeName = declaredTypeName(start, typeEnd);
                int consumed = consumeDeclarators(current.scope, p, typeName,
                        CompletionKind.LOCAL_VARIABLE, null);
                if (consumed >= 0) {
                    return consumed;
                }
            }
        }
        return recoverOne(idx);
    }

    /** Parses the parenthesized header of a for/try and records locals. */
    private void parseForHeader(Scope scope, int kwIdx) {
        int j = kwIdx + 1; // at '('
        int depth = 0;
        while (j < tokens.size() && tokens.get(j).kind() != TokenKind.EOF) {
            Token u = tokens.get(j);
            if (u.kind() == TokenKind.LPAREN) { depth++; j++; continue; }
            if (u.kind() == TokenKind.RPAREN) {
                depth--;
                if (depth == 0) { return; }
                j++; continue;
            }
            if (isTypeStart(u) && depth == 1) {
                int typeEnd = skipTypeName(j);
                if (tokens.get(typeEnd).kind() == TokenKind.IDENTIFIER) {
                    String tn = declaredTypeName(j, typeEnd);
                    consumeDeclarators(scope, typeEnd, tn, CompletionKind.LOCAL_VARIABLE, null);
                }
            }
            j++;
        }
    }

    private void parseCatchHeader(Scope scope, int kwIdx) {
        int j = kwIdx + 2; // '(' then type start
        if (j < tokens.size() && isTypeStart(tokens.get(j))) {
            int typeEnd = skipTypeName(j);
            if (tokens.get(typeEnd).kind() == TokenKind.IDENTIFIER) {
                Token name = tokens.get(typeEnd);
                scope.addSymbol(new Symbol(name.text(), CompletionKind.LOCAL_VARIABLE,
                        declaredTypeName(j, typeEnd), scope.containerName(),
                        name.start(), name.end(), name.line(), scope.depth()));
            }
        }
    }

    // ------------------------------------------------------------------
    // Type declarations and members
    // ------------------------------------------------------------------

    /**
     * Parses {@code [modifiers] class/interface/enum/record Name ... {body}}
     * and enqueues the upcoming brace so it opens a "type" scope.
     */
    private int parseTypeHeader(OpenScope current, int kwIdx, List<OpenScope> stack, boolean nested) {
        Token kw = tokens.get(kwIdx);
        Token nameTok = tokens.get(kwIdx + 1);
        TypeInfo info = new TypeInfo(nameTok.text(), kw.text());
        types.add(info);
        current.scope.addSymbol(new Symbol(nameTok.text(), CompletionKind.TYPE,
                nameTok.text(), current.scope.containerName(),
                nameTok.start(), nameTok.end(), nameTok.line(), current.scope.depth()));

        // extends/implements/permits: remember names up to '{' or ';'
        int j = kwIdx + 2;
        while (j < tokens.size() && tokens.get(j).kind() != TokenKind.EOF) {
            Token u = tokens.get(j);
            if (u.kind() == TokenKind.LBRACE) { break; }
            if (u.kind() == TokenKind.SEMICOLON) { j++; return j; }
            if (u.text().equals("extends") || u.text().equals("implements")
                    || u.text().equals("permits")) {
                j++;
                while (j < tokens.size() && tokens.get(j).kind() != TokenKind.LBRACE
                        && tokens.get(j).kind() != TokenKind.SEMICOLON
                        && tokens.get(j).kind() != TokenKind.EOF) {
                    Token cand = tokens.get(j);
                    if (cand.kind() == TokenKind.IDENTIFIER) {
                        info.addSuperType(cand.text());
                    }
                    j++;
                }
                continue;
            }
            j++;
        }
        if (tokens.get(j).kind() == TokenKind.LBRACE) {
            pendingTypeBodies.add(new PendingTypeBody(tokens.get(j).start(), info));
            return j; // main loop consumes the brace as a "type" scope
        }
        return j;
    }

    /** Pending "type body" association tied to a specific brace offset. */
    private static final class PendingTypeBody {
        final int braceOffset;
        final TypeInfo info;
        PendingTypeBody(int braceOffset, TypeInfo info) {
            this.braceOffset = braceOffset;
            this.info = info;
        }
    }
    private final List<PendingTypeBody> pendingTypeBodies = new ArrayList<>();

    private PendingTypeBody takePendingType(int braceOffset) {
        for (int q = 0; q < pendingTypeBodies.size(); q++) {
            if (pendingTypeBodies.get(q).braceOffset == braceOffset) {
                return pendingTypeBodies.remove(q);
            }
        }
        return null;
    }

    /** Method headers whose following '{' opens a method body with params. */
    private static final class PendingMethod {
        final int braceOffset;
        final Scope parameterScope;
        PendingMethod(int braceOffset, Scope parameterScope) {
            this.braceOffset = braceOffset;
            this.parameterScope = parameterScope;
        }
    }
    private final List<PendingMethod> pendingMethodBodies = new ArrayList<>();

    private PendingMethod takePendingMethod(int braceOffset) {
        for (int q = 0; q < pendingMethodBodies.size(); q++) {
            if (pendingMethodBodies.get(q).braceOffset == braceOffset) {
                return pendingMethodBodies.remove(q);
            }
        }
        return null;
    }

    private int parseTypeMember(OpenScope current, int idx) {
        int start = skipModifiers(idx);
        Token t = tokens.get(start);

        if (isWord(t, TYPE_DECLARATION)) {
            return parseTypeHeader(current, start, null, true);
        }
        if (!isTypeStart(t)) {
            return recoverOne(idx);
        }
        int typeEnd = skipTypeName(start);
        int nameIdx = typeEnd;
        if (tokens.get(nameIdx).kind() != TokenKind.IDENTIFIER) {
            return recoverOne(idx);
        }
        Token nameTok = tokens.get(nameIdx);
        String typeName = declaredTypeName(start, typeEnd);
        TypeInfo owner = current.ownerType;

        int afterName = skipArrayMarkers(nameIdx + 1);
        if (tokens.get(afterName).kind() == TokenKind.LPAREN) {
            return parseMethodHeader(current, nameTok, typeName, afterName);
        }
        // field declaration(s)
        return consumeDeclarators(current.scope, nameIdx, typeName,
                CompletionKind.MEMBER_FIELD, owner);
    }

    private int parseMethodHeader(OpenScope current, Token nameTok, String returnType, int parenIdx) {
        TypeInfo owner = current.ownerType;
        Symbol method = new Symbol(nameTok.text(), CompletionKind.METHOD,
                returnType, owner != null ? owner.simpleName() : "",
                nameTok.start(), nameTok.end(), nameTok.line(), current.scope.depth());
        current.scope.addSymbol(method);
        if (owner != null) {
            owner.addMember(method);
        }

        // parameter scope is a virtual child of the type body scope; the
        // upcoming method body scope will inherit its symbols.
        Scope paramScope = new Scope(parenIdx, parenIdx + 1, current.scope,
                nameTok.text(), current.scope.depth() + 1);
        current.scope.addChild(paramScope);
        int j = parenIdx + 1;
        int depth = 1;
        boolean expectType = true;
        String pendingType = "var";
        while (j < tokens.size() && depth > 0) {
            Token u = tokens.get(j);
            if (u.kind() == TokenKind.LPAREN) { depth++; j++; continue; }
            if (u.kind() == TokenKind.RPAREN) {
                depth--;
                j++;
                if (depth == 0) { break; }
                continue;
            }
            if (u.kind() == TokenKind.COMMA) { expectType = true; j++; continue; }
            if (isWord(u, MODIFIERS)) { j++; continue; }
            if (expectType && isTypeStart(u)) {
                int te = skipTypeName(j);
                pendingType = declaredTypeName(j, te);
                j = te;
                expectType = false;
                continue;
            }
            if (!expectType && u.kind() == TokenKind.IDENTIFIER) {
                paramScope.addSymbol(new Symbol(u.text(), CompletionKind.METHOD_PARAMETER,
                        pendingType, nameTok.text(), u.start(), u.end(), u.line(),
                        paramScope.depth()));
                expectType = true;
                j++;
                continue;
            }
            j++;
        }
        paramScope.setEndOffset(j);

        // skip throws clause / default value up to body brace or ';'
        while (j < tokens.size() && tokens.get(j).kind() != TokenKind.LBRACE
                && tokens.get(j).kind() != TokenKind.SEMICOLON
                && tokens.get(j).kind() != TokenKind.EOF) {
            j++;
        }
        if (tokens.get(j).kind() == TokenKind.LBRACE) {
            pendingMethodBodies.add(new PendingMethod(tokens.get(j).start(), paramScope));
            return j;
        }
        return tokens.get(j).kind() == TokenKind.SEMICOLON ? j + 1 : j;
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    /**
     * Consumes {@code name ('=' initializer)? (',' name ...)* (';'|'{'|EOF)}
     * starting at the first name token. Returns index after the declaration,
     * or -1 when the token shape is not a declarator list.
     */
    private int consumeDeclarators(Scope scope, int nameIdx, String typeName,
                                   CompletionKind kind, TypeInfo owner) {
        int p = nameIdx;
        boolean saw = false;
        while (tokens.get(p).kind() == TokenKind.IDENTIFIER) {
            Token nameTok = tokens.get(p);
            Symbol sym = new Symbol(nameTok.text(), kind, typeName,
                    owner != null ? owner.simpleName() : scope.containerName(),
                    nameTok.start(), nameTok.end(), nameTok.line(), scope.depth());
            scope.addSymbol(sym);
            if (owner != null && kind == CompletionKind.MEMBER_FIELD) {
                owner.addMember(sym);
            }
            saw = true;
            p++;
            p = skipArrayMarkers(p);
            if (tokens.get(p).kind() == TokenKind.ASSIGN) {
                p = skipInitializer(p + 1);
            }
            if (tokens.get(p).kind() == TokenKind.COMMA) {
                p++;
                continue;
            }
            break;
        }
        if (!saw) {
            return -1;
        }
        Token stop = tokens.get(p);
        if (stop.kind() == TokenKind.SEMICOLON) {
            return p + 1;
        }
        if (stop.kind() == TokenKind.LBRACE || stop.kind() == TokenKind.EOF) {
            return p;
        }
        // malformed: recover to semicolon/brace without dropping symbols
        int q = p;
        int d = 0;
        while (q < tokens.size()) {
            TokenKind kk = tokens.get(q).kind();
            if (kk == TokenKind.LPAREN || kk == TokenKind.LBRACE) d++;
            if (kk == TokenKind.RPAREN || kk == TokenKind.RBRACE) d--;
            if (d <= 0 && (kk == TokenKind.SEMICOLON || kk == TokenKind.LBRACE
                    || kk == TokenKind.RBRACE || kk == TokenKind.EOF)) {
                break;
            }
            q++;
        }
        return tokens.get(q).kind() == TokenKind.SEMICOLON ? q + 1 : q;
    }

    private int skipInitializer(int p) {
        int depth = 0;
        while (p < tokens.size()) {
            Token u = tokens.get(p);
            TokenKind kk = u.kind();
            if (kk == TokenKind.LPAREN || kk == TokenKind.LBRACKET) depth++;
            if (kk == TokenKind.RPAREN || kk == TokenKind.RBRACKET) depth--;
            if (depth <= 0 && (kk == TokenKind.COMMA || kk == TokenKind.SEMICOLON
                    || kk == TokenKind.LBRACE || kk == TokenKind.RBRACE
                    || kk == TokenKind.EOF)) {
                return p;
            }
            p++;
        }
        return p;
    }

    private int skipArrayMarkers(int p) {
        while (tokens.get(p).kind() == TokenKind.LBRACKET
                || tokens.get(p).kind() == TokenKind.RBRACKET) {
            p++;
        }
        return p;
    }

    private int skipToToken(int from, TokenKind kind) {
        int j = from;
        while (j < tokens.size() && tokens.get(j).kind() != TokenKind.EOF) {
            if (tokens.get(j).kind() == kind) {
                return j;
            }
            j++;
        }
        return j;
    }

    private int recoverOne(int idx) {
        Token t = tokens.get(idx);
        if (t.kind() == TokenKind.BAD) {
            hasSyntaxErrors = true;
        }
        return idx + 1;
    }

    private int skipModifiers(int idx) {
        int j = idx;
        boolean changed = true;
        while (changed && j < tokens.size()) {
            changed = false;
            while (j < tokens.size() && isWord(tokens.get(j), MODIFIERS)) {
                j++;
                changed = true;
            }
            if (j < tokens.size() && tokens.get(j).kind() == TokenKind.OPERATOR
                    && tokens.get(j).text().equals("@")) {
                j++;
                while (j < tokens.size() && (tokens.get(j).kind() == TokenKind.IDENTIFIER
                        || tokens.get(j).kind() == TokenKind.DOT)) {
                    j++;
                }
                if (tokens.get(j).kind() == TokenKind.LPAREN) {
                    int d = 0;
                    do {
                        if (tokens.get(j).kind() == TokenKind.LPAREN) d++;
                        if (tokens.get(j).kind() == TokenKind.RPAREN) d--;
                        j++;
                    } while (j < tokens.size() && d > 0
                            && tokens.get(j).kind() != TokenKind.EOF);
                }
                changed = true;
            }
        }
        return j;
    }

    private boolean isWord(Token t, Set<String> words) {
        return t.kind() == TokenKind.KEYWORD && words.contains(t.text());
    }

    private boolean isTypeStart(Token t) {
        if (t.kind() == TokenKind.IDENTIFIER) {
            return true;
        }
        return isWord(t, PRIMITIVE_TYPE_WORDS);
    }

    private int skipTypeName(int idx) {
        int j = idx;
        while (true) {
            if (!isTypeStart(tokens.get(j))) {
                break;
            }
            j++;
            if (tokens.get(j).kind() == TokenKind.DOT
                    && isTypeStart(tokens.get(j + 1))) {
                j += 2;
                continue;
            }
            break;
        }
        if (tokens.get(j).kind() == TokenKind.OPERATOR && tokens.get(j).text().equals("<")) {
            int depth = 0;
            while (j < tokens.size()) {
                Token u = tokens.get(j);
                if (u.kind() == TokenKind.OPERATOR && u.text().equals("<")) depth++;
                else if (u.kind() == TokenKind.OPERATOR && u.text().equals(">")) {
                    depth--;
                    j++;
                    if (depth <= 0) break;
                    continue;
                } else if (u.kind() == TokenKind.EOF) {
                    break;
                }
                j++;
            }
        }
        while (tokens.get(j).kind() == TokenKind.LBRACKET) {
            j++;
            if (tokens.get(j).kind() == TokenKind.RBRACKET) {
                j++;
            }
        }
        return j;
    }

    private String declaredTypeName(int from, int to) {
        String last = "var";
        for (int q = from; q < to; q++) {
            Token u = tokens.get(q);
            if (u.kind() == TokenKind.IDENTIFIER
                    || isWord(u, PRIMITIVE_TYPE_WORDS)) {
                last = u.text();
            }
        }
        return last;
    }

    private boolean hasLexicalError() {
        for (Token t : tokens) {
            if (t.kind() == TokenKind.BAD) {
                return true;
            }
        }
        return false;
    }
}
