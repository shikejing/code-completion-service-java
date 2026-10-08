package com.github.shikejing.codecompletionservicejava.config;

import com.github.shikejing.codecompletionservicejava.model.OutOfOrderPolicy;
import com.github.shikejing.codecompletionservicejava.model.StalePolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All tunable knobs of the service. Bound from the completion.* prefix.
 * Every field has a documented default so fixed input + fixed config yields
 * reproducible output.
 */
@ConfigurationProperties(prefix = "completion")
public class CompletionProperties {

    /** Weight added when the symbol type matches the expected type at the cursor. */
    private double typeMatchWeight = 10.0;
    /** Weight added to a symbol declared or touched within recencyWindowEdits edits. */
    private double recencyWeight = 5.0;
    /** Number of recent edits whose touched symbols remain "recent". */
    private int recencyWindowEdits = 10;
    /** Base score per symbol category. */
    private double localVariableBase = 8.0;
    private double parameterBase = 7.0;
    private double fieldBase = 6.0;
    private double methodBase = 5.0;
    private double typeBase = 4.0;
    private double keywordBase = 1.0;
    /** Score added per prefix-matching character (prefix quality). */
    private double prefixCharWeight = 0.2;
    /** Proximity bonus per scope level between the symbol and the cursor. */
    private double proximityWeight = 0.5;

    /** Policy for edits older than the current document version. */
    private StalePolicy stalePolicy = StalePolicy.REJECT;
    /** Policy for edits newer than the current document version. */
    private OutOfOrderPolicy outOfOrderPolicy = OutOfOrderPolicy.BUFFER;
    /** Maximum buffered future edits per document before BUFFER turns into rejection. */
    private int maxBufferedChanges = 100;
    /** Maximum simultaneously open documents; oldest LRU documents are closed beyond it. */
    private int maxOpenDocuments = 1000;
    /** Maximum entries in the per-document completion cache. */
    private int completionCacheSize = 256;
    /** Completion request timeout in milliseconds. */
    private long queryTimeoutMs = 1000;
    /** Size of the query worker pool (edit handling never uses it). */
    private int queryWorkers = 4;
    /** Directory for the durable snapshot; empty disables persistence. */
    private String dataDir = "";
    public double getTypeMatchWeight() {
        return typeMatchWeight;
    }

    public void setTypeMatchWeight(double typeMatchWeight) {
        this.typeMatchWeight = typeMatchWeight;
    }

    public double getRecencyWeight() {
        return recencyWeight;
    }

    public void setRecencyWeight(double recencyWeight) {
        this.recencyWeight = recencyWeight;
    }

    public int getRecencyWindowEdits() {
        return recencyWindowEdits;
    }

    public void setRecencyWindowEdits(int recencyWindowEdits) {
        this.recencyWindowEdits = recencyWindowEdits;
    }

    public double getLocalVariableBase() {
        return localVariableBase;
    }

    public void setLocalVariableBase(double localVariableBase) {
        this.localVariableBase = localVariableBase;
    }

    public double getParameterBase() {
        return parameterBase;
    }

    public void setParameterBase(double parameterBase) {
        this.parameterBase = parameterBase;
    }

    public double getFieldBase() {
        return fieldBase;
    }

    public void setFieldBase(double fieldBase) {
        this.fieldBase = fieldBase;
    }

    public double getMethodBase() {
        return methodBase;
    }

    public void setMethodBase(double methodBase) {
        this.methodBase = methodBase;
    }

    public double getTypeBase() {
        return typeBase;
    }

    public void setTypeBase(double typeBase) {
        this.typeBase = typeBase;
    }

    public double getKeywordBase() {
        return keywordBase;
    }

    public void setKeywordBase(double keywordBase) {
        this.keywordBase = keywordBase;
    }

    public double getPrefixCharWeight() {
        return prefixCharWeight;
    }

    public void setPrefixCharWeight(double prefixCharWeight) {
        this.prefixCharWeight = prefixCharWeight;
    }

    public double getProximityWeight() {
        return proximityWeight;
    }

    public void setProximityWeight(double proximityWeight) {
        this.proximityWeight = proximityWeight;
    }

    public StalePolicy getStalePolicy() {
        return stalePolicy;
    }

    public void setStalePolicy(StalePolicy stalePolicy) {
        this.stalePolicy = stalePolicy;
    }

    public OutOfOrderPolicy getOutOfOrderPolicy() {
        return outOfOrderPolicy;
    }

    public void setOutOfOrderPolicy(OutOfOrderPolicy outOfOrderPolicy) {
        this.outOfOrderPolicy = outOfOrderPolicy;
    }

    public int getMaxBufferedChanges() {
        return maxBufferedChanges;
    }

    public void setMaxBufferedChanges(int maxBufferedChanges) {
        this.maxBufferedChanges = maxBufferedChanges;
    }

    public int getMaxOpenDocuments() {
        return maxOpenDocuments;
    }

    public void setMaxOpenDocuments(int maxOpenDocuments) {
        this.maxOpenDocuments = maxOpenDocuments;
    }

    public int getCompletionCacheSize() {
        return completionCacheSize;
    }

    public void setCompletionCacheSize(int completionCacheSize) {
        this.completionCacheSize = completionCacheSize;
    }

    public long getQueryTimeoutMs() {
        return queryTimeoutMs;
    }

    public void setQueryTimeoutMs(long queryTimeoutMs) {
        this.queryTimeoutMs = queryTimeoutMs;
    }

    public int getQueryWorkers() {
        return queryWorkers;
    }

    public void setQueryWorkers(int queryWorkers) {
        this.queryWorkers = queryWorkers;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

}
