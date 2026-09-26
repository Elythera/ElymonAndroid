package com.elythera.elymon.sync;

import com.elythera.elymon.ElymonConfig;

import java.io.File;

/** Inputs of {@link ElymonSync#run}. Plain fields: the caller fills what it needs. */
public final class SyncOptions {
    /** Tools.DIR_GAME_HOME: the instance lives in gameHome/ElymonConfig.INSTANCE_REL. */
    public File gameHome;
    /** Tools.DIR_GAME_NEW (.minecraft): versions/ and libraries/ live there. */
    public File minecraftDir;
    /**
     * Private scratch space: distribution cache (distribution.json and its
     * ETag), version JSON cache (manifests/) and sync-index.json. Partial
     * downloads are not kept here but next to their target, as {@code <target>.part}.
     */
    public File workDir;
    /** Content of assets/elymon/android-policy.json, read by the caller. */
    public String policyJson;
    public String distributionUrl = ElymonConfig.DISTRIBUTION_URL;
    public String serverId = ElymonConfig.SERVER_ID;
    /** False while the game process is alive: files in use are never pruned. */
    public boolean allowPrune = true;
    public long confirmThresholdBytes = 256L * 1024 * 1024;
    public int threads = 6;
    /** User-Agent sent to the CDN. */
    public String userAgent = "ElymonAndroid";

    // Added by the sync package.

    /**
     * Where the French texts come from; null uses the built-in copies, which
     * equal res/values/elymon_sync_strings.xml. An Android caller may resolve
     * key k to the string resource "elymon_sync_" + k.
     */
    public SyncText.Resolver text;
}
