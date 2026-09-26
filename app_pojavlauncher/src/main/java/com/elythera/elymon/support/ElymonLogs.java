package com.elythera.elymon.support;

import android.app.Activity;

/**
 * Support logs (release package): a zip of the launcher and game logs with every
 * secret removed, handed to the Android share sheet. Stub committed before wave 2
 * so the ui package can call it.
 */
public final class ElymonLogs {
    private ElymonLogs() {}

    /** Builds the redacted zip off the UI thread, then opens the share sheet. UI thread. */
    public static void share(Activity activity) {}
}
