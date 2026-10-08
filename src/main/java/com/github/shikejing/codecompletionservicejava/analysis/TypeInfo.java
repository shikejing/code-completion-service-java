package com.github.shikejing.codecompletionservicejava.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimal structural description of a declared class/interface/enum: just
 * enough for {@code receiver.member} completion and type-matched ranking.
 */
public final class TypeInfo {

    private final String simpleName;
    private final String kind;
    private final List<Symbol> members = new ArrayList<>();
    private final List<String> superTypes = new ArrayList<>();

    public TypeInfo(String simpleName, String kind) {
        this.simpleName = simpleName;
        this.kind = kind;
    }

    public String simpleName() { return simpleName; }
    public String kind() { return kind; }
    public List<Symbol> members() { return Collections.unmodifiableList(members); }
    public List<String> superTypes() { return Collections.unmodifiableList(superTypes); }

    public void addMember(Symbol symbol) {
        members.add(symbol);
    }

    public void addSuperType(String name) {
        if (name != null && !name.isBlank()) {
            superTypes.add(name);
        }
    }
}
