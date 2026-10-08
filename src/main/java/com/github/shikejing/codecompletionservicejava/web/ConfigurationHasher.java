package com.github.shikejing.codecompletionservicejava.web;

import java.util.Locale;

import com.github.shikejing.codecompletionservicejava.config.ServiceProperties;

/**
 * Stable hash of every completion-affecting configuration value. It is part
 * of the cache key so a changed configuration can never return entries
 * computed under different weights.
 */
final class ConfigurationHasher {

    private ConfigurationHasher() {}

    static String hash(ServiceProperties p) {
        ServiceProperties.Weights w = p.getWeights();
        String raw = String.join("|",
                String.valueOf(p.getMaxCandidates()),
                String.valueOf(p.getMinPrefixLength()),
                String.valueOf(p.getVersionPolicy()),
                fmt(w.getLocalVariable()), fmt(w.getMethodParameter()),
                fmt(w.getMemberField()), fmt(w.getMethod()),
                fmt(w.getType()), fmt(w.getKeyword()),
                fmt(w.getPrefixExact()), fmt(w.getPrefixCaseSensitive()),
                fmt(w.getPrefixCaseInsensitive()), fmt(w.getPrefixCamelHump()),
                fmt(w.getTypeMatch()), fmt(w.getRecentlyEdited()),
                String.valueOf(w.getRecentlyEditedWindowMillis()),
                fmt(w.getScopeDistancePenalty()));
        return Integer.toHexString(raw.hashCode());
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
