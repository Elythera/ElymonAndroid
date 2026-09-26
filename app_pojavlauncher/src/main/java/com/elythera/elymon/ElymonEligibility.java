package com.elythera.elymon;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ConfigurationInfo;
import android.os.StatFs;
import android.util.Log;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import java.io.File;

/**
 * Whether this device can run Elymon, checked by {@link ElymonLaunch} at every Play before
 * the first byte of a sync (critique.md §3 gap 1). Thresholds in {@link ElymonEligibilityRules}:
 * <ul>
 * <li>total RAM below 5 GiB: refused; below 7 GiB: warned once ("expérience dégradée");</li>
 * <li>OpenGL ES below 3.2: refused;</li>
 * <li>free space where the game lives below max(3 GiB, 1 GiB + first-install estimate) for a
 * first install, or below 1 GiB later: refused, with the amounts.</li>
 * </ul>
 */
final class ElymonEligibility {
    private static final String TAG = "ElymonEligibility";
    private static final String KEY_LOW_RAM_WARNED = "low_ram_warned";

    private ElymonEligibility() {}

    /**
     * Collects the facts, shows the refusal or the one-time warning, and says whether Play may
     * go on. Blocking (the Play worker thread): the warning waits for the player's answer.
     */
    static boolean check(Context app) throws InterruptedException {
        boolean firstInstall = isFirstInstall();
        ElymonEligibilityRules.Verdict verdict = ElymonEligibilityRules.evaluate(
                totalRam(app), glesVersion(app), freeBytes(), firstInstall);
        Log.i(TAG, "Eligibility: " + verdict.outcome + " (RAM " + (verdict.totalRamBytes >> 20) + " MiB, GLES 0x"
                + Integer.toHexString(verdict.glesVersion) + ", free " + (verdict.freeBytes >> 20) + " MiB, first install "
                + firstInstall + ")");
        switch (verdict.outcome) {
            case REFUSE_RAM:
                refuse(app, R.string.elymon_eligibility_ram_title, app.getString(R.string.elymon_eligibility_ram_refused,
                        ElymonLaunch.formatSize(app, verdict.totalRamBytes),
                        ElymonLaunch.formatSize(app, ElymonEligibilityRules.MIN_RAM_BYTES)));
                return false;
            case REFUSE_GLES:
                refuse(app, R.string.elymon_eligibility_gles_title, app.getString(R.string.elymon_eligibility_gles_refused,
                        ElymonEligibilityRules.glesVersionName(verdict.glesVersion)));
                return false;
            case REFUSE_SPACE:
                refuse(app, R.string.elymon_eligibility_space_title, app.getString(
                        firstInstall ? R.string.elymon_eligibility_space_first : R.string.elymon_eligibility_space_later,
                        ElymonLaunch.formatSize(app, verdict.freeBytes),
                        ElymonLaunch.formatSize(app, verdict.requiredFreeBytes)));
                return false;
            case WARN_LOW_RAM:
                return warnLowRamOnce(app, verdict);
            default:
                return true;
        }
    }

    private static boolean warnLowRamOnce(Context app, ElymonEligibilityRules.Verdict verdict) throws InterruptedException {
        SharedPreferences state = app.getSharedPreferences(ElymonLaunch.STATE_PREFS, Context.MODE_PRIVATE);
        if (state.getBoolean(KEY_LOW_RAM_WARNED, false)) {
            return true;
        }
        boolean go = ElymonLaunch.askBlocking(app.getString(R.string.elymon_eligibility_ram_warn_title),
                app.getString(R.string.elymon_eligibility_ram_warn, ElymonLaunch.formatSize(app, verdict.totalRamBytes)),
                app.getString(R.string.elymon_eligibility_continue),
                app.getString(R.string.elymon_eligibility_cancel));
        if (go) {
            // Only once the player chose to go on: "Annuler" shows it again at the next Play.
            state.edit().putBoolean(KEY_LOW_RAM_WARNED, true).commit();
        }
        return go;
    }

    private static void refuse(Context app, int titleRes, String message) {
        new ElymonNotice(app.getString(titleRes), message, null, null).show();
    }

    /**
     * A first install still has to fetch the pack, Minecraft and Java: true until the
     * instance has mods, a NeoForge version JSON is in place and an asset index exists.
     */
    static boolean isFirstInstall() {
        File instanceMods = new File(new File(Tools.DIR_GAME_HOME, ElymonConfig.INSTANCE_REL), "mods");
        String[] mods = instanceMods.list();
        boolean hasMods = false;
        if (mods != null) {
            for (String name : mods) {
                if (name.endsWith(".jar")) {
                    hasMods = true;
                    break;
                }
            }
        }
        String[] indexes = new File(Tools.ASSETS_PATH, "indexes").list();
        return !hasMods || !hasNeoForgeVersion() || indexes == null || indexes.length == 0;
    }

    /** Any versions/neoforge-X/neoforge-X.json: the pack may move past DEFAULT_VERSION_ID. */
    private static boolean hasNeoForgeVersion() {
        String[] versions = new File(Tools.DIR_HOME_VERSION).list();
        if (versions == null) {
            return false;
        }
        for (String id : versions) {
            if (id.startsWith("neoforge-") && new File(Tools.DIR_HOME_VERSION, id + "/" + id + ".json").isFile()) {
                return true;
            }
        }
        return false;
    }

    private static long totalRam(Context app) {
        ActivityManager activityManager = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) {
            return 0;
        }
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memory);
        return memory.totalMem;
    }

    private static int glesVersion(Context app) {
        ActivityManager activityManager = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) {
            return 0;
        }
        ConfigurationInfo configuration = activityManager.getDeviceConfigurationInfo();
        return configuration == null ? 0 : configuration.reqGlEsVersion;
    }

    /** Free space on the volume of DIR_GAME_HOME (Android/data/&lt;app&gt;/files); -1 when unknown. */
    private static long freeBytes() {
        try {
            File home = new File(Tools.DIR_GAME_HOME);
            File existing = home;
            while (existing != null && !existing.exists()) {
                existing = existing.getParentFile();
            }
            if (existing == null) {
                return -1;
            }
            return new StatFs(existing.getAbsolutePath()).getAvailableBytes();
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read the free space", e);
            return -1;
        }
    }
}
