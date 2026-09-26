package com.elythera.elymon;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.LifecycleOwner;

import com.elythera.elymon.auth.ElymonSession;
import com.elythera.elymon.sync.ElymonSync;
import com.elythera.elymon.sync.ServerMeta;
import com.elythera.elymon.sync.SyncException;
import com.elythera.elymon.sync.SyncListener;
import com.elythera.elymon.sync.SyncOptions;
import com.elythera.elymon.sync.SyncResult;
import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.BuildConfig;
import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.LauncherActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.lifecycle.ContextAwareDoneListener;
import net.kdt.pojavlaunch.lifecycle.ContextExecutor;
import net.kdt.pojavlaunch.lifecycle.ContextExecutorTask;
import net.kdt.pojavlaunch.lifecycle.LifecycleAwareAlertDialog;
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader;
import net.kdt.pojavlaunch.tasks.MinecraftDownloader;
import net.kdt.pojavlaunch.utils.NotificationUtils;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the Play button does in Elymon (DESIGN.md "Play flow"):
 * 1. refuse while the :game process is alive;
 * 2. refresh the Microsoft session;
 * 3. sync the pack from the Elythera distribution, with progress in ProgressLayout;
 * 4. gate on the distribution's requires and availability;
 * 5. write the Elymon profile;
 * 6. hand over to upstream's MinecraftDownloader (vanilla files, Java 21), which then
 *    starts the game exactly as it does upstream.
 */
public final class ElymonLaunch {
    /** Progress key of the Elymon steps; LauncherActivity's ProgressLayout observes it. */
    public static final String PROGRESS_KEY = "elymon_sync";

    private static final String TAG = "ElymonLaunch";
    private static final String POLICY_ASSET = "elymon/android-policy.json";
    /** How long a download confirmation waits for a launcher screen before giving up. */
    private static final long CONFIRM_TIMEOUT_MS = 30L * 60L * 1000L;
    private static final long PROGRESS_MIN_INTERVAL_MS = 500L;
    private static final int NOTIFICATION_ID_READY = 101;
    private static final int PENDINGINTENT_CODE_READY = 101;

    private static final int ANSWER_NONE = 0;
    private static final int ANSWER_YES = 1;
    private static final int ANSWER_NO = 2;
    private static final int ANSWER_LATER = 3;

    private static volatile boolean sCancelRequested;

    private ElymonLaunch() {}

    /**
     * Starts the Play flow. UI thread; the caller already checked that no task is running
     * and that an account is selected.
     */
    public static void start(@NonNull AppCompatActivity activity, @NonNull MinecraftAccount account) {
        if (!account.isMicrosoft || account.isLocal() || account.isDemo()) {
            new ElymonNotice(activity.getString(R.string.elymon_account_required_title),
                    activity.getString(R.string.elymon_account_required_message), null, null)
                    .executeWithActivity(activity);
            return;
        }
        sCancelRequested = false;
        // Registered before the worker starts, so a second tap on Play sees a running task.
        ProgressLayout.setProgress(PROGRESS_KEY, 0, R.string.elymon_progress_preparing);
        final Context appContext = activity.getApplicationContext();
        final WeakReference<AppCompatActivity> activityRef = new WeakReference<>(activity);
        new Thread(() -> prepare(appContext, activityRef, account), "ElymonLaunch").start();
    }

    /** Asks a running sync to stop at the next file. Nothing in the UI calls it yet. */
    public static void cancel() {
        sCancelRequested = true;
    }

    /** Steps 1 to 4, on the worker thread. */
    private static void prepare(Context app, WeakReference<AppCompatActivity> activityRef, MinecraftAccount account) {
        boolean handedOver = false;
        try {
            if (isGameProcessAlive(app)) {
                notice(app, R.string.elymon_game_running_title, app.getString(R.string.elymon_game_running_message), null, null);
                return;
            }

            ProgressLayout.setProgress(PROGRESS_KEY, 0, R.string.elymon_progress_session);
            try {
                ElymonSession.ensureFresh(app, account);
            } catch (IOException e) {
                // The message is the auth package's (French, meant for the player, secret-free:
                // ElymonAuthException): it says whether to retry or to add the account again.
                // Not logged here.
                Log.w(TAG, "Session refresh failed: " + e.getClass().getSimpleName());
                String message = Tools.isValidString(trimmed(e.getMessage())) ? e.getMessage().trim()
                        : app.getString(R.string.elymon_session_failed_message);
                notice(app, R.string.elymon_session_failed_title, message, null, e);
                return;
            }

            SyncResult result = ElymonSync.run(syncOptions(app), new ProgressBridge(app));
            if (result == null || !passesGates(app, result.meta)) {
                return;
            }

            final String versionId = Tools.isValidString(result.versionId) ? result.versionId : null;
            ProgressLayout.setProgress(PROGRESS_KEY, 100, R.string.elymon_progress_minecraft);
            Tools.runOnUiThread(() -> handOver(app, activityRef, versionId));
            handedOver = true;
        } catch (SyncException e) {
            if (!e.cancelled) {
                String message = Tools.isValidString(e.getMessage()) ? e.getMessage()
                        : app.getString(R.string.elymon_sync_failed_message);
                notice(app, R.string.elymon_sync_failed_title, message, null, e);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Préparation d'Elymon interrompue", t);
            notice(app, R.string.elymon_sync_failed_title, app.getString(R.string.elymon_unexpected_error), null, t);
        } finally {
            if (!handedOver) {
                ProgressLayout.clearProgress(PROGRESS_KEY);
            }
        }
    }

    private static SyncOptions syncOptions(Context app) throws SyncException {
        SyncOptions options = new SyncOptions();
        options.gameHome = new File(Tools.DIR_GAME_HOME);
        options.minecraftDir = new File(Tools.DIR_GAME_NEW);
        options.workDir = new File(app.getFilesDir(), "elymon");
        if (!options.workDir.isDirectory() && !options.workDir.mkdirs()) {
            Log.w(TAG, "Cannot create " + options.workDir);
        }
        options.policyJson = readPolicy(app);
        // Checked again right before the sync starts: files in use are never pruned.
        options.allowPrune = !isGameProcessAlive(app);
        options.userAgent = "ElymonAndroid/" + BuildConfig.VERSION_NAME;
        // The engine keeps built-in copies of its texts; the elymon_sync_* resources win.
        options.text = key -> {
            int id = app.getResources().getIdentifier("elymon_sync_" + key, "string", app.getPackageName());
            return id == 0 ? null : app.getString(id);
        };
        return options;
    }

    /**
     * The Android policy is part of the APK. Without it the sync would install the mods
     * that cannot run on Android (Distant Horizons, Iris...), so a missing asset stops
     * Play instead of falling back to an empty policy.
     */
    private static String readPolicy(Context app) throws SyncException {
        try (InputStream in = app.getAssets().open(POLICY_ASSET)) {
            return Tools.read(in);
        } catch (IOException e) {
            Log.e(TAG, POLICY_ASSET + " is missing from the APK");
            throw new SyncException(app.getString(R.string.elymon_policy_missing), e);
        }
    }

    /** Distribution gates, in the desktop's order: requires, then maintenance, then availability. */
    private static boolean passesGates(Context app, @Nullable ServerMeta meta) {
        if (meta == null) {
            return true;
        }
        if (!ElymonVersions.atLeast(ElymonConfig.ELP_LEVEL, meta.requiresLauncher)) {
            notice(app, R.string.elymon_update_required_title,
                    withOperatorMessage(app.getString(R.string.elymon_update_required_message), meta.requiresMessage),
                    meta.requiresUrl, null);
            return false;
        }
        if (!ElymonVersions.atLeast(BuildConfig.VERSION_NAME, meta.requiresAndroid)) {
            notice(app, R.string.elymon_update_required_title,
                    withOperatorMessage(app.getString(R.string.elymon_update_required_android,
                            BuildConfig.VERSION_NAME, meta.requiresAndroid.trim()), meta.requiresMessage),
                    meta.requiresUrl, null);
            return false;
        }
        if (meta.maintenanceActive) {
            String message = Tools.isValidString(trimmed(meta.maintenanceMessage)) ? meta.maintenanceMessage.trim()
                    : app.getString(R.string.elymon_maintenance_message);
            notice(app, R.string.elymon_maintenance_title, message, meta.maintenanceUrl, null);
            return false;
        }
        if (!meta.available) {
            String message = Tools.isValidString(trimmed(meta.availabilityMessage)) ? meta.availabilityMessage.trim()
                    : app.getString(R.string.elymon_unavailable_message);
            notice(app, R.string.elymon_unavailable_title, message, null, null);
            return false;
        }
        return true;
    }

    /** Steps 5 and 6, on the UI thread. */
    private static void handOver(Context app, WeakReference<AppCompatActivity> activityRef, @Nullable String versionId) {
        AppCompatActivity activity = activityRef.get();
        if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
            launch(activity, versionId);
            return;
        }
        // The screen that pressed Play is gone (rotated, or closed): use the current one if any.
        ContextExecutor.execute(new ContextExecutorTask() {
            @Override
            public void executeWithActivity(Activity current) {
                launch(current, versionId);
            }

            @Override
            public void executeWithApplication(Context context) {
                ProgressLayout.clearProgress(PROGRESS_KEY);
                Intent intent = new Intent(context, LauncherActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                NotificationUtils.sendBasicNotification(context, R.string.elymon_notif_ready_title,
                        R.string.elymon_notif_ready_text, intent, PENDINGINTENT_CODE_READY, NOTIFICATION_ID_READY);
            }
        });
    }

    private static void launch(Activity activity, @Nullable String versionId) {
        try {
            ElymonProfile.ensure(activity, versionId);
            String profileVersionId = LauncherProfiles.getCurrentProfile().lastVersionId;
            // From here on, exactly what upstream's LauncherActivity did.
            String normalizedVersionId = AsyncMinecraftDownloader.normalizeVersionId(profileVersionId);
            JMinecraftVersionList.Version mcVersion = AsyncMinecraftDownloader.getListedVersion(normalizedVersionId);
            // Hand the running task over to MinecraftDownloader's own progress line before
            // dropping the Elymon one: the task count never reaches zero in between (so a second
            // tap on Play is still refused), and MinecraftDownloader clears that line itself when
            // it ends, on every path (online, offline, failure), so Play never stays locked.
            ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0, R.string.newdl_starting);
            ProgressLayout.clearProgress(PROGRESS_KEY);
            new MinecraftDownloader().start(activity, mcVersion, normalizedVersionId,
                    new ContextAwareDoneListener(activity, normalizedVersionId));
        } catch (RuntimeException e) {
            Log.e(TAG, "Lancement d'Elymon impossible", e);
            ProgressLayout.clearProgress(PROGRESS_KEY);
            ProgressLayout.clearProgress(ProgressLayout.DOWNLOAD_MINECRAFT);
            notice(activity.getApplicationContext(), R.string.elymon_sync_failed_title,
                    activity.getString(R.string.elymon_unexpected_error), null, e);
        }
    }

    /** Whether this app's :game process (MainActivity, GameService) is running. */
    static boolean isGameProcessAlive(Context context) {
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) return false;
        // Since API 22 this lists only the caller's own processes.
        List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
        if (processes == null) return false;
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.processName != null && process.processName.endsWith(":game")) {
                return true;
            }
        }
        return false;
    }

    private static void notice(Context app, int titleRes, String message, @Nullable String url, @Nullable Throwable details) {
        new ElymonNotice(app.getString(titleRes), message, url,
                details == null ? null : Tools.printToString(details)).show();
    }

    private static String withOperatorMessage(String message, @Nullable String operatorMessage) {
        String extra = trimmed(operatorMessage);
        return Tools.isValidString(extra) ? message + "\n\n" + extra : message;
    }

    private static String trimmed(@Nullable String value) {
        return value == null ? null : value.trim();
    }

    /** "350 Mo", "1,2 Go". */
    static String formatSize(Context context, long bytes) {
        double megabytes = Math.max(0, bytes) / (1024.0 * 1024.0);
        if (megabytes >= 1024.0) {
            return context.getString(R.string.elymon_size_gb, String.format(Locale.FRANCE, "%.1f", megabytes / 1024.0));
        }
        return context.getString(R.string.elymon_size_mb,
                String.format(Locale.FRANCE, megabytes >= 100.0 ? "%.0f" : "%.1f", megabytes));
    }

    /**
     * Shows a yes/no dialog on the launcher screen and blocks the calling (non UI) thread
     * until the player answers. Survives the screen being recreated (rotation) by asking
     * again on the new one, and waits while no launcher screen is in the foreground.
     */
    static boolean askBlocking(final String title, final String message, final String yes, final String no)
            throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + CONFIRM_TIMEOUT_MS;
        while (!sCancelRequested && SystemClock.elapsedRealtime() < deadline) {
            final AtomicInteger answer = new AtomicInteger(ANSWER_NONE);
            final CountDownLatch hidden = new CountDownLatch(1);
            ContextExecutor.execute(new ContextExecutorTask() {
                @Override
                public void executeWithActivity(Activity activity) {
                    try {
                        if (!(activity instanceof LifecycleOwner) || activity.isFinishing()) {
                            answer.set(ANSWER_LATER);
                            hidden.countDown();
                            return;
                        }
                        new LifecycleAwareAlertDialog() {
                            @Override
                            protected void dialogHidden(boolean lifecycleEnded) {
                                answer.compareAndSet(ANSWER_NONE, lifecycleEnded ? ANSWER_LATER : ANSWER_NO);
                                hidden.countDown();
                            }
                        }.show(((LifecycleOwner) activity).getLifecycle(), activity, (dialog, builder) -> builder
                                .setTitle(title)
                                .setMessage(message)
                                .setPositiveButton(yes, (d, w) -> answer.compareAndSet(ANSWER_NONE, ANSWER_YES))
                                .setNegativeButton(no, (d, w) -> answer.compareAndSet(ANSWER_NONE, ANSWER_NO)));
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Cannot show the confirmation", e);
                        answer.compareAndSet(ANSWER_NONE, ANSWER_LATER);
                        hidden.countDown();
                    }
                }

                @Override
                public void executeWithApplication(Context context) {
                    answer.set(ANSWER_LATER);
                    hidden.countDown();
                }
            });
            hidden.await();
            int result = answer.get();
            if (result == ANSWER_YES) return true;
            if (result == ANSWER_NO) return false;
            Thread.sleep(1000);
        }
        return false;
    }

    /** SyncListener → ProgressLayout, plus the download confirmation. */
    private static final class ProgressBridge implements SyncListener {
        private final Context mContext;
        private String mStage;
        private int mLastPercent = -1;
        private long mLastUpdate;

        ProgressBridge(Context context) {
            mContext = context;
            mStage = context.getString(R.string.elymon_progress_update);
        }

        @Override
        public synchronized void onStage(String label) {
            mStage = Tools.isValidString(label) ? label : mContext.getString(R.string.elymon_progress_update);
            mLastPercent = 0;
            mLastUpdate = SystemClock.uptimeMillis();
            ProgressLayout.setProgress(PROGRESS_KEY, 0, mStage);
        }

        @Override
        public synchronized void onProgress(long doneBytes, long totalBytes, String currentPath) {
            int percent = totalBytes > 0 ? (int) Math.max(0, Math.min(100, doneBytes * 100 / totalBytes)) : 0;
            long now = SystemClock.uptimeMillis();
            if (percent == mLastPercent && now - mLastUpdate < PROGRESS_MIN_INTERVAL_MS) {
                return;
            }
            mLastPercent = percent;
            mLastUpdate = now;
            if (totalBytes > 0) {
                ProgressLayout.setProgress(PROGRESS_KEY, percent, R.string.elymon_progress_bytes,
                        mStage, formatSize(mContext, doneBytes), formatSize(mContext, totalBytes));
            } else {
                ProgressLayout.setProgress(PROGRESS_KEY, percent, R.string.elymon_progress_bytes_unknown,
                        mStage, formatSize(mContext, doneBytes));
            }
        }

        @Override
        public boolean confirmDownload(long bytesToDownload) {
            int percent;
            String stage;
            synchronized (this) {
                percent = Math.max(0, mLastPercent);
                stage = mStage;
            }
            ProgressLayout.setProgress(PROGRESS_KEY, percent, R.string.elymon_progress_waiting);
            String size = formatSize(mContext, bytesToDownload);
            String message = mContext.getString(isMetered(mContext)
                    ? R.string.elymon_confirm_metered : R.string.elymon_confirm_unmetered, size);
            try {
                return askBlocking(mContext.getString(R.string.elymon_confirm_title), message,
                        mContext.getString(R.string.elymon_confirm_download),
                        mContext.getString(R.string.elymon_confirm_cancel));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                ProgressLayout.setProgress(PROGRESS_KEY, percent, stage);
            }
        }

        @Override
        public boolean isCancelled() {
            return sCancelRequested;
        }

        private static boolean isMetered(Context context) {
            ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            // Unknown counts as metered: the warning is the safe side.
            return connectivity == null || connectivity.isActiveNetworkMetered();
        }
    }
}
