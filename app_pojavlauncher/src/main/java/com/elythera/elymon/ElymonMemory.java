package com.elythera.elymon;

/**
 * Java heap sizing for Elymon (about 120 mods including Cobblemon).
 *
 * The phase-0 run on a 12 GB phone used 2.1 GB of a 4 GB heap in game; upstream's
 * default of at most 2048 MB is too small for the pack on most devices. The tiers use
 * the total RAM Android reports, which is below the advertised size: a 12 GB phone
 * reports about 11.1 GiB, an 8 GB one about 7.3 GiB, a 6 GB one about 5.5 GiB.
 *
 * Pure Java (no Android imports) for host-side tests.
 */
public final class ElymonMemory {
    private static final int GIB = 1024;

    private ElymonMemory() {}

    /** Default -Xmx in MB, used while the player never set "allocation" in the settings. */
    public static int defaultHeapMb(int totalDeviceRamMb) {
        if (totalDeviceRamMb >= 11 * GIB) return 4096;
        if (totalDeviceRamMb >= 7 * GIB) return 3072;
        if (totalDeviceRamMb >= 5 * GIB) return 2560;
        return 2048;
    }

    /**
     * Initial heap (-Xms) in MB. Upstream set it equal to -Xmx, which commits the whole
     * heap at start on a phone that also needs memory for the GPU driver.
     */
    public static int initialHeapMb(int maxHeapMb) {
        return Math.min(1024, maxHeapMb);
    }
}
