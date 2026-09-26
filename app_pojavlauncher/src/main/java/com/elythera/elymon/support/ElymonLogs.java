package com.elythera.elymon.support;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.StatFs;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.elythera.elymon.ElymonConfig;
import com.elythera.elymon.ElymonLaunch;

import net.kdt.pojavlaunch.BuildConfig;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.GLInfoUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftLauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Support logs (release package): a zip of the launcher and game logs with every
 * secret removed, handed to the Android share sheet. Stub committed before wave 2
 * so the ui package can call it.
 *
 * The zip holds, each file redacted line by line by {@link LogRedactor}:
 * <ul>
 * <li>launcher/latestlog.txt: the launcher's log of the last launch;</li>
 * <li>launcher/latestcrash.txt: the launcher's own last crash, if any;</li>
 * <li>jeu/latest.log and jeu/debug.log: the game's logs, their last 5 MB;</li>
 * <li>jeu/crash-reports/&lt;newest&gt;: the newest Minecraft crash report;</li>
 * <li>jeu/&lt;newest hs_err_pid*.log&gt;: the newest JVM crash file (its command line holds
 * the access token, which the redaction removes);</li>
 * <li>infos.txt: app, device, Android, RAM, GPU, pack version, heap. No account data.</li>
 * </ul>
 * It is written to cache/elymon-logs/ (older zips are deleted first) and shared through
 * {@link ElymonLogsProvider}, whose paths are limited to that folder.
 */
public final class ElymonLogs {
    private static final String TAG = "ElymonLogs";
    /** Sub-folder of getCacheDir(); must match res/xml/elymon_logs_paths.xml. */
    static final String CACHE_DIR = "elymon-logs";
    private static final long GAME_LOG_MAX_BYTES = 5L * 1024 * 1024;
    private static final long LAUNCHER_LOG_MAX_BYTES = 5L * 1024 * 1024;
    private static final long CRASH_MAX_BYTES = 2L * 1024 * 1024;

    private static final AtomicBoolean sBusy = new AtomicBoolean(false);

    private ElymonLogs() {}

    /** Builds the redacted zip off the UI thread, then opens the share sheet. UI thread. */
    public static void share(Activity activity) {
        if (activity == null) {
            return;
        }
        final Context app = activity.getApplicationContext();
        Toast.makeText(app, R.string.elymon_logs_preparing, Toast.LENGTH_SHORT).show();
        if (!sBusy.compareAndSet(false, true)) {
            return;
        }
        final WeakReference<Activity> activityRef = new WeakReference<>(activity);
        new Thread(() -> {
            File zip = null;
            try {
                zip = build(app);
            } catch (Throwable t) {
                // Class only: a message could carry a path.
                Log.w(TAG, "Support zip failed: " + t.getClass().getSimpleName());
            } finally {
                sBusy.set(false);
            }
            final File result = zip;
            Tools.runOnUiThread(() -> deliver(app, activityRef.get(), result));
        }, "ElymonLogs").start();
    }

    /**
     * For the game process's exit dialog, whose thread kills the process right after: builds
     * the zip on the calling thread (never the UI thread) and returns once the share sheet has
     * been started on the UI thread.
     */
    public static void shareBlocking(Activity activity) {
        if (activity == null) {
            return;
        }
        final Context app = activity.getApplicationContext();
        File zip = null;
        try {
            zip = build(app);
        } catch (Throwable t) {
            Log.w(TAG, "Support zip failed: " + t.getClass().getSimpleName());
        }
        final File result = zip;
        final CountDownLatch started = new CountDownLatch(1);
        Tools.runOnUiThread(() -> {
            try {
                deliver(app, activity, result);
            } finally {
                started.countDown();
            }
        });
        try {
            started.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Writes the zip and returns it. Worker thread. */
    static File build(Context context) throws IOException {
        File dir = new File(context.getCacheDir(), CACHE_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create the logs folder");
        }
        File[] old = dir.listFiles();
        if (old != null) {
            for (File file : old) {
                if (!file.delete()) {
                    file.deleteOnExit();
                }
            }
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        File zip = new File(dir, "elymon-journaux-" + stamp + ".zip");

        File gameHome = new File(Tools.DIR_GAME_HOME);
        File instance = new File(gameHome, ElymonConfig.INSTANCE_REL);
        File logs = new File(instance, "logs");
        List<LogBundle.Entry> entries = new ArrayList<>();
        entries.add(new LogBundle.Entry("launcher/latestlog.txt", new File(gameHome, "latestlog.txt"), LAUNCHER_LOG_MAX_BYTES));
        // PojavApplication writes it to DIR_GAME_HOME, or to DIR_DATA when storage was not ready.
        File launcherCrash = new File(gameHome, "latestcrash.txt");
        if (!launcherCrash.isFile() && Tools.DIR_DATA != null) {
            launcherCrash = new File(Tools.DIR_DATA, "latestcrash.txt");
        }
        if (launcherCrash.isFile()) {
            entries.add(new LogBundle.Entry("launcher/latestcrash.txt", launcherCrash, CRASH_MAX_BYTES));
        }
        entries.add(new LogBundle.Entry("jeu/latest.log", new File(logs, "latest.log"), GAME_LOG_MAX_BYTES));
        entries.add(new LogBundle.Entry("jeu/debug.log", new File(logs, "debug.log"), GAME_LOG_MAX_BYTES));
        File crash = LogBundle.newest(new File(instance, "crash-reports"), "crash-", ".txt");
        if (crash != null) {
            entries.add(new LogBundle.Entry("jeu/crash-reports/" + crash.getName(), crash, CRASH_MAX_BYTES));
        }
        File jvmCrash = LogBundle.newest(instance, "hs_err_pid", ".log");
        if (jvmCrash != null) {
            entries.add(new LogBundle.Entry("jeu/" + jvmCrash.getName(), jvmCrash, CRASH_MAX_BYTES));
        }
        LogBundle.write(zip, entries, infos(context, gameHome, crash, jvmCrash));
        return zip;
    }

    /** infos.txt: the device and the build, never the account. */
    static String infos(Context context, File gameHome, File crash, File jvmCrash) {
        StringBuilder out = new StringBuilder(1024);
        line(out, "Journaux Elymon", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(new Date()));
        line(out, "Application", context.getPackageName() + " " + BuildConfig.VERSION_NAME
                + " (versionCode " + BuildConfig.VERSION_CODE + ", " + BuildConfig.BUILD_TYPE + ")");
        line(out, "Annoncé à ElytheraMod", ElymonConfig.LAUNCHER_BRAND + " " + ElymonConfig.launcherVersion(BuildConfig.VERSION_CODE)
                + ", profil " + ElymonConfig.SERVER_ID);
        line(out, "Clé Elythera compilée", BuildConfig.ELYTHERA_KEY_PRESENT ? "présente" : "absente");
        line(out, "Appareil", Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")");
        line(out, "Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        line(out, "Architectures", join(Build.SUPPORTED_ABIS));
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memory);
            line(out, "Mémoire vive", mib(memory.totalMem) + " au total, " + mib(memory.availMem) + " disponibles");
            try {
                int gles = activityManager.getDeviceConfigurationInfo().reqGlEsVersion;
                line(out, "OpenGL ES déclaré", (gles >>> 16) + "." + (gles & 0xffff));
            } catch (RuntimeException ignored) {
                // Not worth failing the zip for.
            }
        }
        try {
            GLInfoUtils.GLInfo gl = GLInfoUtils.getGlInfo();
            line(out, "GPU", gl.vendor + " " + gl.renderer + " (contexte OpenGL ES " + gl.glesMajorVersion + ")");
        } catch (Throwable t) {
            line(out, "GPU", "inconnu");
        }
        MinecraftProfile profile = elymonProfile();
        line(out, "Rendu", profile == null || profile.pojavRendererName == null ? "inconnu" : profile.pojavRendererName);
        line(out, "Mémoire allouée au jeu", LauncherPreferences.PREF_RAM_ALLOCATION + " Mo");
        SharedPreferences state = context.getSharedPreferences(ElymonLaunch.STATE_PREFS, Context.MODE_PRIVATE);
        line(out, "Version du pack", state.getString(ElymonLaunch.STATE_PACK_VERSION, "inconnue"));
        long syncedAt = state.getLong(ElymonLaunch.STATE_PACK_SYNCED_AT, 0);
        if (syncedAt > 0) {
            line(out, "Dernière mise à jour du pack", new SimpleDateFormat("yyyy-MM-dd HH:mm Z", Locale.ROOT).format(new Date(syncedAt)));
        }
        try {
            line(out, "Espace libre", mib(new StatFs(gameHome.getAbsolutePath()).getAvailableBytes()));
        } catch (RuntimeException ignored) {
            // The game folder may not exist yet.
        }
        String[] modFiles = new File(new File(gameHome, ElymonConfig.INSTANCE_REL), "mods").list();
        line(out, "Mods installés", String.valueOf(modFiles == null ? 0 : countJars(modFiles)));
        line(out, "Rapport de crash le plus récent", crash == null ? "aucun" : crash.getName());
        line(out, "Crash de la JVM le plus récent", jvmCrash == null ? "aucun" : jvmCrash.getName());
        return out.toString();
    }

    /** The Elymon profile as last loaded by the launcher; never reloads (that would race the UI). */
    private static MinecraftProfile elymonProfile() {
        try {
            MinecraftLauncherProfiles profiles = LauncherProfiles.mainProfileJson;
            return profiles == null || profiles.profiles == null ? null : profiles.profiles.get(ElymonConfig.PROFILE_UUID);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void deliver(Context app, Activity activity, File zip) {
        if (zip == null) {
            Toast.makeText(app, R.string.elymon_logs_failed, Toast.LENGTH_LONG).show();
            return;
        }
        Context context = activity != null && !activity.isFinishing() && !activity.isDestroyed() ? activity : app;
        Uri uri;
        try {
            uri = FileProvider.getUriForFile(context, authority(context), zip);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "The logs provider does not cover the zip");
            Toast.makeText(app, R.string.elymon_logs_failed, Toast.LENGTH_LONG).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.elymon_logs_subject))
                .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.elymon_logs_text, BuildConfig.VERSION_NAME))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // The chooser forwards the read grant only when the URI is also in the ClipData.
        send.setClipData(ClipData.newRawUri(zip.getName(), uri));
        Intent chooser = Intent.createChooser(send, context.getString(R.string.elymon_logs_chooser))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!(context instanceof Activity)) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            context.startActivity(chooser);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(app, R.string.elymon_logs_no_app, Toast.LENGTH_LONG).show();
        }
    }

    /** "${applicationId}.elymon.logs", as declared in the manifest. */
    static String authority(Context context) {
        return context.getPackageName() + ".elymon.logs";
    }

    private static void line(StringBuilder out, String label, String value) {
        out.append(label).append(" : ").append(value).append('\n');
    }

    private static String mib(long bytes) {
        return String.format(Locale.FRANCE, "%.0f Mo", bytes / (1024.0 * 1024.0));
    }

    private static String join(String[] values) {
        StringBuilder out = new StringBuilder();
        if (values != null) {
            for (String value : values) {
                if (out.length() > 0) out.append(", ");
                out.append(value);
            }
        }
        return out.toString();
    }

    private static int countJars(String[] names) {
        int count = 0;
        for (String name : names) {
            if (name.endsWith(".jar")) count++;
        }
        return count;
    }
}
