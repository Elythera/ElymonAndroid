package com.elythera.elymon.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.elythera.elymon.ElymonConfig;
import com.elythera.elymon.ElymonMemory;
import com.elythera.elymon.support.ElymonLogs;
import com.elythera.elymon.update.ElymonUpdater;

import net.kdt.pojavlaunch.BuildConfig;
import net.kdt.pojavlaunch.CustomControlsActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.CustomSeekBarPreference;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;
import java.util.List;

/**
 * The "Elymon" section at the top of the settings (pref_main.xml), and the upstream
 * entries Elymon does not use, hidden with setVisible(false): their keys must stay in the
 * XML because upstream code looks them up with requirePreference().
 *
 * Also locks the values of the hidden upstream settings (applyLocks), so that nothing a
 * previous build let the player change keeps applying.
 */
public final class ElymonSettings {
    private static final String TAG = "ElymonSettings";
    private static final String KEY_CATEGORY = "elymon_category";
    private static final String KEY_RENDERER = "elymon_renderer";
    private static final String KEY_MEMORY = "allocation";
    private static final String KEY_MICROPHONE = "elymon_microphone";
    private static final String KEY_CONTROLS = "elymon_controls";
    private static final String KEY_GAME_FOLDER = "elymon_game_folder";
    private static final String KEY_SEND_LOGS = "elymon_send_logs";
    private static final String KEY_CHECK_UPDATES = "elymon_check_updates";

    /** Upstream entries of pref_main.xml hidden in Elymon. */
    private static final String[] HIDDEN_MAIN_KEYS = {
            "java_screen_setting",          // RAM moved to the Elymon section; Java 21 is installed by Play
            "misc_screen_setting",          // download mirror, capes, checks: locked in applyLocks()
            "experimental_screen_setting",  // shader dumps, Sodium override: not for players
            "force_english",                // Elymon is French
            "microphone_permission_request" // replaced by the Elymon microphone entry
    };

    private ElymonSettings() {}

    /**
     * Binds the Elymon section of the main settings screen. UI thread, after
     * addPreferencesFromResource(R.xml.pref_main).
     *
     * @param ramAllocation LauncherPreferences.PREF_RAM_ALLOCATION read before inflating
     */
    public static void bindMain(@NonNull PreferenceFragmentCompat fragment, int ramAllocation) {
        if (fragment.findPreference(KEY_CATEGORY) == null) {
            return;
        }
        for (String key : HIDDEN_MAIN_KEYS) {
            Preference preference = fragment.findPreference(key);
            if (preference != null) {
                preference.setVisible(false);
            }
        }
        Context context = fragment.requireContext();
        bindRenderer(fragment, context);
        bindMemory(fragment, context, ramAllocation);
        bindMicrophone(fragment);

        onClick(fragment, KEY_CONTROLS, activity ->
                activity.startActivity(new Intent(activity, CustomControlsActivity.class)));
        onClick(fragment, KEY_GAME_FOLDER, ElymonSettings::openGameFolder);
        onClick(fragment, KEY_SEND_LOGS, ElymonLogs::share);
        onClick(fragment, KEY_CHECK_UPDATES, ElymonUpdater::checkNow);
        Preference updates = fragment.findPreference(KEY_CHECK_UPDATES);
        if (updates != null) {
            updates.setSummary(context.getString(R.string.elymon_settings_update_summary, BuildConfig.VERSION_NAME));
        }
    }

    /** From the settings screens' onResume: states that may have changed outside (Android's app settings). */
    public static void refresh(@NonNull PreferenceFragmentCompat fragment) {
        if (fragment.isAdded() && fragment.findPreference(KEY_MICROPHONE) != null) {
            updateMicrophone(fragment);
        }
    }

    /**
     * Values of the settings Elymon hides, forced whatever a previous build stored.
     * Called by LauncherPreferences.loadPreferences, in every process.
     */
    public static void applyLocks() {
        LauncherPreferences.PREF_FORCE_ENGLISH = false;
        // LocaleUtils reads the stored switch itself when the app starts, before these locks,
        // and wave-1 builds still showed it: forget a stored "true" so French comes back.
        SharedPreferences stored = LauncherPreferences.DEFAULT_PREF;
        if (stored != null && stored.getBoolean("force_english", false)) {
            stored.edit().remove("force_english").apply();
        }
        LauncherPreferences.PREF_CHECK_LIBRARY_SHA = true;
        LauncherPreferences.PREF_VERIFY_MANIFEST = true;
        LauncherPreferences.PREF_DOWNLOAD_SOURCE = "default";
        LauncherPreferences.PREF_ARC_CAPES = false;
        LauncherPreferences.PREF_DUMP_SHADERS = false;
        LauncherPreferences.PREF_BIG_CORE_AFFINITY = false;
        LauncherPreferences.PREF_GAMEPAD_FORCEDSDL_PASSTHRU = false;
        LauncherPreferences.PREF_FORCE_ENABLE_TOUCHCONTROLLER = false;
        LauncherPreferences.PREF_CUSTOM_JAVA_ARGS = "";
    }

    private static void bindRenderer(PreferenceFragmentCompat fragment, Context context) {
        ListPreference renderer = fragment.findPreference(KEY_RENDERER);
        if (renderer == null) {
            return;
        }
        List<String> ids = ElymonRenderer.available(context);
        final String[] values = ids.toArray(new String[0]);
        final String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = context.getString(ElymonRenderer.KOPPER_ZINK.equals(values[i])
                    ? R.string.elymon_settings_renderer_zink : R.string.elymon_settings_renderer_mobileglues);
        }
        renderer.setEntries(names);
        renderer.setEntryValues(values);
        renderer.setValue(ElymonRenderer.current(context));
        if (values.length < 2) {
            renderer.setEnabled(false);
            renderer.setSummary(context.getString(R.string.elymon_settings_renderer_only, names[0]));
            return;
        }
        renderer.setSummary(rendererSummary(context, renderer.getEntry()));
        renderer.setOnPreferenceChangeListener((preference, newValue) -> {
            String id = String.valueOf(newValue);
            if (!ElymonRenderer.save(preference.getContext(), id)) {
                Toast.makeText(preference.getContext(), R.string.elymon_settings_renderer_error, Toast.LENGTH_LONG).show();
                return false;
            }
            int index = renderer.findIndexOfValue(id);
            preference.setSummary(rendererSummary(preference.getContext(), index >= 0 ? names[index] : id));
            return true;
        });
    }

    private static String rendererSummary(Context context, CharSequence name) {
        return context.getString(R.string.elymon_settings_renderer_summary, name);
    }

    private static void bindMemory(PreferenceFragmentCompat fragment, Context context, int ramAllocation) {
        Preference preference = fragment.findPreference(KEY_MEMORY);
        if (!(preference instanceof CustomSeekBarPreference)) {
            return;
        }
        CustomSeekBarPreference memory = (CustomSeekBarPreference) preference;
        int totalMb = Tools.getTotalDeviceMemory(context);
        // Upstream's ceiling (LauncherPreferenceJavaFragment): leave Android at least 1 GB.
        memory.setMaxKeepIncrement(Math.max(totalMb - 1024, ElymonMemory.defaultHeapMb(totalMb)));
        memory.setSuffix(context.getString(R.string.elymon_settings_memory_unit));
        SharedPreferences stored = memory.getSharedPreferences();
        if (stored != null && stored.contains(KEY_MEMORY)) {
            // The player chose a value once. Inflating pref_main.xml already clamped it to the
            // slider's XML maximum, which SeekBarPreference raises to the minimum (1024), and
            // SAVED that: upstream's "triggers a write" trap. Save the player's value again, or
            // the next game would start with a 1024 MB heap.
            memory.setValue(ramAllocation);
        } else {
            // Shown without being saved: "allocation" stays unset until the player moves the
            // slider, so the default keeps following ElymonMemory's tiers.
            memory.setPersistent(false);
            memory.setValue(ramAllocation);
            memory.setPersistent(true);
        }
        memory.setSummary(context.getString(R.string.elymon_settings_memory_summary,
                ElymonMemory.defaultHeapMb(totalMb), ElymonMemory.maxSafeHeapMb(totalMb)));
    }

    private static void bindMicrophone(PreferenceFragmentCompat fragment) {
        Preference microphone = fragment.findPreference(KEY_MICROPHONE);
        if (microphone == null) {
            return;
        }
        updateMicrophone(fragment);
        microphone.setOnPreferenceClickListener(preference -> {
            Activity activity = fragment.getActivity();
            if (activity == null) {
                return true;
            }
            switch (ElymonMicrophone.state(activity)) {
                case GRANTED:
                    Toast.makeText(activity, R.string.elymon_settings_mic_already, Toast.LENGTH_SHORT).show();
                    break;
                case DENIED_FOR_GOOD:
                    ElymonMicrophone.openAppSettings(activity);
                    break;
                default:
                    if (activity instanceof ElymonMicrophone.Requester) {
                        ((ElymonMicrophone.Requester) activity).requestMicrophonePermission(() -> refresh(fragment));
                    }
                    break;
            }
            return true;
        });
    }

    private static void updateMicrophone(PreferenceFragmentCompat fragment) {
        Preference microphone = fragment.findPreference(KEY_MICROPHONE);
        Activity activity = fragment.getActivity();
        if (microphone == null || activity == null) {
            return;
        }
        switch (ElymonMicrophone.state(activity)) {
            case GRANTED:
                microphone.setSummary(R.string.elymon_settings_mic_granted);
                break;
            case DENIED_FOR_GOOD:
                microphone.setSummary(R.string.elymon_settings_mic_denied);
                break;
            default:
                microphone.setSummary(R.string.elymon_settings_mic_ask);
                break;
        }
    }

    private static void openGameFolder(Activity activity) {
        File instance = new File(Tools.DIR_GAME_HOME, ElymonConfig.INSTANCE_REL);
        if (!instance.isDirectory()) {
            Toast.makeText(activity, R.string.elymon_settings_folder_missing, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Tools.openPath(activity, instance, false);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "Aucune application pour ouvrir le dossier du jeu", e);
            Toast.makeText(activity, R.string.elymon_settings_folder_no_app, Toast.LENGTH_LONG).show();
        }
    }

    private interface ActivityAction {
        void run(Activity activity);
    }

    private static void onClick(PreferenceFragmentCompat fragment, String key, ActivityAction action) {
        Preference preference = fragment.findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setOnPreferenceClickListener(p -> {
            Activity activity = fragment.getActivity();
            if (activity != null) {
                action.run(activity);
            }
            return true;
        });
    }
}
