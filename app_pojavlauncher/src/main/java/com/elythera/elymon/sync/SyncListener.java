package com.elythera.elymon.sync;

/**
 * Callbacks from {@link ElymonSync#run}. Every method is called on the sync
 * thread; implementations post to the UI thread themselves.
 */
public interface SyncListener {
    /** A new step starts. The label is French and shown as is to the player. */
    void onStage(String label);

    /** Byte progress of the current step; totalBytes is 0 when unknown. */
    void onProgress(long doneBytes, long totalBytes, String currentPath);

    /**
     * Asked once, before downloading more than {@link SyncOptions#confirmThresholdBytes}.
     * May block until the player answers. Returning false aborts the sync.
     */
    boolean confirmDownload(long bytesToDownload);

    /** Polled between files; returning true aborts the sync with a cancellation. */
    boolean isCancelled();
}
