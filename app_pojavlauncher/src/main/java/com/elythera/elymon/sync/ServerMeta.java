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
}
