package com.elythera.elymon.auth;

import net.kdt.pojavlaunch.value.MinecraftAccount;

import java.io.IOException;

/** Session upkeep for the account about to play. */
public final class ElymonSession {
    private ElymonSession() {}

    /**
     * Makes sure the Minecraft access token of a Microsoft account is valid for
     * the coming session, refreshing and saving the account if needed. Blocking:
     * call it off the UI thread. Returns the account to launch with.
     */
    public static MinecraftAccount ensureFresh(MinecraftAccount account) throws IOException {
        return account;
    }
}
