package com.elythera.elymon.update;

import android.app.Activity;

/**
 * Self-update of the Elymon APK from the Elythera CDN (release package).
 * Stub committed before wave 2 so the ui package can call it; the release
 * package implements it without changing these signatures.
 */
public final class ElymonUpdater {
    private ElymonUpdater() {}

    /** Quiet check when the launcher opens: only speaks up when an update exists. UI thread. */
    public static void checkOnStartup(Activity activity) {}

    /** Check asked by the player from the settings: always reports the outcome. UI thread. */
    public static void checkNow(Activity activity) {}
}
