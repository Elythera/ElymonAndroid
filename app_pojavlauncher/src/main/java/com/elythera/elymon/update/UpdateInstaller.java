package com.elythera.elymon.update;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Hands a verified APK to Android through a PackageInstaller session. The outcome comes back
 * to {@link UpdateInstallReceiver}: STATUS_PENDING_USER_ACTION there starts the system's
 * confirmation screen (always shown for a sideloaded app), then Android replaces the app and
 * stops every process of it, the game's included.
 */
final class UpdateInstaller {
    static final String ACTION_STATUS = "com.elythera.elymon.update.INSTALL_STATUS";

    private UpdateInstaller() {}

    /** Whether Android lets this app install packages ("Installer des applis inconnues"). */
    static boolean canInstall(Context context) {
        return context.getPackageManager().canRequestPackageInstalls();
    }

    /** Writes the APK into a new session and commits it. Worker thread. */
    static void install(Context context, File apk) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(apk.length());
        int sessionId = installer.createSession(params);
        boolean committed = false;
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("elymon.apk", 0, apk.length())) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                session.fsync(out);
            }
            Intent status = new Intent(context, UpdateInstallReceiver.class).setAction(ACTION_STATUS);
            // Mutable: the installer fills in the status extras. The intent is explicit (our own
            // receiver, not exported), which Android 14 requires of a mutable PendingIntent.
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, status, flags);
            session.commit(pending.getIntentSender());
            committed = true;
        } finally {
            if (!committed) {
                try {
                    installer.abandonSession(sessionId);
                } catch (RuntimeException ignored) {
                    // Already gone.
                }
            }
        }
    }
}
