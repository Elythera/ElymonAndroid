package com.elythera.elymon;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftLauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps launcher_profiles.json down to one profile, Elymon's, and selects it.
 *
 * The profile lives under the fixed key ElymonConfig.PROFILE_UUID (a canonical UUID,
 * or LauncherProfiles.normalizeProfileIds would re-key it) and points at the instance
 * ElymonConfig.GAME_DIR. What the player chose in the settings (renderer, ANGLE,
 * controls, Java runtime) is carried over; everything else is Elymon's.
 */
public final class ElymonProfile {
    private static final String TAG = "ElymonProfile";
    private static final String DEFAULT_RENDERER = "opengles_mobileglues";

    private ElymonProfile() {}

    /**
     * Writes the Elymon profile if anything differs and selects it, with commit():
     * ContextAwareDoneListener kills the launcher process right after starting the game.
     * Call on the UI thread (the launcher screens read LauncherProfiles there), after
     * LauncherPreferences.loadPreferences.
     *
     * @param versionId the version to launch, or null to keep the profile's (or the default)
     * @throws RuntimeException when launcher_profiles.json cannot be written (an unreadable one is replaced)
     */
    @SuppressLint("ApplySharedPref") // commit() on purpose, see above
    public static synchronized void ensure(@NonNull Context context, @Nullable String versionId) {
        MinecraftLauncherProfiles launcherProfiles;
        try {
            LauncherProfiles.load();
            launcherProfiles = LauncherProfiles.mainProfileJson;
        } catch (RuntimeException e) {
            // A damaged launcher_profiles.json (a write cut short, a full disk) would otherwise
            // block Play for good, and crash the main menu, which loads it too. Elymon owns the
            // only profile anyway: start again from an empty file, written below.
            Log.w(TAG, "launcher_profiles.json illisible, il est recréé", e);
            launcherProfiles = null;
        }
        if (launcherProfiles == null) {
            launcherProfiles = new MinecraftLauncherProfiles();
            LauncherProfiles.mainProfileJson = launcherProfiles;
        }
        if (launcherProfiles.profiles == null) {
            launcherProfiles.profiles = new HashMap<>();
        }
        Map<String, MinecraftProfile> profiles = launcherProfiles.profiles;

        MinecraftProfile existing = profiles.get(ElymonConfig.PROFILE_UUID);
        // Settings the player may have picked on the profile shown before the Elymon one existed.
        MinecraftProfile settingsSource = existing != null ? existing : profiles.get(currentProfileKey(context));

        MinecraftProfile profile = new MinecraftProfile();
        profile.name = ElymonConfig.PROFILE_NAME;
        profile.gameDir = ElymonConfig.GAME_DIR;
        if (Tools.isValidString(versionId)) {
            profile.lastVersionId = versionId;
        } else if (existing != null && isUsableVersion(existing.lastVersionId)) {
            profile.lastVersionId = existing.lastVersionId;
        } else {
            profile.lastVersionId = ElymonConfig.DEFAULT_VERSION_ID;
        }
        if (existing != null) {
            profile.created = existing.created;
            profile.lastUsed = existing.lastUsed;
        }
        if (settingsSource != null) {
            profile.pojavRendererName = settingsSource.pojavRendererName;
            profile.useANGLE = settingsSource.useANGLE;
            profile.controlFile = settingsSource.controlFile;
            profile.javaDir = settingsSource.javaDir;
        }
        if (!Tools.isValidString(profile.pojavRendererName)) {
            profile.pojavRendererName = DEFAULT_RENDERER;
        }

        boolean profilesChanged = profiles.size() != 1 || existing == null
                || !Tools.GLOBAL_GSON.toJson(existing).equals(Tools.GLOBAL_GSON.toJson(profile));
        if (profilesChanged) {
            Map<String, MinecraftProfile> onlyElymon = new HashMap<>();
            onlyElymon.put(ElymonConfig.PROFILE_UUID, profile);
            // Swap the whole map instead of editing the one screens may be iterating.
            launcherProfiles.profiles = onlyElymon;
            LauncherProfiles.write();
            Log.i(TAG, "Profil Elymon écrit (version " + profile.lastVersionId + ")");
        }

        SharedPreferences preferences = preferences(context);
        if (!ElymonConfig.PROFILE_UUID.equals(preferences.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null))) {
            preferences.edit()
                    .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, ElymonConfig.PROFILE_UUID)
                    .commit();
        }
    }

    /** ensure(context, null) at app start; a failure is logged, never fatal there. */
    public static void ensureAtStartup(@NonNull Context context) {
        try {
            ensure(context, null);
        } catch (RuntimeException e) {
            Log.e(TAG, "Impossible de préparer le profil Elymon au démarrage", e);
        }
    }

    private static boolean isUsableVersion(String versionId) {
        return Tools.isValidString(versionId) && !"Unknown".equals(versionId)
                && !MinecraftProfile.LATEST_RELEASE.equals(versionId)
                && !MinecraftProfile.LATEST_SNAPSHOT.equals(versionId);
    }

    private static String currentProfileKey(Context context) {
        return preferences(context).getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
    }

    private static SharedPreferences preferences(Context context) {
        if (LauncherPreferences.DEFAULT_PREF != null) {
            return LauncherPreferences.DEFAULT_PREF;
        }
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }
}
