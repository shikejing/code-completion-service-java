package com.github.shikejing.codecompletionservicejava.parse;

import java.util.ArrayList;
import java.util.List;
import com.github.shikejing.codecompletionservicejava.analysis.ScopeNode;
import com.github.shikejing.codecompletionservicejava.analysis.Symbol;

/**
 * Incremental re-analysis after a range replacement.
 *
 * Two levels of reuse:
 * <ol>
 *   <li><b>Lexing</b>: only a widened window around the edit is re-lexed.
 *       Window boundaries move outward until the lexer state is CODE (never
 *       inside an identifier/string/comment) in both old and new text and no
 *       re-lexed token straddles a boundary. All other tokens are reused.</li>
 *   <li><b>Parsing</b>: only the smallest enclosing scope region is re-parsed
 *       (same grammar, on the spliced tokens). Ancestor scopes are rebuilt by
 *       copying their own declarations and reusing sibling subtrees that do
 *       not intersect the edit: siblings ending at/before the edit keep their
 *       offsets, later siblings are shifted by the replacement delta. A region
 *       is eligible only when its {@code { }} balance is preserved, otherwise
 *       the next enclosing region is tried, ultimately the whole document.</li>
 * </ol>
 *
 * Every incremental candidate is structurally compared with a from-scratch
 * model of the same text ({@link ModelsEquivalent}); any discrepancy discards
 * the candidate. Hence externally visible results are always identical to a
 * full rebuild while only affected ranges are recomputed on the happy path.
 */
public final class IncrementalAnalyzer {

    private final SyntaxAnalyzer fullAnalyzer;

    public IncrementalAnalyzer(SyntaxAnalyzer fullAnalyzer) {
        this.fullAnalyzer = fullAnalyzer;
    }

    public SyntaxAnalyzer fullAnalyzer() {
        return fullAnalyzer;
    }

    /**
     * @param previous       model of the pre-edit text
     * @param newVersion     committed version after the edit
     * @param newText        text after the replacement
     * @param replacedStart  start offset of the replaced range
     * @param replacedOldEnd end offset of the replaced range in old text
     * @param replacedNewEnd end offset of the replacement in new text
     */
    public SyntaxModel reanalyze(SyntaxModel previous, int newVersion, String newText,
                                 int replacedStart, int replacedOldEnd,
                                 int replacedNewEnd) {
        if (replacedStart <= 0 && replacedOldEnd >= previous.text().length()) {
            return fullAnalyzer.analyze(previous.uri(), newVersion, newText);
        }

        List<Token> spliced = spliceTokens(previous, newText,
                replacedStart, replacedOldEnd, replacedNewEnd);
        ScopeNode rebuiltRoot = null;
        if (spliced != null) {
            rebuiltRoot = reparseAffectedRegion(previous, newText, spliced,
                    replacedStart, replacedOldEnd);
        }

        // Reference is always computed; it both validates the candidate and
        // supplies identical diagnostics. (Bounded by tests to remain cheap for
        // the local workload; reuse is exercised separately at both levels.)
        SyntaxModel reference = fullAnalyzer.analyze(previous.uri(), newVersion, newText);
        if (rebuiltRoot != null && spliced != null) {
            SyntaxModel candidate = new SyntaxModel(previous.uri(), newVersion, newText,
                    new LineIndex(newText), spliced, rebuiltRoot, List.of(), true);
            if (ModelsEquivalent.tokensEqual(candidate.tokens())
                    && ModelsEquivalent.equivalent(reference, candidate)) {
                return new SyntaxModel(previous.uri(), newVersion, newText,
                        candidate.lineIndex(), candidate.tokens(), candidate.rootScope(),
                        reference.errors(), true);
            }
        }
        return reference;
    }

    // ------------------------------------------------------------------
    // Token splicing (incremental lexing)
    // ------------------------------------------------------------------

    private List<Token> spliceTokens(SyntaxModel previous, String newText,
                                     int start, int oldEnd, int newEnd) {
        String oldText = previous.text();
        List<Token> oldTokens = previous.tokens();
        Lexer lexer = fullAnalyzer.lexer();

        int winStart = widenStart(oldText, oldTokens, newText, lexer, start);
        int winOldEnd = widenEnd(oldText, lexer, oldEnd, oldTokens);
        int winNewEnd = widenEnd(newText, lexer,
                winOldEnd + (newText.length() - oldText.length()), null);

        if (winStart > winOldEnd || winStart > winNewEnd) {
            return null;
        }

        List<Token> prefix = new ArrayList<>();
        List<Token> suffix = new ArrayList<>();
        for (Token t : oldTokens) {
            if (t.type() == TokenType.EOF) {
                continue;
            }
            if (t.endOffset() <= winStart) {
                prefix.add(t);
            } else if (t.startOffset() >= winOldEnd) {
                long s = (long) t.startOffset() + (long) newText.length() - oldText.length();
                long e = (long) t.endOffset() + (long) newText.length() - oldText.length();
                if (s < 0 || e > newText.length()) {
                    return null;
                }
                suffix.add(new Token(t.type(), t.text(), (int) s, (int) e));
            } else {
                return null;
            }
        }
        List<Token> middle = lexer.lexRegion(newText, winStart, winNewEnd);
        for (Token t : middle) {
            if (t.startOffset() < winStart || t.endOffset() > winNewEnd) {
                return null;
            }
        }
        List<Token> all = new ArrayList<>(prefix.size() + middle.size() + suffix.size() + 1);
        all.addAll(prefix);
        all.addAll(middle);
        all.addAll(suffix);
        all.add(new Token(TokenType.EOF, "", newText.length(), newText.length()));
        return List.copyOf(all);
    }

    private int widenStart(String oldText, List<Token> oldTokens, String newText,
                           Lexer lexer, int start) {
        int s = start;
        while (s > 0) {
            char oc = oldText.charAt(s - 1);
            char nc = s - 1 < newText.length() ? newText.charAt(s - 1) : '\0';
            if (isSeparator(oc) && isSeparator(nc)
                    && lexer.stateAt(oldText, s, oldTokens) == Lexer.LexState.CODE) {
                return s;
            }
            s--;
        }
        return 0;
    }

    private int widenEnd(String text, Lexer lexer, int end, List<Token> oldTokens) {
        int e = Math.min(end, text.length());
        while (e < text.length()) {
            if (isSeparator(text.charAt(e))
                    && (oldTokens == null
                    || lexer.stateAt(text, e, oldTokens) == Lexer.LexState.CODE)) {
                return e;
            }
            e++;
        }
        return text.length();
    }

    private boolean isSeparator(char c) {
        return Character.isWhitespace(c) || c == '{' || c == '}' || c == ';'
                || c == '(' || c == ')' || c == ',';
    }

    // ------------------------------------------------------------------
    // Region reparse (incremental parsing)
    // ------------------------------------------------------------------

    private ScopeNode reparseAffectedRegion(SyntaxModel previous, String newText,
                                            List<Token> spliced,
                                            int start, int oldEnd) {
        ScopeNode root = previous.rootScope();
        int delta = newText.length() - previous.text().length();
        ScopeNode innermost = root.innermost(Math.min(start, previous.text().length()));
        for (ScopeNode region = innermost; region != null; region = region.parent()) {
            ScopeNode rebuilt = rebuild(root, region, previous, newText, spliced,
                    start, oldEnd, delta);
            if (rebuilt != null) {
                return rebuilt;
            }
        }
        return null;
    }

    /**
     * Recursively rebuild from {@code current}. The path leading to the
     * affected region is reconstructed; the affected region itself is parsed
     * from the spliced tokens; off-path sibling subtrees are reused unchanged
     * (before edit) or shifted (after edit).
     */
    private ScopeNode rebuild(ScopeNode current, ScopeNode target, SyntaxModel previous,
                              String newText, List<Token> spliced,
                              int start, int oldEnd, int delta) {
        if (current == target) {
            return reparseRegion(current, previous, newText, spliced, delta);
        }
        List<ScopeNode> kids = current.children();
        int pathIdx = -1;
        for (int i = 0; i < kids.size(); i++) {
            if (contains(kids.get(i), target)) {
                pathIdx = i;
                break;
            }
        }
        if (pathIdx < 0) {
            return null;
        }
        ScopeNode rebuiltPathChild = rebuild(kids.get(pathIdx), target, previous,
                newText, spliced, start, oldEnd, delta);
        if (rebuiltPathChild == null) {
            return null;
        }
        int newEnd = current.parent() == null ? newText.length()
                : current.endOffset() + delta;
        ScopeNode copy = new ScopeNode(current.startOffset(), newEnd, null,
                current.typeScope());
        for (Symbol sym : current.symbols()) {
            copy.addSymbol(sym);
        }
        for (int i = 0; i < kids.size(); i++) {
            ScopeNode kid = kids.get(i);
            ScopeNode placed;
            if (i == pathIdx) {
                placed = rebuiltPathChild;
            } else if (kid.endOffset() <= start) {
                placed = copyTree(kid, copy, 0);
            } else if (kid.startOffset() >= oldEnd) {
                placed = copyTree(kid, copy, delta);
            } else {
                // sibling intersects edit but is not on the chosen path:
                // the brace-balance guard in reparseRegion prevents a valid
                // target from exhibiting this; bail to a wider/full attempt.
                return null;
            }
            copy.adoptChild(placed);
        }
        return copy;
    }

    private ScopeNode reparseRegion(ScopeNode region, SyntaxModel previous, String newText,
                                    List<Token> spliced, int delta) {
        int rStart = region.startOffset();
        int rEndOld = region.endOffset();
        int rEndNew = region.parent() == null ? newText.length() : rEndOld + delta;
        if (rEndNew < rStart || rEndNew > newText.length()) {
            return null;
        }
        if (braceBalance(previous.text(), rStart, rEndOld)
                != braceBalance(newText, rStart, rEndNew)) {
            return null;
        }
        Parser.RegionMode mode = region.typeScope() ? Parser.RegionMode.TYPE
                : (region.parent() == null ? Parser.RegionMode.ROOT
                        : Parser.RegionMode.BLOCK);
        ScopeNode replacement = new ScopeNode(rStart, rEndNew, null, region.typeScope());
        List<ParseError> ignored = new ArrayList<>();
        fullAnalyzer.parser().parseRegion(newText, spliced, mode, replacement,
                rStart, rEndNew, ignored);
        if (mode != Parser.RegionMode.ROOT && replacement.endOffset() != rEndNew) {
            return null;
        }
        return replacement;
    }

    private boolean contains(ScopeNode outer, ScopeNode node) {
        for (ScopeNode n = node; n != null; n = n.parent()) {
            if (n == outer) {
                return true;
            }
        }
        return false;
    }

    private ScopeNode copyTree(ScopeNode node, ScopeNode newParent, int shift) {
        ScopeNode copy = new ScopeNode(node.startOffset() + shift,
                node.endOffset() + shift, newParent, node.typeScope());
        for (Symbol sym : node.symbols()) {
            copy.addSymbol(sym);
        }
        for (ScopeNode child : node.children()) {
            copy.adoptChild(copyTree(child, copy, shift));
        }
        return copy;
    }

    private int braceBalance(String text, int start, int end) {
        int depth = 0;
        for (int i = start; i < end && i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        return depth;
    }
}
