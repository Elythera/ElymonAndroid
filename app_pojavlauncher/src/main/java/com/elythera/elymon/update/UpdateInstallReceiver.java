package com.elythera.elymon.update;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;
import android.widget.Toast;

import com.elythera.elymon.ElymonNotice;

import net.kdt.pojavlaunch.R;

/**
 * Outcome of the PackageInstaller session UpdateInstaller committed (not exported: only our
 * own PendingIntent reaches it).
 */
public final class UpdateInstallReceiver extends BroadcastReceiver {
    private static final String TAG = "ElymonUpdate";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !UpdateInstaller.ACTION_STATUS.equals(intent.getAction())) {
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm == null) {
                    fail(context, intent, status, R.string.elymon_update_install_failed);
                    return;
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                    Toast.makeText(context, R.string.elymon_update_install_confirm, Toast.LENGTH_SHORT).show();
                } catch (ActivityNotFoundException | SecurityException e) {
                    Log.w(TAG, "Cannot open the install confirmation", e);
                    fail(context, intent, status, R.string.elymon_update_install_failed);
                }
                return;
            }
            case PackageInstaller.STATUS_SUCCESS:
                // Android replaces the app and stops this process right after.
                Log.i(TAG, "Update installed");
                return;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                Toast.makeText(context, R.string.elymon_update_install_cancelled, Toast.LENGTH_SHORT).show();
                return;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                fail(context, intent, status, R.string.elymon_update_install_storage);
                return;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                fail(context, intent, status, R.string.elymon_update_install_conflict);
                return;
            default:
                fail(context, intent, status, R.string.elymon_update_install_failed);
        }
    }

    private static void fail(Context context, Intent intent, int status, int messageRes) {
        // The status message is the installer's English diagnostic: no secret in it.
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String details = "PackageInstaller status " + status + (message == null ? "" : ": " + message);
        Log.w(TAG, "Install failed: " + details);
        try {
            new ElymonNotice(context.getString(R.string.elymon_update_failed_title), context.getString(messageRes),
                    null, details).show();
        } catch (RuntimeException e) {
            Toast.makeText(context, messageRes, Toast.LENGTH_LONG).show();
        }
    }
}
