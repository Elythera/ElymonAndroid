package com.elythera.elymon.ui;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.elythera.elymon.ElymonConfig;
import com.elythera.elymon.ElymonProfile;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftLauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * The renderer choice of the settings. Upstream only had it in the profile editor, which
 * Elymon removed: the game reads it from the profile (MainActivity: pojavRendererName),
 * so it is saved in the Elymon profile, whose ElymonProfile.ensure carries it over.
 *
 * Only two renderers are offered: MobileGlues (phase 0 ran the pack on it at 60 FPS with
 * Sodium) and Kopper Zink, the Vulkan fallback, when the phone has Vulkan. UI thread.
 */
public final class ElymonRenderer {
    public static final String MOBILEGLUES = "opengles_mobileglues";
    public static final String KOPPER_ZINK = "opengles3_desktopgl_zink_kopper";
    /** Older Zink id; MainActivity runs it as Kopper Zink. */
    private static final String LEGACY_ZINK = "vulkan_zink";
    private static final String TAG = "ElymonRenderer";

    private ElymonRenderer() {}

    /** Renderer ids this phone can use, MobileGlues first. */
    @NonNull
    public static List<String> available(@NonNull Context context) {
        List<String> ids = new ArrayList<>(2);
        ids.add(MOBILEGLUES);
        // Upstream's compatibility list does not check Vulkan for this id: do it here.
        if (Tools.checkVulkanSupport(context.getPackageManager())
                && Tools.checkRendererCompatible(context, KOPPER_ZINK)) {
            ids.add(KOPPER_ZINK);
        }
        return ids;
    }

    /** The renderer the next game will use (MobileGlues when none or an unavailable one is saved). */
    @NonNull
    public static String current(@NonNull Context context) {
        String id = null;
        try {
            LauncherProfiles.load();
            MinecraftLauncherProfiles profiles = LauncherProfiles.mainProfileJson;
            MinecraftProfile profile = profiles == null || profiles.profiles == null ? null
                    : profiles.profiles.get(ElymonConfig.PROFILE_UUID);
            id = profile == null ? null : profile.pojavRendererName;
        } catch (RuntimeException e) {
            Log.w(TAG, "launcher_profiles.json illisible", e);
        }
        if (LEGACY_ZINK.equals(id)) {
            id = KOPPER_ZINK;
        }
        return id != null && available(context).contains(id) ? id : MOBILEGLUES;
    }

    public static boolean isZink(@NonNull Context context) {
        return KOPPER_ZINK.equals(current(context));
    }

    /**
     * Saves the renderer into the Elymon profile (created first if needed).
     * @return false when launcher_profiles.json could not be written
     */
    public static boolean save(@NonNull Context context, @NonNull String rendererId) {
        try {
            ElymonProfile.ensure(context, null);
            MinecraftProfile profile = LauncherProfiles.mainProfileJson.profiles.get(ElymonConfig.PROFILE_UUID);
            if (profile == null) {
                return false;
            }
            profile.pojavRendererName = rendererId;
            LauncherProfiles.write();
            Log.i(TAG, "Moteur de rendu : " + rendererId);
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Impossible d'enregistrer le moteur de rendu", e);
            return false;
        }
    }
}
