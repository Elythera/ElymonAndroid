package com.elythera.elymon.sync;

/**
 * Installs or updates the Elymon modpack from the Elythera distribution, the
 * way the desktop launcher does, adapted to Android by the policy in
 * assets/elymon/android-policy.json.
 *
 * Pure Java (no Android imports) so it runs in host-side tests. Blocking:
 * call it off the UI thread.
 */
public final class ElymonSync {
    private ElymonSync() {}

    public static SyncResult run(SyncOptions options, SyncListener listener) throws SyncException {
        throw new UnsupportedOperationException("ElymonSync is implemented in the sync work package");
    }
}
