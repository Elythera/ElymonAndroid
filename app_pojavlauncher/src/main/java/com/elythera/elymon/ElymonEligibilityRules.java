package com.elythera.elymon;

/**
 * The thresholds {@link ElymonEligibility} applies before the first byte of a sync.
 *
 * RAM is the total Android reports (ActivityManager.MemoryInfo.totalMem), which is below
 * the advertised size: a "12 GB" phone reports about 10.8 to 11.3 GiB, an "8 GB" one about
 * 7.3 to 7.6 GiB, a "6 GB" one about 5.5 GiB and a "4 GB" one about 3.6 GiB. So:
 * "4 GB" phones are refused, "6 GB" phones are warned once, "8 GB" and up pass silently.
 *
 * Pure Java (no Android imports) for host-side tests.
 */
public final class ElymonEligibilityRules {
    public static final long MIB = 1024L * 1024L;
    public static final long GIB = 1024L * MIB;

    /** Below this, Elymon (about 120 mods, a 2.5 GB heap at least) cannot start. */
    public static final long MIN_RAM_BYTES = 5L * GIB;
    /** Below this, it starts but the experience is degraded: one warning, then never again. */
    public static final long WARN_RAM_BYTES = 7L * GIB;

    /** OpenGL ES 3.2, as ConfigurationInfo.reqGlEsVersion encodes it (major in the high 16 bits). */
    public static final int MIN_GLES_VERSION = 0x00030002;

    /**
     * Disk space a first install takes, measured for the 2026-09 pack: about 524 MiB from the
     * Elythera CDN after the Android policy, about 348 MiB of Minecraft files once vanilla
     * music is trimmed (ElymonAssetTrim: 267 MiB of assets, the 26 MiB client, 55 MiB of
     * libraries), Java 21 unpacked (about 110 MiB), rounded up for the runtime data the
     * game writes at its first start (Showdown extraction, caches).
     */
    public static final long FIRST_INSTALL_ESTIMATE_BYTES = 1229L * MIB;
    /** Free space a first install needs: max(3 GiB, 1 GiB + estimate). */
    public static final long MIN_FREE_FIRST_INSTALL_FLOOR = 3L * GIB;
    public static final long FREE_MARGIN_BYTES = 1L * GIB;
    /** Free space every later Play needs (updates, logs, worlds, crash reports). */
    public static final long MIN_FREE_LATER_BYTES = 1L * GIB;

    private ElymonEligibilityRules() {}

    public enum Outcome {
        OK,
        /** Starts, but the player is told once that the experience will be degraded. */
        WARN_LOW_RAM,
        REFUSE_RAM,
        REFUSE_GLES,
        REFUSE_SPACE
    }

    /** The outcome and the numbers the French message needs. */
    public static final class Verdict {
        public final Outcome outcome;
        public final long totalRamBytes;
        public final int glesVersion;
        public final long freeBytes;
        public final long requiredFreeBytes;
        public final boolean firstInstall;

        Verdict(Outcome outcome, long totalRamBytes, int glesVersion, long freeBytes,
                long requiredFreeBytes, boolean firstInstall) {
            this.outcome = outcome;
            this.totalRamBytes = totalRamBytes;
            this.glesVersion = glesVersion;
            this.freeBytes = freeBytes;
            this.requiredFreeBytes = requiredFreeBytes;
            this.firstInstall = firstInstall;
        }

        public boolean refused() {
            return outcome == Outcome.REFUSE_RAM || outcome == Outcome.REFUSE_GLES || outcome == Outcome.REFUSE_SPACE;
        }
    }

    /**
     * @param totalRamBytes total RAM; 0 or less when unknown (never refused for that)
     * @param glesVersion   ConfigurationInfo.reqGlEsVersion; 0 or less when unknown (never refused for that)
     * @param freeBytes     free space where the game lives; negative when unknown (never refused for that)
     * @param firstInstall  whether the pack, Minecraft and Java still have to be installed
     */
    public static Verdict evaluate(long totalRamBytes, int glesVersion, long freeBytes, boolean firstInstall) {
        long required = requiredFreeBytes(firstInstall);
        Outcome outcome;
        if (totalRamBytes > 0 && totalRamBytes < MIN_RAM_BYTES) {
            outcome = Outcome.REFUSE_RAM;
        } else if (glesVersion > 0 && glesVersion < MIN_GLES_VERSION) {
            outcome = Outcome.REFUSE_GLES;
        } else if (freeBytes >= 0 && freeBytes < required) {
            outcome = Outcome.REFUSE_SPACE;
        } else if (totalRamBytes > 0 && totalRamBytes < WARN_RAM_BYTES) {
            outcome = Outcome.WARN_LOW_RAM;
        } else {
            outcome = Outcome.OK;
        }
        return new Verdict(outcome, totalRamBytes, glesVersion, freeBytes, required, firstInstall);
    }

    public static long requiredFreeBytes(boolean firstInstall) {
        if (!firstInstall) {
            return MIN_FREE_LATER_BYTES;
        }
        return Math.max(MIN_FREE_FIRST_INSTALL_FLOOR, FREE_MARGIN_BYTES + FIRST_INSTALL_ESTIMATE_BYTES);
    }

    /** "3.2" for 0x00030002. */
    public static String glesVersionName(int glesVersion) {
        return (glesVersion >>> 16) + "." + (glesVersion & 0xffff);
    }
}
