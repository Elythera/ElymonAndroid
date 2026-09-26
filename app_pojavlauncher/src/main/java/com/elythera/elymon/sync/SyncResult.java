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

    // Added by the sync package.

    /** Files the distribution places after the Android policy (versions, libraries, mods, instance files). */
    public int filesPlanned;
    /** Mods placed in mods/ after the Android policy. */
    public int modsPlanned;
    /** Files placed without a download: copies of identical content, empty files. */
    public int filesCopied;
    /** Overlay edits (re)applied during this run. */
    public int overlaysApplied;
    /** True when options.txt was missing or empty and was created by this run. */
    public boolean optionsSeeded;
    /** Non-fatal oddities, in English, for the log. They never contain secrets. */
    public final java.util.List<String> warnings = new java.util.ArrayList<String>();
}
