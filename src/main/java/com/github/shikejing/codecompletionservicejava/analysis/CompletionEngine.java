package com.github.shikejing.codecompletionservicejava.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.github.shikejing.codecompletionservicejava.config.CompletionProperties;
import com.github.shikejing.codecompletionservicejava.model.CompletionItem;
import com.github.shikejing.codecompletionservicejava.model.Position;
import com.github.shikejing.codecompletionservicejava.model.SymbolKind;
import com.github.shikejing.codecompletionservicejava.parse.SyntaxModel;
import com.github.shikejing.codecompletionservicejava.parse.Token;
import com.github.shikejing.codecompletionservicejava.parse.TokenType;

/**
 * Pure completion computation over an immutable syntax model.
 *
 * Semantics:
 *  - the prefix is the identifier fragment directly left of the cursor;
 *  - after a {@code qualifier.} only the qualifier's accessible members are
 *    candidates (never the whole-file symbol table);
 *  - otherwise candidates are the symbols visible at the innermost scope with
 *    shadowing applied, plus context-appropriate keywords;
 *  - candidates must start with the prefix (case sensitive), are de-duplicated
 *    by name keeping the strongest (innermost/kind) source, and sorted by a
 *    documented score with a fully deterministic lexicographic tie breaker;
 *  - malformed or unclosed text still yields a definite result because the
 *    parser recovered to EOF.
 */
public final class CompletionEngine {

    /** Keywords sensible at an ordinary expression/statement position. */
    private static final List<String> STATEMENT_KEYWORDS = List.of(
            "if", "for", "while", "return", "new", "int", "long", "double",
            "boolean", "char", "byte", "short", "float", "void", "var",
            "class", "switch", "case", "break", "continue", "else", "do",
            "try", "catch", "finally", "throw", "throws", "this", "super",
            "true", "false", "null", "instanceof", "import", "package");

    private static final List<String> MEMBER_KEYWORDS = List.of("this", "super");

    private final CompletionProperties props;

    public CompletionEngine(CompletionProperties properties) {
        this.props = properties;
    }

    public List<CompletionItem> complete(SyntaxModel model, Position position,
                                         RecencyTracker recency) {
        String text = model.text();
        int cursor = Math.max(0, Math.min(model.lineIndex().offset(position),
                text.length()));

        String prefix = prefixAt(text, cursor);
        int nameStart = cursor - prefix.length();

        ScopeNode innermost = model.rootScope().innermost(cursor);
        MemberAccess member = detectMemberAccess(model.tokens(), text, nameStart);

        String expectedType = inferExpectedType(text, cursor);
        Map<String, CandidateBuilder> candidates = new LinkedHashMap<>();

        if (member != null) {
            addMemberCandidates(candidates, innermost, member, prefix, cursor,
                    expectedType, recency);
        } else {
            addVisibleSymbols(candidates, innermost, cursor, prefix,
                    expectedType, recency);
            addKeywords(candidates, prefix, innermost, cursor, false);
        }

        List<CompletionItem> items = new ArrayList<>();
        for (CandidateBuilder b : candidates.values()) {
            items.add(b.build(props, recency));
        }
        items.sort(finalComparator());
        return List.copyOf(items);
    }

    /**
     * Collect the names declared inside the replaced span of the previous
     * model, so the service can record them as recently edited. The post-edit
     * model is what completion uses; this only needs the pre-edit declarations
     * that the edit may have touched (plus the identifier fragment inserted).
     */
    public List<String> namesTouchedByEdit(SyntaxModel before, int replacedStart,
                                           int replacedEnd, String insertedText) {
        List<String> names = new ArrayList<>();
        collectDeclared(before.rootScope(), replacedStart, replacedEnd, names);
        if (insertedText != null) {
            int i = 0;
            while (i < insertedText.length()) {
                char c = insertedText.charAt(i);
                if (Character.isJavaIdentifierStart(c)) {
                    int s = i;
                    i++;
                    while (i < insertedText.length()
                            && Character.isJavaIdentifierPart(insertedText.charAt(i))) {
                        i++;
                    }
                    String word = insertedText.substring(s, i);
                    if (!names.contains(word)) {
                        names.add(word);
                    }
                } else {
                    i++;
                }
            }
        }
        return List.copyOf(names);
    }

    private void collectDeclared(ScopeNode node, int start, int end, List<String> out) {
        for (Symbol symbol : node.symbols()) {
            if (symbol.declaredOffset() >= start && symbol.declaredOffset() <= end
                    && !out.contains(symbol.name())) {
                out.add(symbol.name());
            }
        }
        for (ScopeNode child : node.children()) {
            if (child.endOffset() >= start && child.startOffset() <= end) {
                collectDeclared(child, start, end, out);
            }
        }
    }

    // ------------------------------------------------------------------
    // Prefix / member access detection
    // ------------------------------------------------------------------

    /** Identifier fragment ending at (but not including) cursor. */
    static String prefixAt(String text, int cursor) {
        int i = cursor;
        while (i > 0 && Character.isJavaIdentifierPart(text.charAt(i - 1))) {
            i--;
        }
        // must actually begin an identifier, not follow a part-char run start
        if (i < cursor && i > 0 && Character.isJavaIdentifierPart(text.charAt(i - 1))) {
            // already consumed maximally; loop did the right thing
        }
        return text.substring(i, cursor);
    }

    record MemberAccess(String qualifier, int dotOffset) {
    }

    /**
     * Detect a {@code name.} immediately before the prefix position. The
     * qualifier is resolved to the visible symbol of that name; member
     * completion is offered only if it resolves, otherwise ordinary
     * completion applies (typing after a dot on an unknown name yields
     * methods/fields named by any known same-typed symbol is not possible, so
     * we fall back gracefully).
     */
    private MemberAccess detectMemberAccess(List<Token> tokens, String text,
                                            int nameStart) {
        // nameStart is the prefix token's start offset; walk tokens to the
        // token immediately preceding it, which must be a '.' (the prefix
        // itself may still be an uncommitted partial identifier not in tokens).
        int dotIndex = -1;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.type() == TokenType.PUNCTUATION && t.text().equals(".")
                    && t.endOffset() == nameStart) {
                dotIndex = i;
                break;
            }
            if (t.startOffset() > nameStart) {
                break;
            }
        }
        if (dotIndex <= 0) {
            return null;
        }
        Token qual = tokens.get(dotIndex - 1);
        if (qual.type() != TokenType.IDENTIFIER || qual.endOffset() != nameStart - 1) {
            return null;
        }
        return new MemberAccess(qual.text(), tokens.get(dotIndex).startOffset());
    }

    private int tokenIndexEndingAt(List<Token> tokens, int offset) {
        // tokens are sorted; binary-search-like linear from end near cursor
        for (int i = tokens.size() - 1; i >= 0; i--) {
            if (tokens.get(i).endOffset() == offset) {
                return i;
            }
            if (tokens.get(i).endOffset() < offset) {
                return i + 1;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Candidate collection
    // ------------------------------------------------------------------

    private void addVisibleSymbols(Map<String, CandidateBuilder> candidates,
                                   ScopeNode innermost, int cursor, String prefix,
                                   String expectedType, RecencyTracker recency) {
        List<Symbol> visible = innermost.visibleSymbols(cursor);
        // visibleSymbols returns inner-to-outer with shadowing already applied;
        // iterate outer-to-inner for map insertion so innermost wins on merge.
        for (int i = visible.size() - 1; i >= 0; i--) {
            Symbol symbol = visible.get(i);
            if (!matchesPrefix(symbol.name(), prefix)) {
                continue;
            }
            putOrStrengthen(candidates, symbol, innermost, cursor, expectedType);
        }
    }

    private void addMemberCandidates(Map<String, CandidateBuilder> candidates,
                                     ScopeNode innermost, MemberAccess member,
                                     String prefix, int cursor, String expectedType,
                                     RecencyTracker recency) {
        Symbol qualifier = null;
        for (Symbol s : innermost.visibleSymbols(cursor)) {
            if (s.name().equals(member.qualifier())) {
                qualifier = s;
                break;
            }
        }
        // Find the type name of the qualifier and offer that type's members.
        String typeName = qualifier != null ? typeOf(qualifier) : null;
        ScopeNode typeBody = findTypeBody(innermost, typeName);
        if (typeBody != null) {
            for (Symbol s : typeBody.visibleSymbols(cursor)) {
                if ((s.kind() == SymbolKind.FIELD || s.kind() == SymbolKind.METHOD)
                        && matchesPrefix(s.name(), prefix)) {
                    putOrStrengthen(candidates, s, typeBody, cursor, typeName);
                }
            }
        }
        addKeywords(candidates, prefix, innermost, cursor, true);
    }

    private ScopeNode findTypeBody(ScopeNode scope, String typeName) {
        if (typeName == null) {
            return null;
        }
        for (ScopeNode root = scope; root != null; root = root.parent()) {
            ScopeNode found = findTypeBodyDown(root, typeName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private ScopeNode findTypeBodyDown(ScopeNode node, String typeName) {
        // A TYPE symbol's declaredOffset is its name position; its body scope
        // is the unique typeScope child that begins after that name.
        for (Symbol s : node.symbols()) {
            if (s.kind() == SymbolKind.TYPE && simpleName(s.name()).equals(typeName)) {
                ScopeNode best = null;
                for (ScopeNode child : node.children()) {
                    if (child.typeScope()
                            && child.startOffset() >= s.declaredOffset()
                            && (best == null
                            || child.startOffset() < best.startOffset())) {
                        best = child;
                    }
                }
                if (best != null) {
                    return best;
                }
            }
        }
        for (ScopeNode child : node.children()) {
            ScopeNode found = findTypeBodyDown(child, typeName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private String simpleName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }

    private void addKeywords(Map<String, CandidateBuilder> candidates, String prefix,
                             ScopeNode innermost, int cursor, boolean member) {
        List<String> words = member ? MEMBER_KEYWORDS : STATEMENT_KEYWORDS;
        for (String kw : words) {
            if (!matchesPrefix(kw, prefix) || candidates.containsKey(kw)) {
                continue;
            }
            Symbol sym = new Symbol(kw, SymbolKind.KEYWORD, "keyword", -1, -1);
            candidates.put(kw, new CandidateBuilder(sym, 0, "keyword", 0));
        }
    }

    private boolean matchesPrefix(String name, String prefix) {
        return name.startsWith(prefix);
    }

    private void putOrStrengthen(Map<String, CandidateBuilder> candidates, Symbol symbol,
                                 ScopeNode scope, int cursor, String expectedType) {
        int proximity = proximityLevels(symbol, scope);
        String source = sourceOf(symbol);
        CandidateBuilder existing = candidates.get(symbol.name());
        if (existing == null) {
            candidates.put(symbol.name(),
                    new CandidateBuilder(symbol, proximity, source,
                            typeScore(symbol, expectedType)));
        } else {
            // Dedup by name, preserving provenance of every declaration.
            existing.mergeSource(source);
            boolean closer = proximity < existing.proximity;
            boolean sameLevelStronger = proximity == existing.proximity
                    && CandidateBuilder.kindRank(symbol.kind()) < existing.kindRank;
            if (closer || sameLevelStronger) {
                candidates.put(symbol.name(),
                        new CandidateBuilder(symbol, proximity, existing.joinedSources(),
                                typeScore(symbol, expectedType)));
            }
        }
    }

    /**
     * Number of scope boundaries between the cursor scope and the scope that
     * declares the symbol: 0 when the symbol is declared directly in the
     * cursor scope, 1 in the immediate enclosing scope, etc. Declaration scope
     * is identified structurally (the ancestor whose symbol table contains
     * the symbol), not by cached start offsets, so incremental and full models
     * agree exactly.
     */
    private int proximityLevels(Symbol symbol, ScopeNode cursorScope) {
        int levels = 0;
        for (ScopeNode n = cursorScope; n != null; n = n.parent(), levels++) {
            if (n.symbols().contains(symbol)) {
                return levels;
            }
        }
        return levels;
    }

    private String sourceOf(Symbol symbol) {
        return symbol.kind().name().toLowerCase()
                + (symbol.declaredOffset() >= 0 ? "@" + symbol.declaredOffset() : "");
    }

    private double typeScore(Symbol symbol, String expectedType) {
        if (expectedType == null || expectedType.isBlank()) {
            return 0;
        }
        return simpleName(typeOf(symbol)).equals(simpleName(expectedType)) ? 1 : 0;
    }

    private String typeOf(Symbol symbol) {
        return symbol.detail() == null ? "" : symbol.detail();
    }

    // ------------------------------------------------------------------
    // Expected type inference (local, conservative)
    // ------------------------------------------------------------------

    /**
     * Infer the expected type immediately left of the cursor for the common
     * forms {@code Type x = |}, {@code return |} (method return type), and
     * argument position {@code foo(|)} (parameter type). Unknown -> null.
     */
    private String inferExpectedType(String text, int cursor) {
        // assignment: backtrack over '=' at statement start on the same line
        int lineStart = cursor;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        String line = text.substring(lineStart, cursor);
        int eq = line.lastIndexOf('=');
        if (eq >= 0 && !line.contains("==")) {
            String lhs = line.substring(0, eq).trim();
            String type = declaredTypeOfLhs(lhs);
            if (type != null) {
                return type;
            }
        }
        if (line.trim().equals("return") || line.trim().startsWith("return ")) {
            return enclosingMethodReturnType(text, lineStart);
        }
        return null;
    }

    private String declaredTypeOfLhs(String lhs) {
        // lhs looks like "Type name" optionally with modifiers; last token is
        // the variable name, first token the declared type.
        String[] parts = lhs.split("\\s+");
        if (parts.length >= 2) {
            String maybeType = parts[parts.length - 2];
            if (maybeType.matches("[A-Za-z][A-Za-z0-9_.<>]*")) {
                return simpleName(maybeType);
            }
        }
        return null;
    }

    private String enclosingMethodReturnType(String text, int offset) {
        // best-effort: find nearest "Type name(" before offset, return Type
        int best = -1;
        int idx = 0;
        while (true) {
            int p = text.indexOf('(', idx);
            if (p < 0 || p > offset) {
                break;
            }
            best = p;
            idx = p + 1;
        }
        if (best < 0) {
            return null;
        }
        int j = best - 1;
        while (j >= 0 && Character.isJavaIdentifierPart(text.charAt(j))) {
            j--;
        }
        int end = j;
        while (j >= 0 && Character.isWhitespace(text.charAt(j))) {
            j--;
        }
        int tEnd = j;
        while (j >= 0 && (Character.isJavaIdentifierPart(text.charAt(j))
                || text.charAt(j) == '.')) {
            j--;
        }
        if (tEnd > j) {
            return simpleName(text.substring(j + 1, tEnd + 1));
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Scoring
    // ------------------------------------------------------------------

    private Comparator<CompletionItem> finalComparator() {
        return Comparator
                .comparingDouble(CompletionItem::score).reversed()
                .thenComparing(CompletionItem::sortText)
                .thenComparing(CompletionItem::label);
    }

    /** Mutable accumulator used while collecting a de-duplicated candidate. */
    static final class CandidateBuilder {
        private final Symbol symbol;
        final int proximity;
        private final List<String> sources = new ArrayList<>();
        private final double typeMatchFlag;
        int kindRank;

        CandidateBuilder(Symbol symbol, int proximity, String source,
                         double typeMatchFlag) {
            this.symbol = symbol;
            this.proximity = proximity;
            this.sources.add(source);
            this.typeMatchFlag = typeMatchFlag;
            this.kindRank = kindRank(symbol.kind());
        }

        String joinedSources() {
            return String.join(",", new ArrayList<>(new java.util.LinkedHashSet<>(sources)));
        }

        void mergeSource(String source) {
            if (!sources.contains(source)) {
                sources.add(source);
            }
        }

        static int kindRank(SymbolKind kind) {
            return switch (kind) {
                case LOCAL_VARIABLE -> 0;
                case PARAMETER -> 1;
                case FIELD -> 2;
                case METHOD -> 3;
                case TYPE -> 4;
                case KEYWORD -> 5;
            };
        }

        double base(CompletionProperties p) {
            return switch (symbol.kind()) {
                case LOCAL_VARIABLE -> p.getLocalVariableBase();
                case PARAMETER -> p.getParameterBase();
                case FIELD -> p.getFieldBase();
                case METHOD -> p.getMethodBase();
                case TYPE -> p.getTypeBase();
                case KEYWORD -> p.getKeywordBase();
            };
        }

        CompletionItem build(CompletionProperties p, RecencyTracker recency) {
            double score = base(p);
            double proximityBonus = p.getProximityWeight() * proximity;
            double typeBonus = typeMatchFlag * p.getTypeMatchWeight();
            int rank = recency == null ? -1 : recency.rankOf(symbol.name());
            double recencyBonus = rank < 0 ? 0
                    : p.getRecencyWeight() * (1.0 - (double) rank
                            / Math.max(1, p.getRecencyWindowEdits()));
            double total = score + proximityBonus + typeBonus + recencyBonus;
            String reason = String.format(
                    "base=%.2f+prox=%.2f+type=%.2f+recent=%.2f",
                    score, proximityBonus, typeBonus, recencyBonus);
            return new CompletionItem(symbol.name(),
                    symbol.detail() == null ? "" : symbol.detail(),
                    symbol.kind(), joinedSources(), round(total),
                    sortKey(), reason);
        }

        private static double round(double v) {
            return Math.round(v * 10000.0d) / 10000.0d;
        }

        private String sortKey() {
            return String.format("%d|%s", kindRank(symbol.kind()), symbol.name());
        }
    }
}
