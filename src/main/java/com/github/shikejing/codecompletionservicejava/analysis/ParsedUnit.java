package com.github.shikejing.codecompletionservicejava.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * Parse output for one committed document version.
 *
 * @param text exact source text this unit was derived from
 * @param version document version the text corresponds to
 * @param rootScope outermost (file/class) scope; unclosed blocks are
 *                  implicitly closed at EOF
 * @param types types declared in the file
 * @param hasSyntaxErrors true when the lexer/parser had to recover (the tree
 *                        is still a valid, deterministic best effort)
 * @param fullRebuild true when produced by a full (re)parse rather than an
 *                    incremental update; observable for tests/debugging only
 */
public record ParsedUnit(
        String text,
        long version,
        Scope rootScope,
        List<TypeInfo> types,
        boolean hasSyntaxErrors,
        boolean fullRebuild
) {
    public ParsedUnit {
        types = types == null ? List.of() : List.copyOf(types);
    }

    public TypeInfo findType(String simpleName) {
        if (simpleName == null) {
            return null;
        }
        for (TypeInfo type : types) {
            if (type.simpleName().equals(simpleName)) {
                return type;
            }
        }
        return null;
    }

    /** All members of a type including (one level of) inherited members. */
    public List<Symbol> membersOf(String simpleName) {
        TypeInfo type = findType(simpleName);
        if (type == null) {
            return List.of();
        }
        List<Symbol> out = new ArrayList<>(type.members());
        for (String superName : type.superTypes()) {
            TypeInfo sup = findType(superName);
            if (sup != null) {
                out.addAll(sup.members());
            }
        }
        return out;
    }
}
