package com.github.shikejing.codecompletionservicejava.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A lexical scope (file / type body / method body / plain block) with
 * half-open absolute offsets. Scopes nest; unclosed blocks are recovered by
 * closing them at EOF so completion always has a well-defined innermost scope.
 *
 * The class is mutable while the parser builds it (symbols/children/end are
 * filled in) but query methods only read the resulting structure.
 */
public final class ScopeNode {

    private int startOffset;
    private int endOffset;
    private final ScopeNode parent;
    private final boolean typeScope;
    private final List<Symbol> symbols = new ArrayList<>();
    private final List<ScopeNode> children = new ArrayList<>();

    public ScopeNode(int startOffset, int endOffset, ScopeNode parent, boolean typeScope) {
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.parent = parent;
        this.typeScope = typeScope;
    }

    public int startOffset() {
        return startOffset;
    }

    public int endOffset() {
        return endOffset;
    }

    public ScopeNode parent() {
        return parent;
    }

    public boolean typeScope() {
        return typeScope;
    }

    public List<Symbol> symbols() {
        return List.copyOf(symbols);
    }

    public List<ScopeNode> children() {
        return List.copyOf(children);
    }

    public void setEndOffset(int endOffset) {
        this.endOffset = endOffset;
    }

    /** Update where the scope physically starts (normally its opening brace). */
    public void setStartOffsetPublic(int startOffset) {
        this.startOffset = startOffset;
    }

    public void addSymbol(Symbol symbol) {
        symbols.add(symbol);
    }

    public ScopeNode addChild(int start, boolean typeScope) {
        ScopeNode child = new ScopeNode(start, endOffset, this, typeScope);
        children.add(child);
        return child;
    }

    /** Index of a direct child, or -1 if not present. */
    public int indexOfChild(ScopeNode child) {
        return children.indexOf(child);
    }

    /**
     * Atomically replace a direct child with a freshly parsed replacement
     * (used by incremental re-analysis of the minimal affected scope).
     */
    public void replaceChild(int index, ScopeNode replacement) {
        children.set(index, replacement);
    }

    /** Create a child pre-wired to this parent without registering it. */
    public ScopeNode newChildLike(int start, boolean typeScope) {
        return new ScopeNode(start, endOffset, this, typeScope);
    }

    /** Register an externally built child (already wired to this parent). */
    public void adoptChild(ScopeNode child) {
        children.add(child);
    }

    /**
     * Innermost scope containing the offset. An offset equal to a scope's
     * start belongs to that scope; an offset equal to its end belongs to the
     * parent (cursor right after a closing brace is outside the block).
     */
    public ScopeNode innermost(int offset) {
        for (ScopeNode child : children) {
            if (offset >= child.startOffset && offset < child.endOffset) {
                return child.innermost(offset);
            }
        }
        return this;
    }

    /**
     * Symbols visible at the offset in inner-to-outer order, applying
     * declaration order (a symbol is visible only at/after its name offset)
     * and shadowing (the innermost declaration of a name wins).
     */
    public List<Symbol> visibleSymbols(int offset) {
        Map<String, Symbol> byName = new LinkedHashMap<>();
        for (ScopeNode scope = this; scope != null; scope = scope.parent) {
            for (Symbol symbol : scope.symbols) {
                // declared at/after the cursor: not visible yet
                if (symbol.declaredOffset() > offset) {
                    continue;
                }
                byName.putIfAbsent(symbol.name(), symbol);
            }
        }
        return new ArrayList<>(byName.values());
    }
}
