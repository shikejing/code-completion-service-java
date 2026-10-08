package com.github.shikejing.codecompletionservicejava.analysis;

import com.github.shikejing.codecompletionservicejava.model.SymbolKind;

/**
 * A declared symbol discovered during syntactic analysis. Immutable.
 *
 * @param name           identifier text
 * @param kind           category
 * @param detail         display detail (e.g. inferred type or signature)
 * @param declaredOffset absolute offset of the declaration name
 * @param scopeStart    start offset of the declaring scope
 */
public record Symbol(String name,
                     SymbolKind kind,
                     String detail,
                     int declaredOffset,
                     int scopeStart) {
}
