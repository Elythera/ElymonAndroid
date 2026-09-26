package com.elythera.elymon.update;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

import com.elythera.elymon.ElymonLaunch;
import com.elythera.elymon.ElymonNotice;

import net.kdt.pojavlaunch.BuildConfig;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.lifecycle.ContextExecutor;
import net.kdt.pojavlaunch.lifecycle.ContextExecutorTask;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Self-update of the Elymon APK from the Elythera CDN (release package).
 * Stub committed before wave 2 so the ui package can call it; the release
 * package implements it without changing these signatures.
 *
 * Flow: latest.json ({@link UpdateManifest}, format in docs/elymon/RELEASE.md) → French
 * dialog with the notes and the size → download to cache/elymon-update/ with a progress
 * dialog → size and SHA-256 ({@link UpdateHttp}) → same package, newer, same signing
 * certificate ({@link ApkVerifier}) → PackageInstaller session ({@link UpdateInstaller}),
 * whose confirmation {@link UpdateInstallReceiver} opens.
 *
 * A feed whose minVersionCode is above this build makes {@link ElymonLaunch} refuse Play
 * ({@link #requiredUpdate}) and offer the update instead.
 *
 * Debug builds (com.elythera.elymon.debug, signed with the public debug key) read their own
 * feed, latest-debug.json, so a release APK is never offered to them (it could not replace
 * them anyway) and the whole flow can be tried with two debug builds.
 */
public final class ElymonUpdater {
    /** The release feed, published next to the APKs (docs/elymon/RELEASE.md). */
    public static final String MANIFEST_URL = "https://cdn.elythera.com/elylauncher/android/latest.json";
    /** The feed debug builds read. */
    public static final String MANIFEST_URL_DEBUG = "https://cdn.elythera.com/elylauncher/android/latest-debug.json";

    /** checkOnStartup asks the CDN at most this often. */
    static final long STARTUP_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final int FEED_TIMEOUT_MS = 10000;
    /** Play waits at most this long for the feed before trusting the cached copy. */
    private static final int PLAY_FEED_TIMEOUT_MS = 5000;
    private static final int DOWNLOAD_TIMEOUT_MS = 30000;
    private static final long PROGRESS_UI_INTERVAL_MS = 200L;

    private static final String TAG = "ElymonUpdate";
    private static final String PREFS = "elymon_update";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_MANIFEST = "manifest";
    /** Sub-folder of getCacheDir() holding the downloaded APK. */
    private static final String UPDATE_DIR = "elymon-update";

    private static final AtomicBoolean sChecking = new AtomicBoolean(false);
    /** The running download and install; UI thread only. */
    private static Job sJob;
    /** The offer dialog on screen, so a second check does not stack another one; UI thread only. */
    private static WeakReference<AlertDialog> sOfferDialog;

    private ElymonUpdater() {}

    /** Quiet check when the launcher opens: only speaks up when an update exists. UI thread. */
    public static void checkOnStartup(Activity activity) {
        if (activity == null) {
            return;
        }
        if (sJob != null) {
            sJob.attach(activity);
            return;
        }
        final Context app = activity.getApplicationContext();
        SharedPreferences prefs = prefs(app);
        long now = System.currentTimeMillis();
        long last = prefs.getLong(KEY_LAST_CHECK, 0);
        if (last > 0 && now >= last && now - last < STARTUP_INTERVAL_MS) {
            return;
        }
        if (!sChecking.compareAndSet(false, true)) {
            return;
        }
        // Recorded before the request: a failing CDN is not asked again at every start.
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply();
        new Thread(() -> {
            UpdateHttp.FeedResult result;
            try {
                deleteStaleDownloads(app);
                result = fetch(app, FEED_TIMEOUT_MS);
            } finally {
                sChecking.set(false);
            }
            // Quiet: a network error or a feed not published yet (404) says nothing.
            if (result.kind == UpdateHttp.FeedResult.Kind.FOUND && result.manifest.isNewerThan(BuildConfig.VERSION_CODE)) {
                final UpdateManifest manifest = result.manifest;
                onLauncherScreen(current -> offer(current, manifest, manifest.isRequiredFor(BuildConfig.VERSION_CODE)));
            }
        }, "ElymonUpdateCheck").start();
    }

    /** Check asked by the player from the settings: always reports the outcome. UI thread. */
    public static void checkNow(Activity activity) {
        if (activity == null) {
            return;
        }
        if (sJob != null) {
            sJob.attach(activity);
            return;
        }
        final Context app = activity.getApplicationContext();
        Toast.makeText(app, R.string.elymon_update_checking, Toast.LENGTH_SHORT).show();
        if (!sChecking.compareAndSet(false, true)) {
            return;
        }
        prefs(app).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
        new Thread(() -> {
            UpdateHttp.FeedResult result;
            try {
                result = fetch(app, FEED_TIMEOUT_MS);
            } finally {
                sChecking.set(false);
            }
            final UpdateHttp.FeedResult outcome = result;
            onLauncherScreen(current -> report(current, outcome));
        }, "ElymonUpdateCheck").start();
    }

    /**
     * The release this build must install before playing, or null. Blocking (the Play worker
     * thread): asks the feed, and when the network fails trusts the last feed it read, so going
     * offline does not skip a required update. A feed that no longer exists (404) requires nothing.
     */
    @Nullable
    public static UpdateManifest requiredUpdate(Context context) {
        Context app = context.getApplicationContext();
        UpdateHttp.FeedResult result = fetch(app, PLAY_FEED_TIMEOUT_MS);
        UpdateManifest manifest;
        if (result.kind == UpdateHttp.FeedResult.Kind.FOUND) {
            manifest = result.manifest;
        } else if (result.kind == UpdateHttp.FeedResult.Kind.NOT_FOUND) {
            manifest = null;
        } else {
            manifest = cached(app);
        }
        return manifest != null && manifest.isRequiredFor(BuildConfig.VERSION_CODE) ? manifest : null;
    }

    /** Shows the required-update dialog on the launcher screen. Any thread. */
    public static void offerRequiredUpdate(final UpdateManifest manifest) {
        ContextExecutor.execute(new ContextExecutorTask() {
            @Override
            public void executeWithActivity(Activity activity) {
                offer(activity, manifest, true);
            }

            @Override
            public void executeWithApplication(Context context) {
                Toast.makeText(context, context.getString(R.string.elymon_update_mandatory_message,
                        manifest.versionName, BuildConfig.VERSION_NAME,
                        ElymonLaunch.formatSize(context, manifest.size)), Toast.LENGTH_LONG).show();
            }
        });
    }

    // ------------------------------------------------------------ feed

    static String manifestUrl() {
        return BuildConfig.DEBUG ? MANIFEST_URL_DEBUG : MANIFEST_URL;
    }

    private static UpdateHttp.FeedResult fetch(Context app, int timeoutMs) {
        UpdateHttp.FeedResult result = UpdateHttp.fetchFeed(manifestUrl(), "ElymonAndroid/" + BuildConfig.VERSION_NAME, timeoutMs);
        SharedPreferences.Editor editor = prefs(app).edit();
        if (result.kind == UpdateHttp.FeedResult.Kind.FOUND) {
            editor.putString(KEY_MANIFEST, result.json).apply();
        } else if (result.kind == UpdateHttp.FeedResult.Kind.NOT_FOUND) {
            // Withdrawn (rollback) or never published: forget what an older feed said.
            editor.remove(KEY_MANIFEST).apply();
        } else {
            Log.i(TAG, "Update feed unavailable: " + result.error);
        }
        return result;
    }

    @Nullable
    private static UpdateManifest cached(Context app) {
        String json = prefs(app).getString(KEY_MANIFEST, null);
        if (json == null) {
            return null;
        }
        try {
            return UpdateManifest.parse(json);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ dialogs (UI thread)

    private interface ActivityAction {
        void run(Activity activity);
    }

    /** Runs the action on the launcher screen in the foreground, if there is one. */
    private static void onLauncherScreen(final ActivityAction action) {
        ContextExecutor.execute(new ContextExecutorTask() {
            @Override
            public void executeWithActivity(Activity activity) {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    action.run(activity);
                }
            }

            @Override
            public void executeWithApplication(Context context) {
                // No launcher screen: stay quiet, the next start asks again.
            }
        });
    }

    private static void report(Activity activity, UpdateHttp.FeedResult result) {
        if (result.kind == UpdateHttp.FeedResult.Kind.FOUND && result.manifest.isNewerThan(BuildConfig.VERSION_CODE)) {
            offer(activity, result.manifest, result.manifest.isRequiredFor(BuildConfig.VERSION_CODE));
        } else if (result.kind == UpdateHttp.FeedResult.Kind.ERROR) {
            showDialog(activity, new AlertDialog.Builder(activity)
                    .setTitle(R.string.elymon_update_check_failed_title)
                    .setMessage(R.string.elymon_update_check_failed)
                    .setPositiveButton(R.string.elymon_ok, null));
        } else {
            Toast.makeText(activity, activity.getString(R.string.elymon_update_up_to_date, BuildConfig.VERSION_NAME),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** The update dialog: version, size, notes; "Mettre à jour" starts the download. */
    static void offer(final Activity activity, final UpdateManifest manifest, boolean required) {
        AlertDialog shown = sOfferDialog == null ? null : sOfferDialog.get();
        if (shown != null && shown.isShowing()) {
            return;
        }
        String size = ElymonLaunch.formatSize(activity, manifest.size);
        StringBuilder message = new StringBuilder(activity.getString(
                required ? R.string.elymon_update_mandatory_message : R.string.elymon_update_available_message,
                manifest.versionName, BuildConfig.VERSION_NAME, size));
        if (!manifest.notes.isEmpty()) {
            message.append("\n\n").append(activity.getString(R.string.elymon_update_notes))
                    .append('\n').append(manifest.notes);
        }
        AlertDialog dialog = showDialog(activity, new AlertDialog.Builder(activity)
                .setTitle(required ? R.string.elymon_update_mandatory_title : R.string.elymon_update_available_title)
                .setMessage(message.toString())
                .setPositiveButton(R.string.elymon_update_install, (d, w) -> startUpdate(activity, manifest))
                .setNegativeButton(required ? R.string.elymon_update_close : R.string.elymon_update_later, null));
        sOfferDialog = dialog == null ? null : new WeakReference<>(dialog);
    }

    private static void startUpdate(Activity activity, UpdateManifest manifest) {
        Context app = activity.getApplicationContext();
        if (sJob != null) {
            sJob.attach(activity);
            return;
        }
        if (ElymonLaunch.isGameProcessAlive(app)) {
            notice(app, R.string.elymon_update_game_running);
            return;
        }
        if (!UpdateInstaller.canInstall(app)) {
            showDialog(activity, new AlertDialog.Builder(activity)
                    .setTitle(R.string.elymon_update_unknown_sources_title)
                    .setMessage(R.string.elymon_update_unknown_sources_message)
                    .setPositiveButton(R.string.elymon_update_open_settings, (d, w) -> openUnknownSourcesSettings(activity))
                    .setNegativeButton(R.string.elymon_update_cancel, null));
            return;
        }
        sJob = new Job(app, manifest);
        sJob.attach(activity);
        sJob.start();
    }

    private static void openUnknownSourcesSettings(Activity activity) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName()));
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            } catch (ActivityNotFoundException ignored) {
                Toast.makeText(activity, R.string.elymon_update_unknown_sources_title, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Nullable
    private static AlertDialog showDialog(Activity activity, AlertDialog.Builder builder) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return null;
        }
        try {
            return builder.show();
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot show the update dialog", e);
            return null;
        }
    }

    private static void notice(Context app, int messageRes) {
        new ElymonNotice(app.getString(R.string.elymon_update_failed_title), app.getString(messageRes), null, null).show();
    }

    /** Deletes downloaded APKs this build already is (or is newer than). */
    private static void deleteStaleDownloads(Context app) {
        File[] files = new File(app.getCacheDir(), UPDATE_DIR).listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            int code = versionCodeOf(file.getName());
            if (code <= BuildConfig.VERSION_CODE && !file.delete()) {
                file.deleteOnExit();
            }
        }
    }

    /** "Elymon-12.apk" → 12; anything else → 0 (deleted as stale). */
    private static int versionCodeOf(String name) {
        if (!name.startsWith("Elymon-") || !name.endsWith(".apk")) {
            return 0;
        }
        try {
            return Integer.parseInt(name.substring("Elymon-".length(), name.length() - ".apk".length()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------ download and install

    /** One download, verification and install, with its progress dialog. */
    private static final class Job implements UpdateHttp.Progress {
        private static final int PHASE_DOWNLOAD = 0;
        private static final int PHASE_VERIFY = 1;
        private static final int PHASE_INSTALL = 2;

        private final Context mApp;
        private final UpdateManifest mManifest;
        private final AtomicBoolean mCancelled = new AtomicBoolean(false);
        private volatile long mDone;
        private volatile int mPhase = PHASE_DOWNLOAD;
        private long mLastUiUpdate;

        // UI thread only.
        private AlertDialog mDialog;
        private ProgressBar mBar;
        private TextView mText;

        Job(Context app, UpdateManifest manifest) {
            mApp = app;
            mManifest = manifest;
        }

        /** Shows the progress dialog on this screen (again, after a rotation). UI thread. */
        void attach(Activity activity) {
            if (mDialog != null) {
                mDialog.dismiss();
                mDialog = null;
            }
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            float density = activity.getResources().getDisplayMetrics().density;
            int padding = (int) (24 * density);
            LinearLayout layout = new LinearLayout(activity);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(padding, padding / 2, padding, 0);
            mText = new TextView(activity);
            mText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            mBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            mBar.setMax(1000);
            layout.addView(mText);
            layout.addView(mBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            final AlertDialog dialog = showDialog(activity, new AlertDialog.Builder(activity)
                    .setTitle(R.string.elymon_update_downloading_title)
                    .setView(layout)
                    .setCancelable(false)
                    .setNegativeButton(R.string.elymon_update_cancel, (d, w) -> mCancelled.set(true)));
            if (dialog == null) {
                return;
            }
            mDialog = dialog;
            if (activity instanceof LifecycleOwner) {
                final Lifecycle lifecycle = ((LifecycleOwner) activity).getLifecycle();
                lifecycle.addObserver(new LifecycleEventObserver() {
                    @Override
                    public void onStateChanged(LifecycleOwner source, Lifecycle.Event event) {
                        if (event == Lifecycle.Event.ON_DESTROY) {
                            lifecycle.removeObserver(this);
                            // The download goes on; the next screen gets the dialog back.
                            if (mDialog == dialog) {
                                dialog.dismiss();
                                mDialog = null;
                            }
                        }
                    }
                });
            }
            refresh();
        }

        void start() {
            new Thread(this::run, "ElymonUpdateDownload").start();
        }

        @Override
        public void onProgress(long doneBytes, long totalBytes) {
            mDone = doneBytes;
            long now = SystemClock.uptimeMillis();
            if (now - mLastUiUpdate >= PROGRESS_UI_INTERVAL_MS) {
                mLastUiUpdate = now;
                Tools.runOnUiThread(this::refresh);
            }
        }

        @Override
        public boolean isCancelled() {
            return mCancelled.get();
        }

        private void run() {
            Integer problem = null;
            try {
                File dir = new File(mApp.getCacheDir(), UPDATE_DIR);
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new IOException("cannot create the update folder");
                }
                File apk = new File(dir, "Elymon-" + mManifest.versionCode + ".apk");
                if (!UpdateHttp.matches(apk, mManifest.size, mManifest.sha256)) {
                    UpdateHttp.download(mManifest.url, apk, mManifest.size, mManifest.sha256,
                            "ElymonAndroid/" + BuildConfig.VERSION_NAME, DOWNLOAD_TIMEOUT_MS, this);
                }
                mDone = mManifest.size;
                phase(PHASE_VERIFY);
                problem = ApkVerifier.check(mApp, apk, mManifest);
                if (problem != null) {
                    if (!apk.delete()) {
                        apk.deleteOnExit();
                    }
                    Log.w(TAG, "Downloaded APK refused: " + mApp.getResources().getResourceEntryName(problem));
                } else if (isCancelled()) {
                    problem = 0;
                } else {
                    phase(PHASE_INSTALL);
                    UpdateInstaller.install(mApp, apk);
                }
            } catch (UpdateHttp.DownloadException e) {
                Log.w(TAG, "Update download failed: " + e.reason + " " + e.getMessage());
                switch (e.reason) {
                    case CANCELLED:
                        problem = 0;
                        break;
                    case SIZE:
                    case HASH:
                        problem = R.string.elymon_update_corrupt;
                        break;
                    default:
                        problem = R.string.elymon_update_download_failed;
                }
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Update install failed", e);
                problem = R.string.elymon_update_install_failed;
            }
            final Integer outcome = problem;
            Tools.runOnUiThread(() -> finish(outcome));
        }

        private void phase(int phase) {
            mPhase = phase;
            Tools.runOnUiThread(this::refresh);
        }

        /** UI thread. */
        private void refresh() {
            if (mDialog == null || mText == null || mBar == null) {
                return;
            }
            if (mPhase == PHASE_DOWNLOAD) {
                long total = Math.max(1, mManifest.size);
                mBar.setIndeterminate(false);
                mBar.setProgress((int) Math.min(1000, mDone * 1000 / total));
                mText.setText(mApp.getString(R.string.elymon_update_progress,
                        ElymonLaunch.formatSize(mApp, mDone), ElymonLaunch.formatSize(mApp, mManifest.size)));
            } else {
                mBar.setIndeterminate(true);
                mText.setText(R.string.elymon_update_verifying);
                View cancel = mDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                if (cancel != null && mPhase == PHASE_INSTALL) {
                    cancel.setEnabled(false);
                }
            }
        }

        /** UI thread. problem: null when the installer took over, 0 when cancelled, else a message. */
        private void finish(Integer problem) {
            if (mDialog != null) {
                mDialog.dismiss();
                mDialog = null;
            }
            if (sJob == this) {
                sJob = null;
            }
            if (problem != null && problem != 0) {
                notice(mApp, problem);
            }
        }
    }
}
