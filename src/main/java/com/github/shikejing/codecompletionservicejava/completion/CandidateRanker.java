package com.github.shikejing.codecompletionservicejava.completion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.github.shikejing.codecompletionservicejava.analysis.Symbol;
import com.github.shikejing.codecompletionservicejava.config.ServiceProperties;
import com.github.shikejing.codecompletionservicejava.model.CompletionCandidate;
import com.github.shikejing.codecompletionservicejava.model.CompletionKind;
import com.github.shikejing.codecompletionservicejava.model.SymbolSource;

/**
 * Scores visible symbols and orders the deduplicated candidates.
 *
 * <h3>Score of one declaration</h3>
 * <pre>
 *   categoryBase(kind)
 *     + prefixBonus(prefix, label)
 *     + typeMatchBonus           (when the candidate's type equals the
 *                                 desired/expected type of the context)
 *     + recentlyEditedBonus      (decays linearly inside the configured
 *                                 recency window, else 0)
 *     - scopeDistancePenalty * distance
 * </pre>
 * All positive weights come from {@link ServiceProperties.Weights}; the
 * formula is documented in the README.
 *
 * <h3>Determinism</h3>
 * Ties are broken by (higher score, kind ordinal, label, container,
 * declaration offset) before the list is truncated, so identical inputs and
 * configuration always produce byte-for-byte identical results.
 */
@Component
public class CandidateRanker {

    /** Symbol decorated with visibility information before ranking. */
    public record ScoredSymbol(
            Symbol symbol,
            int scopeDistance,
            String typeName,
            List<SymbolSource> shadowedSources
    ) {}

    /** Prefix extraction result. */
    public record PrefixContext(String prefix, boolean dotMember, String receiverType) {}

    private final ServiceProperties properties;

    public CandidateRanker(ServiceProperties properties) {
        this.properties = properties;
    }

    public double categoryBase(CompletionKind kind) {
        ServiceProperties.Weights w = properties.getWeights();
        return switch (kind) {
            case LOCAL_VARIABLE -> w.getLocalVariable();
            case METHOD_PARAMETER -> w.getMethodParameter();
            case MEMBER_FIELD -> w.getMemberField();
            case METHOD -> w.getMethod();
            case TYPE -> w.getType();
            case KEYWORD -> w.getKeyword();
        };
    }

    /**
     * Ranks symbols visible at the cursor.
     *
     * @param symbols      symbols with scope distance (already shadowed-aware)
     * @param prefix       prefix/member context at the cursor
     * @param desiredType  expected type at the completion site, if known
     * @param nowNanos     current time for recency calculation
     * @param lastEditNanos last edit time of the document
     */
    public List<CompletionCandidate> rank(List<ScoredSymbol> symbols, PrefixContext prefix,
                                          String desiredType, long nowNanos, long lastEditNanos) {
        String p = prefix.prefix() == null ? "" : prefix.prefix();
        Map<String, Aggregated> byName = new LinkedHashMap<>();
        for (ScoredSymbol ss : symbols) {
            Symbol sym = ss.symbol();
            if (!matchesPrefix(p, sym.name())) {
                continue;
            }
            double score = scoreOne(ss, p, desiredType, nowNanos, lastEditNanos);
            List<String> breakdown = breakdown(ss, p, desiredType, nowNanos, lastEditNanos);
            byName.computeIfAbsent(sym.name(),
                    k -> new Aggregated(sym, ss)).add(score, breakdown, sym, ss);
        }

        List<Aggregated> aggregated = new ArrayList<>(byName.values());
        aggregated.sort((a, b) -> compareCandidates(b, a)); // higher score first
        int max = properties.getMaxCandidates();
        List<CompletionCandidate> out = new ArrayList<>();
        for (Aggregated agg : aggregated) {
            if (out.size() >= max) {
                break;
            }
            out.add(agg.toCandidate(prefix));
        }
        return out;
    }

    private double scoreOne(ScoredSymbol ss, String prefix, String desiredType,
                            long nowNanos, long lastEditNanos) {
        Symbol sym = ss.symbol();
        double score = categoryBase(sym.kind());
        score += prefixBonus(prefix, sym.name());
        if (desiredType != null && !desiredType.isBlank()
                && desiredType.equals(nonBlank(ss.typeName(), sym.typeName()))) {
            score += properties.getWeights().getTypeMatch();
        }
        score -= properties.getWeights().getScopeDistancePenalty() * ss.scopeDistance();
        score += recencyBonus(nowNanos, lastEditNanos);
        return score;
    }

    private List<String> breakdown(ScoredSymbol ss, String prefix, String desiredType,
                                   long nowNanos, long lastEditNanos) {
        Symbol sym = ss.symbol();
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "category(%s)=%.1f", sym.kind(), categoryBase(sym.kind())));
        lines.add(String.format(Locale.ROOT, "prefix(%s)=%.1f", prefix, prefixBonus(prefix, sym.name())));
        double typeBonus = 0;
        String actualType = nonBlank(ss.typeName(), sym.typeName());
        if (desiredType != null && !desiredType.isBlank() && desiredType.equals(actualType)) {
            typeBonus = properties.getWeights().getTypeMatch();
        }
        lines.add(String.format(Locale.ROOT, "typeMatch(%s==%s)=%.1f", desiredType, actualType, typeBonus));
        double distance = properties.getWeights().getScopeDistancePenalty() * ss.scopeDistance();
        lines.add(String.format(Locale.ROOT, "scopeDistance(%d)=-%.1f", ss.scopeDistance(), distance));
        double recency = recencyBonus(nowNanos, lastEditNanos);
        lines.add(String.format(Locale.ROOT, "recentlyEdited=%.1f", recency));
        return lines;
    }

    private double recencyBonus(long nowNanos, long lastEditNanos) {
        long windowNanos = properties.getWeights().getRecentlyEditedWindowMillis() * 1_000_000L;
        if (windowNanos <= 0 || lastEditNanos <= 0) {
            return 0;
        }
        long age = nowNanos - lastEditNanos;
        if (age < 0 || age > windowNanos) {
            return 0;
        }
        double factor = 1.0 - ((double) age / windowNanos);
        return properties.getWeights().getRecentlyEdited() * factor;
    }

    private double prefixBonus(String prefix, String label) {
        ServiceProperties.Weights w = properties.getWeights();
        if (prefix.isEmpty()) {
            return 0;
        }
        if (label.equals(prefix)) {
            return w.getPrefixExact();
        }
        if (label.startsWith(prefix)) {
            return w.getPrefixCaseSensitive();
        }
        if (label.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
            return w.getPrefixCaseInsensitive();
        }
        if (isCamelHump(prefix, label)) {
            return w.getPrefixCamelHump();
        }
        return 0;
    }

    private boolean matchesPrefix(String prefix, String label) {
        if (prefix.isEmpty()) {
            return true;
        }
        if (label.startsWith(prefix)) {
            return true;
        }
        if (label.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return isCamelHump(prefix, label);
    }

    /** True when prefix letters match the capital letters / subsequence of label. */
    private boolean isCamelHump(String prefix, String label) {
        if (prefix.isEmpty()) {
            return false;
        }
        int j = 0;
        for (int i = 0; i < label.length() && j < prefix.length(); i++) {
            char c = label.charAt(i);
            if (Character.isUpperCase(c) && c == prefix.charAt(j)) {
                j++;
            }
        }
        if (j == prefix.length()) {
            return true;
        }
        // plain subsequence (case-insensitive)
        j = 0;
        String lp = label.toLowerCase(Locale.ROOT);
        String pp = prefix.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lp.length() && j < pp.length(); i++) {
            if (lp.charAt(i) == pp.charAt(j)) {
                j++;
            }
        }
        return j == pp.length();
    }

    private String nonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    /**
     * Total ordering of candidates. Higher score wins; every subsequent key is
     * a fixed, input-derived value so the order is fully deterministic.
     */
    private int compareCandidates(Aggregated a, Aggregated b) {
        int byScore = Double.compare(a.bestScore, b.bestScore);
        if (byScore != 0) {
            return byScore;
        }
        int byKind = Integer.compare(b.primary.kind().ordinal(), a.primary.kind().ordinal());
        if (byKind != 0) {
            return byKind;
        }
        int byName = a.primary.name().compareTo(b.primary.name());
        if (byName != 0) {
            return byName;
        }
        String ca = a.primary.containerName() == null ? "" : a.primary.containerName();
        String cb = b.primary.containerName() == null ? "" : b.primary.containerName();
        int byContainer = ca.compareTo(cb);
        if (byContainer != 0) {
            return byContainer;
        }
        return Integer.compare(a.primary.startOffset(), b.primary.startOffset());
    }

    /** Accumulates every declaration contributing to one visible name. */
    private static final class Aggregated {
        private final Symbol primary;
        private final ScoredSymbol primaryScored;
        private double bestScore = -Double.MAX_VALUE;
        private List<String> bestBreakdown = List.of();
        private final List<SymbolSource> sources = new ArrayList<>();

        Aggregated(Symbol first, ScoredSymbol firstScored) {
            this.primary = first;
            this.primaryScored = firstScored;
        }

        void add(double score, List<String> breakdown, Symbol symbol, ScoredSymbol ss) {
            if (score > bestScore) {
                bestScore = score;
                bestBreakdown = new ArrayList<>(breakdown);
            }
            sources.add(new SymbolSource(symbol.kind(), symbol.containerName(),
                    symbol.startOffset(), symbol.line()));
        }

        CompletionCandidate toCandidate(CandidateRanker.PrefixContext prefix) {
            double rounded = Math.round(bestScore * 100.0d) / 100.0d;
            String sortText = String.format(Locale.ROOT, "%012.2f|%s|%d",
                    bestScore, primary.name(), primary.startOffset());
            return new CompletionCandidate(primary.name(), primary.kind(),
                    primary.typeName(), rounded, sortText,
                    List.copyOf(sources), List.copyOf(bestBreakdown));
        }
    }
}
