package com.elythera.elymon.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.elythera.elymon.ElymonMemory;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.lifecycle.LifecycleAwareAlertDialog;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

/**
 * The memory warning shown just before the game starts (Tools.launchMinecraft, :game process).
 *
 * Upstream compared the heap with the RAM free at that moment and warned at every Play on
 * every phone: Android keeps background apps in memory and closes them when a foreground
 * app needs it, so "free" RAM is always low. Elymon warns only when the heap leaves less
 * than ElymonMemory.RESERVED_FOR_ANDROID_MB of the phone's total RAM, and "Ne plus afficher"
 * silences it for that heap size (it comes back if the player raises the heap further).
 */
public final class ElymonMemoryWarning {
    private static final String TAG = "ElymonMemory";
    /** Largest heap (MB) the player accepted with "Ne plus afficher". */
    private static final String PREF_ACCEPTED_MB = "elymon_memory_warning_accepted_mb";

    private ElymonMemoryWarning() {}

    /**
     * Shows the warning when it applies and waits for the player. Worker thread.
     *
     * @return true when the activity's lifecycle ended while the warning was shown: the
     *         caller must then stop, exactly as upstream's warning required
     */
    public static boolean haltOnWarning(@NonNull AppCompatActivity activity) throws InterruptedException {
        final int totalMb = Tools.getTotalDeviceMemory(activity);
        final int allocationMb = LauncherPreferences.PREF_RAM_ALLOCATION;
        Log.i(TAG, "RAM totale " + totalMb + " Mo, tas Java " + allocationMb + " Mo");
        if (!ElymonMemory.exceedsSafeHeap(allocationMb, totalMb)) {
            return false;
        }
        final SharedPreferences preferences = preferences(activity);
        if (allocationMb <= preferences.getInt(PREF_ACCEPTED_MB, 0)) {
            return false;
        }
        final int safeMb = ElymonMemory.maxSafeHeapMb(totalMb);
        return LifecycleAwareAlertDialog.haltOnDialog(activity.getLifecycle(), activity, (dialog, builder) ->
                builder.setTitle(R.string.elymon_memory_warning_title)
                        .setMessage(activity.getString(R.string.elymon_memory_warning_message, allocationMb, totalMb, safeMb))
                        .setPositiveButton(R.string.elymon_memory_warning_play, null)
                        .setNeutralButton(R.string.elymon_memory_warning_never, (d, which) ->
                                preferences.edit().putInt(PREF_ACCEPTED_MB, allocationMb).apply()));
    }

    private static SharedPreferences preferences(Context context) {
        if (LauncherPreferences.DEFAULT_PREF != null) {
            return LauncherPreferences.DEFAULT_PREF;
        }
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }
}
