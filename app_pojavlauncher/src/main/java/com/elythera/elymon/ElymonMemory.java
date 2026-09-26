package com.elythera.elymon;

/**
 * Java heap sizing for Elymon (about 120 mods including Cobblemon).
 *
 * The phase-0 run on a 12 GB phone used 2.1 GB of a 4 GB heap in game; upstream's
 * default of at most 2048 MB is too small for the pack on most devices. The tiers use
 * the total RAM Android reports (MemTotal), which is well below the advertised size:
 * a "12 GB" phone reports about 10.8 to 11.3 GiB (the Galaxy S24 Ultra: 10.8 GiB), an
 * "8 GB" one about 7.3 to 7.6 GiB, a "6 GB" one about 5.5 GiB.
 *
 * Pure Java (no Android imports) for host-side tests.
 */
public final class ElymonMemory {
    private static final int GIB = 1024;

    /**
     * RAM left to Android, the GPU driver (it shares the RAM on phones) and the launcher
     * when the heap is at its largest "safe" size.
     */
    public static final int RESERVED_FOR_ANDROID_MB = 2 * GIB;

    private ElymonMemory() {}

    /** Default -Xmx in MB, used while the player never set "allocation" in the settings. */
    public static int defaultHeapMb(int totalDeviceRamMb) {
        if (totalDeviceRamMb >= 10 * GIB) return 4096;
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

    /** Largest heap that still leaves RESERVED_FOR_ANDROID_MB to the rest of the phone (0 when none does). */
    public static int maxSafeHeapMb(int totalDeviceRamMb) {
        return Math.max(0, totalDeviceRamMb - RESERVED_FOR_ANDROID_MB);
    }

    /**
     * Whether a heap of allocationMb deserves a warning before Play. Only the total RAM
     * counts: the free RAM upstream compared with is low on every phone, because Android
     * keeps background apps in memory until something needs it, then closes them. An
     * unknown total (0 or less) never warns.
     */
    public static boolean exceedsSafeHeap(int allocationMb, int totalDeviceRamMb) {
        return totalDeviceRamMb > 0 && allocationMb > maxSafeHeapMb(totalDeviceRamMb);
    }
}
