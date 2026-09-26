package com.elythera.elymon.ui;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

/**
 * The microphone permission (RECORD_AUDIO, declared in the manifest) for Simple Voice Chat,
 * which Elymon ships. Upstream only offered it deep in the settings, so players never gave
 * it and the mod said "Microphone non disponible".
 *
 * Elymon asks once, with an explanation, before the first Play, and the settings show the
 * state with a way back: a new request, or Android's app settings once the system stopped
 * showing the request (refused twice, or "Ne plus demander").
 */
public final class ElymonMicrophone {
    private static final String TAG = "ElymonMicrophone";
    /** The explanation before the first Play was shown (whatever the answer). */
    private static final String PREF_OFFERED = "elymon_microphone_offered";
    /** The system request was shown at least once, so a missing rationale means "refused for good". */
    private static final String PREF_REQUESTED = "elymon_microphone_requested";

    public enum State { GRANTED, CAN_ASK, DENIED_FOR_GOOD }

    /** Starts the system request; the host activity calls back through the given runnable. */
    public interface Requester {
        void requestMicrophonePermission(@NonNull Runnable onAnswer);
    }

    private ElymonMicrophone() {}

    public static boolean isGranted(@NonNull Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @NonNull
    public static State state(@NonNull Activity activity) {
        if (isGranted(activity)) {
            return State.GRANTED;
        }
        boolean requested = preferences(activity).getBoolean(PREF_REQUESTED, false);
        boolean rationale = ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO);
        return requested && !rationale ? State.DENIED_FOR_GOOD : State.CAN_ASK;
    }

    /** To call right before launching the system request. */
    public static void markRequested(@NonNull Context context) {
        preferences(context).edit().putBoolean(PREF_REQUESTED, true).apply();
    }

    /** Whether Play should first explain voice chat and offer the microphone (once per install). */
    public static boolean shouldOfferBeforePlay(@NonNull Activity activity) {
        return !isGranted(activity) && !preferences(activity).getBoolean(PREF_OFFERED, false);
    }

    /**
     * The one-time explanation before the first Play. Whatever the answer, then runs
     * {@code thenPlay} (after the system request when the player accepts).
     */
    public static void offerBeforePlay(@NonNull Activity activity, @NonNull Requester requester, @NonNull Runnable thenPlay) {
        preferences(activity).edit().putBoolean(PREF_OFFERED, true).apply();
        new AlertDialog.Builder(activity)
                .setTitle(R.string.elymon_mic_prompt_title)
                .setMessage(R.string.elymon_mic_prompt_message)
                .setCancelable(false)
                .setPositiveButton(R.string.elymon_mic_prompt_allow, (dialog, which) ->
                        requester.requestMicrophonePermission(thenPlay))
                .setNegativeButton(R.string.elymon_mic_prompt_later, (dialog, which) -> thenPlay.run())
                .show();
    }

    /** Android's page for this app, where a refused permission can still be given. */
    public static void openAppSettings(@NonNull Activity activity) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.getPackageName(), null));
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "Réglages de l'application introuvables", e);
        }
    }

    private static SharedPreferences preferences(Context context) {
        if (LauncherPreferences.DEFAULT_PREF != null) {
            return LauncherPreferences.DEFAULT_PREF;
        }
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }
}
