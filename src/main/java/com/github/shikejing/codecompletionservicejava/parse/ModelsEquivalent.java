package com.github.shikejing.codecompletionservicejava.parse;

import java.util.ArrayList;
import java.util.List;
import com.github.shikejing.codecompletionservicejava.analysis.ScopeNode;
import com.github.shikejing.codecompletionservicejava.analysis.Symbol;

/**
 * Structural comparison of syntax models, independent of the
 * incremental/full flag. Two models are equivalent when every scope region,
 * scope kind, declared symbol (name/kind/detail/offset) and token stream is
 * identical. Completion output depends only on this normalized structure.
 */
public final class ModelsEquivalent {

    private ModelsEquivalent() {
    }

    public static boolean equivalent(SyntaxModel a, SyntaxModel b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (!a.text().equals(b.text()) || a.version() != b.version()) {
            return false;
        }
        if (!tokensEqual(a.tokens(), b.tokens())) {
            return false;
        }
        return scopesEqual(a.rootScope(), b.rootScope());
    }

    /** Sanity check that spliced tokens are ordered, non-overlapping offsets. */
    public static boolean tokensEqual(List<Token> tokens) {
        for (int i = 1; i < tokens.size(); i++) {
            Token prev = tokens.get(i - 1);
            Token cur = tokens.get(i);
            if (cur.startOffset() < prev.endOffset()) {
                return false;
            }
        }
        return true;
    }

    public static boolean tokensEqual(List<Token> a, List<Token> b) {        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            Token x = a.get(i);
            Token y = b.get(i);
            if (x.type() != y.type() || x.startOffset() != y.startOffset()
                    || x.endOffset() != y.endOffset()) {
                return false;
            }
        }
        return true;
    }

    private static boolean scopesEqual(ScopeNode a, ScopeNode b) {
        if (a.startOffset() != b.startOffset() || a.endOffset() != b.endOffset()
                || a.typeScope() != b.typeScope()) {
            return false;
        }
        List<Symbol> sa = new ArrayList<>(a.symbols());
        List<Symbol> sb = new ArrayList<>(b.symbols());
        if (sa.size() != sb.size()) {
            return false;
        }
        for (int i = 0; i < sa.size(); i++) {
            Symbol x = sa.get(i);
            Symbol y = sb.get(i);
            if (!x.name().equals(y.name()) || x.kind() != y.kind()
                    || x.declaredOffset() != y.declaredOffset()
                    || !safe(x.detail()).equals(safe(y.detail()))) {
                return false;
            }
        }
        List<ScopeNode> ca = a.children();
        List<ScopeNode> cb = b.children();
        if (ca.size() != cb.size()) {
            return false;
        }
        for (int i = 0; i < ca.size(); i++) {
            if (!scopesEqual(ca.get(i), cb.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
