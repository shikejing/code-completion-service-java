package com.github.shikejing.codecompletionservicejava.completion;

import java.util.List;

import org.springframework.stereotype.Component;

import com.github.shikejing.codecompletionservicejava.analysis.ParsedUnit;
import com.github.shikejing.codecompletionservicejava.analysis.Symbol;
import com.github.shikejing.codecompletionservicejava.model.CompletionKind;

/**
 * Extracts the identifier prefix under the cursor and detects member access
 * after a {@code .} trigger.
 *
 * <p>For {@code receiver.foo^} the receiver expression is resolved with the
 * same visibility/shadowing rules as normal completion:
 * <ul>
 *   <li>a local variable or parameter uses its declared type;</li>
 *   <li>a field uses the field's declared type;</li>
 *   <li>{@code this.} binds to the innermost type name that lexically
 *       contains the cursor;</li>
 *   <li>a capitalized simple name with a matching declared type is treated
 *       as a type reference (static members).</li>
 * </ul>
 * Anything more complex than a one-hop receiver (e.g. chained calls) is not
 * type-inferred; completion then degrades deterministically to ordinary
 * identifier-prefix completion.</p>
 */
@Component
public class PrefixResolver {

    private final ScopeResolver scopeResolver;

    public PrefixResolver(ScopeResolver scopeResolver) {
        this.scopeResolver = scopeResolver;
    }

    public CandidateRanker.PrefixContext resolve(String text, ParsedUnit parsed, int offset) {
        int end = Math.min(offset, text.length());
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        String prefix = text.substring(start, end);

        int q = start;
        // allow optional whitespace between '.' and prefix: recv . foo
        while (q > 0 && Character.isWhitespace(text.charAt(q - 1))) {
            q--;
        }
        if (q <= 0 || text.charAt(q - 1) != '.') {
            return new CandidateRanker.PrefixContext(prefix, false, null);
        }
        int dot = q - 1;
        int recvEnd = dot;
        while (recvEnd > 0 && Character.isWhitespace(text.charAt(recvEnd - 1))) {
            recvEnd--;
        }
        int recvStart = recvEnd;
        while (recvStart > 0 && Character.isJavaIdentifierPart(text.charAt(recvStart - 1))) {
            recvStart--;
        }
        if (recvStart == recvEnd) {
            return new CandidateRanker.PrefixContext(prefix, false, null);
        }
        String receiver = text.substring(recvStart, recvEnd);
        String type = resolveReceiverType(receiver, parsed, end);
        return new CandidateRanker.PrefixContext(prefix, true, type);
    }

    private String resolveReceiverType(String receiver, ParsedUnit parsed, int cursorOffset) {
        if ("this".equals(receiver)) {
            return innermostTypeName(parsed, cursorOffset);
        }
        List<Symbol> visible = scopeResolver.visibleSymbols(parsed, cursorOffset);
        for (Symbol symbol : visible) {
            if (symbol.name().equals(receiver)
                    && (symbol.kind() == CompletionKind.LOCAL_VARIABLE
                    || symbol.kind() == CompletionKind.METHOD_PARAMETER
                    || symbol.kind() == CompletionKind.MEMBER_FIELD)) {
                return symbol.typeName();
            }
        }
        if (!receiver.isEmpty() && Character.isUpperCase(receiver.charAt(0))
                && parsed.findType(receiver) != null) {
            return receiver;
        }
        return null;
    }

    private String innermostTypeName(ParsedUnit parsed, int offset) {
        String best = null;
        int bestStart = Integer.MIN_VALUE;
        for (var type : parsed.types()) {
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (Symbol member : type.members()) {
                min = Math.min(min, member.startOffset());
                max = Math.max(max, member.endOffset());
            }
            if (min != Integer.MAX_VALUE && offset >= min && offset <= max + 1
                    && min > bestStart) {
                best = type.simpleName();
                bestStart = min;
            }
        }
        return best;
    }
}
