package com.elythera.elymon.ui;

import android.content.Context;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.elythera.elymon.ElymonConfig;

import net.kdt.pojavlaunch.BuildConfig;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;

import java.io.File;

/**
 * The discreet line under Play: the app version, and the pack version from the
 * distribution the last sync cached (&lt;filesDir&gt;/elymon/distribution.json, see
 * ElymonLaunch.syncOptions) once there is one.
 */
public final class ElymonHomeStatus {
    private ElymonHomeStatus() {}

    /** UI thread. Shows the app version at once, then the pack version when the cache has one. */
    public static void show(@Nullable TextView view) {
        if (view == null) {
            return;
        }
        final Context context = view.getContext().getApplicationContext();
        final String appVersion = BuildConfig.VERSION_NAME;
        view.setText(context.getString(R.string.elymon_home_status_app, appVersion));
        final File distribution = new File(new File(context.getFilesDir(), "elymon"), "distribution.json");
        PojavApplication.sExecutorService.execute(() -> {
            ElymonPackInfo info = ElymonPackInfo.read(distribution, ElymonConfig.SERVER_ID);
            if (info == null) {
                return;
            }
            final String text = info.minecraftVersion != null
                    ? context.getString(R.string.elymon_home_status_full, info.packVersion, info.minecraftVersion, appVersion)
                    : context.getString(R.string.elymon_home_status_pack, info.packVersion, appVersion);
            // On the UI thread; a view that left the screen in the meantime just keeps the text.
            view.post(() -> view.setText(text));
        });
    }
}
