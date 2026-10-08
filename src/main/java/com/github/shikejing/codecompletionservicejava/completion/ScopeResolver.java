package com.github.shikejing.codecompletionservicejava.completion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.github.shikejing.codecompletionservicejava.analysis.ParsedUnit;
import com.github.shikejing.codecompletionservicejava.analysis.Scope;
import com.github.shikejing.codecompletionservicejava.analysis.Symbol;

/**
 * Collects declarations visible at a document offset.
 *
 * <h3>Rules</h3>
 * <ul>
 *   <li>A declaration is visible when its offset is at or before the cursor
 *       (it has already been typed) and the cursor lies inside its scope.</li>
 *   <li>Scopes are walked from innermost to outermost; a name already found
 *       in an inner scope shadows every outer declaration of the same name.
 *       Shadowed declarations are still tracked as sources for diagnostics
 *       but do not become separate candidates.</li>
 *   <li>Method parameters are visible throughout the method body via the
 *       scope's parameter-owner link.</li>
 *   <li>Unclosed blocks work identically: their implicit end is EOF, so
 *       everything declared before the cursor remains visible.</li>
 * </ul>
 */
@Component
public class ScopeResolver {

    /** Symbol plus the number of scope boundaries crossed to reach it. */
    public record VisibleSymbol(Symbol symbol, int distance) {}

    public List<VisibleSymbol> resolve(ParsedUnit unit, int offset) {
        Map<String, VisibleSymbol> byName = new LinkedHashMap<>();
        if (unit == null) {
            return List.of();
        }
        Scope innermost = innermostScope(unit.rootScope(), offset);
        Scope s = innermost;
        int distance = 0;
        Set<String> claimed = new HashSet<>();
        while (s != null) {
            // parameters of the enclosing method: distance 0 for the body
            Scope paramOwner = s.parameterOwner();
            if (paramOwner != null) {
                addScopeSymbols(paramOwner, offset, distance, byName, claimed);
            }
            addScopeSymbols(s, offset, distance, byName, claimed);
            s = s.parent();
            distance++;
        }
        return new ArrayList<>(byName.values());
    }

    public List<Symbol> visibleSymbols(ParsedUnit parsed, int offset) {
        List<VisibleSymbol> resolved = resolve(parsed, offset);
        List<Symbol> out = new ArrayList<>(resolved.size());
        for (VisibleSymbol v : resolved) {
            out.add(v.symbol());
        }
        return out;
    }

    private void addScopeSymbols(Scope scope, int offset, int distance,
                                 Map<String, VisibleSymbol> byName, Set<String> claimed) {
        for (Symbol symbol : scope.symbols()) {
            // declared before/at cursor (at offset == start the prefix char
            // itself is being typed, so the declaration can't precede cursor)
            if (symbol.startOffset() >= offset) {
                continue;
            }
            if (claimed.contains(symbol.name())) {
                continue;
            }
            claimed.add(symbol.name());
            byName.put(symbol.name(), new VisibleSymbol(symbol, distance));
        }
    }

    /** Finds the deepest scope that both contains the offset and starts at/before it. */
    private Scope innermostScope(Scope root, int offset) {
        Scope best = root;
        // iterative DFS in declaration order for determinism
        List<Scope> stack = new ArrayList<>();
        stack.add(root);
        int bestDepth = -1;
        while (!stack.isEmpty()) {
            Scope s = stack.remove(stack.size() - 1);
            if (s.startOffset() <= offset && offset <= s.endOffset() && s.depth() > bestDepth) {
                best = s;
                bestDepth = s.depth();
            }
            for (int i = s.children().size() - 1; i >= 0; i--) {
                stack.add(s.children().get(i));
            }
        }
        return best;
    }
}
