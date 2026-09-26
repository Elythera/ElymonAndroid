package com.elythera.elymon.sync;

/**
 * Elythera extensions of the Elymon server entry, as read from the
 * distribution. Null fields mean the block is absent.
 */
public final class ServerMeta {
    public String name;
    public String address;
    public String packVersion;
    public String minecraftVersion;
    /** "availability": {"enabled": bool, "message": str}. Absent block means available. */
    public boolean available = true;
    public String availabilityMessage;
    /** "requires": {"launcher": "x.y.z", "android": "x.y.z", "message": str, "url": str}. */
    public String requiresLauncher;
    public String requiresAndroid;
    public String requiresMessage;
    public String requiresUrl;
    /** "background": {"image": url}. */
    public String backgroundImage;
    public String iconUrl;

    // Added by the sync package. The fields above keep their meaning; requires*
    // is the higher of the root and profile floors, the profile winning a tie
    // (desktop launcherrequirement.js), and unreadable floors are left null.

    /**
     * "open", "maintenance", "upcoming" or "ended", derived at sync time from
     * availability.enabled/start/end like the desktop's availability.js.
     * {@link #available} is true exactly when this is "open".
     */
    public String availabilityState = "open";
    /** Epoch milliseconds of availability.start and availability.end; 0 when absent or unreadable. */
    public long availabilityStart;
    public long availabilityEnd;

    /**
     * Root "maintenance" block (desktop maintenance.js). Active only when the
     * notice is in force AND the distribution was just fetched from the
     * network: a notice read from the cache never closes Play.
     */
    public boolean maintenanceActive;
    public String maintenanceMessage;
    public String maintenanceUrl;
}
