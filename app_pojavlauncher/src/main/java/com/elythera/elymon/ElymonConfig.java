package com.elythera.elymon;

/**
 * Constants shared by every Elymon-specific component.
 *
 * Pure Java on purpose (no Android imports) so the sync engine and its host-side
 * tests can use it too. Values that must match the Elythera desktop launcher or
 * ElytheraMod say where their counterpart lives.
 */
public final class ElymonConfig {
    private ElymonConfig() {}

    /** Server id of Elymon in the Elythera distribution index. */
    public static final String SERVER_ID = "elymon-1.21.1";

    /** Same index the desktop launcher reads (ElytheraLauncher app/assets/js/distromanager.js). */
    public static final String DISTRIBUTION_URL = "https://cdn.elythera.com/elylauncher/ndist/distribution.json";

    /**
     * Key of the only launcher profile. It must be a canonical UUID or
     * LauncherProfiles.normalizeProfileIds re-keys it at every load.
     * Value: UUID.nameUUIDFromBytes("elythera:elymon-1.21.1").
     */
    public static final String PROFILE_UUID = "60d8b0e0-1707-34a3-856e-6a4f5fb2bd27";
    public static final String PROFILE_NAME = "Elymon";

    /**
     * Instance directory relative to Tools.DIR_GAME_HOME. The amethyst:// prefix
     * resolves against DIR_GAME_HOME, not against DIR_GAME_NEW (.minecraft):
     * the phase-0 run on a real device started an empty instance because of that.
     */
    public static final String INSTANCE_REL = "custom_instances/" + SERVER_ID;
    public static final String GAME_DIR = "amethyst://" + INSTANCE_REL;

    /**
     * Version the profile points at until a sync reports one: the "id" of the CDN's
     * pre-processed NeoForge JSON (versions/&lt;id&gt;/&lt;id&gt;.json).
     */
    public static final String DEFAULT_VERSION_ID = "neoforge-21.1.249";

    /**
     * Java 21 runtime for arm64-v8a, downloaded at the first Play when no Java 21 is
     * installed. Today it is AngelAuraMC's release asset (download_jre21, 28,675,516
     * bytes); point the URL at an Elythera CDN mirror of the same file later. The
     * SHA-256 is checked before anything is unpacked, so it must change with the file.
     */
    public static final String JRE21_ARM64_URL =
            "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre21/jre21-android-arm64.tar.xz";
    public static final String JRE21_ARM64_SHA256 = "8d41ec401ee59f7722df60ed991f81ad146e130452804bfdd8a05d3436f7bbfe";

    /** Brand announced to ElytheraMod (-Delythera.launcher). The server accepts "elythera" by default (badge.acceptedBrands). */
    public static final String LAUNCHER_BRAND = "elythera";

    /**
     * Desktop launcher protocol level this app implements. Announced as
     * "<level>-android.<versionCode>" and compared with the distribution's
     * "requires.launcher"; ElytheraMod's VersionOrder ignores the qualifier.
     */
    public static final String ELP_LEVEL = "1.4.0";

    /** Elythera's Entra app, already allowed on the Minecraft API (ElytheraLauncher app/assets/js/ipcconstants.js). */
    public static final String AZURE_CLIENT_ID = "6aff0b13-f7fd-4cf3-8c5a-add5a6fe7bfe";
    public static final String AZURE_REDIRECT_URI = "https://login.microsoftonline.com/common/oauth2/nativeclient";

    public static String launcherVersion(int versionCode) {
        return ELP_LEVEL + "-android." + versionCode;
    }
}
