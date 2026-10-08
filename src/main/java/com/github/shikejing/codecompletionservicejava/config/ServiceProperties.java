package com.github.shikejing.codecompletionservicejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.github.shikejing.codecompletionservicejava.model.VersionPolicy;

/**
 * All externally configurable knobs of the completion service.
 *
 * <p>Every field maps to a property under the {@code completion.*} prefix, see
 * {@code application.properties} and the documentation for the full list and
 * semantics. Configuration is bound at startup; runtime-visible weights are
 * snapshotted inside the ranking component so queries always see a consistent set.</p>
 */
@ConfigurationProperties(prefix = "completion")
public class ServiceProperties {

    /** Maximum number of candidates returned per request. */
    private int maxCandidates = 128;

    /** Minimum prefix length before any candidates are produced. */
    private int minPrefixLength = 0;

    /** Version anomaly handling strategy. */
    private VersionPolicy versionPolicy = VersionPolicy.STRICT;

    /** Per-request timeout in milliseconds; 0 disables the timeout. */
    private long requestTimeoutMillis = 2000;

    /** Whether completion requests may be interrupted via cancellation token. */
    private boolean cancellationEnabled = true;

    private final Weights weights = new Weights();
    private final Incremental incremental = new Incremental();
    private final Cache cache = new Cache();
    private final Persistence persistence = new Persistence();
    private final Limits limits = new Limits();

    public static class Weights {
        /** Base score per candidate category, indexed by CompletionKind name. */
        private double localVariable = 90;
        private double methodParameter = 85;
        private double memberField = 70;
        private double method = 60;
        private double type = 40;
        private double keyword = 10;
        /** Exact prefix match bonus. */
        private double prefixExact = 30;
        /** Case-sensitive prefix match bonus. */
        private double prefixCaseSensitive = 20;
        /** Case-insensitive prefix match bonus. */
        private double prefixCaseInsensitive = 10;
        /** Camel-case / sub-sequence match bonus. */
        private double prefixCamelHump = 8;
        /** Bonus when candidate type matches the expected type of the context. */
        private double typeMatch = 25;
        /** Bonus per unit of recency for symbols edited recently. */
        private double recentlyEdited = 15;
        /** Recency window in milliseconds; edits older than this get no bonus. */
        private long recentlyEditedWindowMillis = 60_000;
        /** Distance penalty per enclosing scope between declaration and cursor. */
        private double scopeDistancePenalty = 4;

        public double getLocalVariable() { return localVariable; }
        public void setLocalVariable(double v) { this.localVariable = v; }
        public double getMethodParameter() { return methodParameter; }
        public void setMethodParameter(double v) { this.methodParameter = v; }
        public double getMemberField() { return memberField; }
        public void setMemberField(double v) { this.memberField = v; }
        public double getMethod() { return method; }
        public void setMethod(double v) { this.method = v; }
        public double getType() { return type; }
        public void setType(double v) { this.type = v; }
        public double getKeyword() { return keyword; }
        public void setKeyword(double v) { this.keyword = v; }
        public double getPrefixExact() { return prefixExact; }
        public void setPrefixExact(double v) { this.prefixExact = v; }
        public double getPrefixCaseSensitive() { return prefixCaseSensitive; }
        public void setPrefixCaseSensitive(double v) { this.prefixCaseSensitive = v; }
        public double getPrefixCaseInsensitive() { return prefixCaseInsensitive; }
        public void setPrefixCaseInsensitive(double v) { this.prefixCaseInsensitive = v; }
        public double getPrefixCamelHump() { return prefixCamelHump; }
        public void setPrefixCamelHump(double v) { this.prefixCamelHump = v; }
        public double getTypeMatch() { return typeMatch; }
        public void setTypeMatch(double v) { this.typeMatch = v; }
        public double getRecentlyEdited() { return recentlyEdited; }
        public void setRecentlyEdited(double v) { this.recentlyEdited = v; }
        public long getRecentlyEditedWindowMillis() { return recentlyEditedWindowMillis; }
        public void setRecentlyEditedWindowMillis(long v) { this.recentlyEditedWindowMillis = v; }
        public double getScopeDistancePenalty() { return scopeDistancePenalty; }
        public void setScopeDistancePenalty(double v) { this.scopeDistancePenalty = v; }
    }

    public static class Incremental {
        /** Enable incremental re-analysis of only affected regions. */
        private boolean enabled = true;
        /** Fall back to a full rebuild when affected region ratio exceeds this value. */
        private double fullRebuildRegionRatio = 0.6;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public double getFullRebuildRegionRatio() { return fullRebuildRegionRatio; }
        public void setFullRebuildRegionRatio(double v) { this.fullRebuildRegionRatio = v; }
    }

    public static class Cache {
        /** Enable cross-request completion cache. */
        private boolean enabled = true;
        /** Maximum cached entries (bounded LRU). */
        private int maxEntries = 512;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getMaxEntries() { return maxEntries; }
        public void setMaxEntries(int v) { this.maxEntries = v; }
    }

    public static class Persistence {
        /** Persist committed document state to local disk. */
        private boolean enabled = true;
        /** Directory used for the local file journal/snapshots. */
        private String dir = "data/completion-service";
        /** Flush to disk after every committed edit (fsync of the journal). */
        private boolean fsync = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public String getDir() { return dir; }
        public void setDir(String v) { this.dir = v; }
        public boolean isFsync() { return fsync; }
        public void setFsync(boolean v) { this.fsync = v; }
    }

    public static class Limits {
        /** Maximum number of tracked open documents (bounded memory). */
        private int maxDocuments = 256;
        /** Maximum document text size in characters. */
        private int maxDocumentChars = 1_000_000;
        /** Number of worker threads serving completion queries. */
        private int workerThreads = 4;

        public int getMaxDocuments() { return maxDocuments; }
        public void setMaxDocuments(int v) { this.maxDocuments = v; }
        public int getMaxDocumentChars() { return maxDocumentChars; }
        public void setMaxDocumentChars(int v) { this.maxDocumentChars = v; }
        public int getWorkerThreads() { return workerThreads; }
        public void setWorkerThreads(int v) { this.workerThreads = v; }
    }

    public int getMaxCandidates() { return maxCandidates; }
    public void setMaxCandidates(int v) { this.maxCandidates = v; }
    public int getMinPrefixLength() { return minPrefixLength; }
    public void setMinPrefixLength(int v) { this.minPrefixLength = v; }
    public VersionPolicy getVersionPolicy() { return versionPolicy; }
    public void setVersionPolicy(VersionPolicy v) { this.versionPolicy = v; }
    public long getRequestTimeoutMillis() { return requestTimeoutMillis; }
    public void setRequestTimeoutMillis(long v) { this.requestTimeoutMillis = v; }
    public boolean isCancellationEnabled() { return cancellationEnabled; }
    public void setCancellationEnabled(boolean v) { this.cancellationEnabled = v; }
    public Weights getWeights() { return weights; }
    public Incremental getIncremental() { return incremental; }
    public Cache getCache() { return cache; }
    public Persistence getPersistence() { return persistence; }
    public Limits getLimits() { return limits; }
}
