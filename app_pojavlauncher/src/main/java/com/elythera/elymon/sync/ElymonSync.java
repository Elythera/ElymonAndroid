package com.elythera.elymon.sync;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Installs or updates the Elymon modpack from the Elythera distribution, the
 * way the desktop launcher does, adapted to Android by the policy in
 * assets/elymon/android-policy.json.
 *
 * Pure Java (no Android imports) so it runs in host-side tests. Blocking:
 * call it off the UI thread.
 *
 * <p>Steps of a run:
 * <ol>
 * <li>fetch the distribution (ETag, 3 tries), falling back to
 * workDir/distribution.json when the network fails;</li>
 * <li>plan the files (helios destinations, Android policy, path guard) and
 * read the NeoForge version id from the VersionManifest;</li>
 * <li>verify what is on disk, trusting workDir/sync-index.json when size and
 * mtime did not change;</li>
 * <li>download what is missing or different (.part files resumed with Range,
 * MD5 checked, identical content downloaded once), after
 * {@link SyncListener#confirmDownload} above the threshold;</li>
 * <li>apply the overlays, seed a missing or empty options.txt;</li>
 * <li>prune what this engine placed earlier and the distribution dropped,
 * unless the distribution came from the cache or pruning is not allowed.</li>
 * </ol>
 * Nothing here logs: messages meant for the player are in the exceptions and
 * the stage labels, in French.
 */
public final class ElymonSync {
    private ElymonSync() {}

    private static final ReentrantLock LOCK = new ReentrantLock();

    private static final SyncListener SILENT = new SyncListener() {
        @Override
        public void onStage(String label) {
        }

        @Override
        public void onProgress(long doneBytes, long totalBytes, String currentPath) {
        }

        @Override
        public boolean confirmDownload(long bytesToDownload) {
            return true;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    public static SyncResult run(SyncOptions options, SyncListener listener) throws SyncException {
        SyncText text = new SyncText(options == null ? null : options.text);
        if (!LOCK.tryLock()) {
            throw new SyncException(text.get(SyncText.ERROR_BUSY), null);
        }
        try {
            return new SyncSession(options, listener == null ? SILENT : listener, text).run();
        } finally {
            LOCK.unlock();
        }
    }

    /** Distribution, plan and version id only; nothing is placed. For the planning tests. */
    static SyncSession.Prepared prepare(SyncOptions options, SyncListener listener) throws SyncException {
        SyncText text = new SyncText(options == null ? null : options.text);
        if (!LOCK.tryLock()) {
            throw new SyncException(text.get(SyncText.ERROR_BUSY), null);
        }
        try {
            return new SyncSession(options, listener == null ? SILENT : listener, text).prepare();
        } finally {
            LOCK.unlock();
        }
    }
}
