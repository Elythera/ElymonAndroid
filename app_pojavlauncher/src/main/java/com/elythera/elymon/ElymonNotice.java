package com.elythera.elymon;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.ShowErrorActivity;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.lifecycle.ContextExecutorTask;

import java.util.Locale;

/**
 * A French message for the player, with an optional web page and optional details to copy.
 *
 * It is a Throwable implementing ContextExecutorTask so it can travel through
 * Tools.showErrorRemote: shown at once on the launcher screen, or behind a notification
 * that opens ShowErrorActivity when no launcher screen is in the foreground (the sync
 * keeps running in the background through ProgressService).
 */
public final class ElymonNotice extends Exception implements ContextExecutorTask {
    private static final String TAG = "ElymonNotice";
    private static final long serialVersionUID = 1L;

    private final String mTitle;
    private final String mMessage;
    private final String mUrl;
    private final String mDetails;

    public ElymonNotice(@NonNull String title, @NonNull String message, @Nullable String url, @Nullable String details) {
        super(message);
        mTitle = title;
        mMessage = message;
        mUrl = isWebUrl(url) ? url.trim() : null;
        mDetails = details;
    }

    /** Shows the notice from any thread. */
    public void show() {
        Tools.showErrorRemote(null, this);
    }

    @Override
    public void executeWithActivity(Activity activity) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(mTitle)
                .setMessage(mMessage)
                .setPositiveButton(R.string.elymon_ok, null);
        if (mUrl != null) {
            builder.setNeutralButton(R.string.elymon_open_page, (dialog, which) -> openUrl(activity, mUrl));
        }
        if (mDetails != null) {
            builder.setNegativeButton(R.string.elymon_copy_details, (dialog, which) -> copyDetails(activity, mDetails));
        }
        if (activity instanceof ShowErrorActivity) {
            // Opened from the error notification: nothing else to show behind the dialog.
            builder.setOnDismissListener(dialog -> activity.finish());
        }
        try {
            builder.show();
        } catch (RuntimeException e) {
            Log.w(TAG, "Impossible d'afficher le message", e);
        }
    }

    @Override
    public void executeWithApplication(Context context) {
        Toast.makeText(context, mMessage, Toast.LENGTH_LONG).show();
    }

    private static void openUrl(Activity activity, String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, url, Toast.LENGTH_LONG).show();
        }
    }

    private static void copyDetails(Activity activity, String details) {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("Elymon", details));
        Toast.makeText(activity, R.string.elymon_details_copied, Toast.LENGTH_SHORT).show();
    }

    /** Only http(s) links from the distribution are offered; anything else is dropped. */
    private static boolean isWebUrl(String url) {
        if (url == null) return false;
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }
}
