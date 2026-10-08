package com.github.shikejing.codecompletionservicejava.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A lexical scope covering the half-open span {@code [startOffset, endOffset)}.
 * Declarations made directly in the scope live in {@link #symbols()}; nested
 * blocks are children. An unclosed block is represented by a scope whose end
 * equals the text length.
 *
 * <p>{@code parameterOwner} links a method-body scope to the enclosing
 * parameter list scope so parameters declared in the signature are visible
 * inside the body as {@code METHOD_PARAMETER}s.</p>
 */
public final class Scope {

    private final int startOffset;
    private int endOffset;
    private final Scope parent;
    private final String containerName;
    private final int depth;
    private final List<Symbol> symbols = new ArrayList<>();
    private final List<Scope> children = new ArrayList<>();
    private Scope parameterOwner;

    public Scope(int startOffset, int endOffset, Scope parent, String containerName, int depth) {
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.parent = parent;
        this.containerName = containerName;
        this.depth = depth;
    }

    public Scope parent() { return parent; }
    public int startOffset() { return startOffset; }
    public int endOffset() { return endOffset; }
    public void setEndOffset(int end) { this.endOffset = end; }
    public int depth() { return depth; }
    public String containerName() { return containerName; }
    public List<Symbol> symbols() { return Collections.unmodifiableList(symbols); }
    public List<Scope> children() { return Collections.unmodifiableList(children); }
    public Scope parameterOwner() { return parameterOwner; }
    public void setParameterOwner(Scope owner) { this.parameterOwner = owner; }

    public void addSymbol(Symbol symbol) {
        symbols.add(symbol);
    }

    public void addChild(Scope child) {
        children.add(child);
    }

    /** Direct symbol declared in this scope with the given name, if any. */
    public Symbol findDirect(String name) {
        for (Symbol s : symbols) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return null;
    }
}
