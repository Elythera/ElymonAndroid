package com.elythera.elymon.sync;

/** Outcome of a successful {@link ElymonSync#run}. */
public final class SyncResult {
    /** "id" of the NeoForge version JSON, e.g. "neoforge-21.1.249": the profile's lastVersionId. */
    public String versionId;
    public ServerMeta meta;
    /** True when the network failed and the last cached distribution was used (no pruning then). */
    public boolean distributionFromCache;
    public long bytesDownloaded;
    public int filesDownloaded;
    public int filesPruned;
}
