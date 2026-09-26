package com.elythera.elymon.auth;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Account files as Elymon sees them. They are upstream's
 * {@code <DIR_ACCOUNT_NEW>/<username>.json}, filtered to Microsoft accounts.
 *
 * Upstream keys each account by its Minecraft name. So after a rename, the
 * same player gets a second file, and the selection points to a name that no
 * longer exists. This class finds an account again by its profile UUID and
 * moves the file and the selection to the new name. MinecraftAccount itself
 * is left unchanged.
 */
public final class ElymonAccounts {
    private static final String TAG = "ElymonAccounts";
    private static final String SUFFIX = ".json";

    private ElymonAccounts() {}

    /**
     * Names of the stored accounts that can play Elymon: Microsoft accounts
     * only, sorted by name. Local or demo files, and anything that is not an
     * account file, are skipped.
     */
    public static List<String> listMicrosoftAccountNames() {
        List<String> names = new ArrayList<>();
        String[] files = new File(Tools.DIR_ACCOUNT_NEW).list();
        if (files == null) return names;
        Arrays.sort(files);
        for (String file : files) {
            if (!file.endsWith(SUFFIX) || file.length() == SUFFIX.length()) continue;
            String name = file.substring(0, file.length() - SUFFIX.length());
            MinecraftAccount account = MinecraftAccount.load(name);
            if (account != null && account.isMicrosoft && !account.isDemo()) {
                names.add(name);
            }
        }
        return names;
    }

    /** The stored account with this Minecraft profile UUID, with or without dashes, or null. */
    @Nullable
    public static MinecraftAccount findByProfileId(String profileId) {
        if (profileId == null) return null;
        String wanted = normalizeUuid(profileId);
        String[] files = new File(Tools.DIR_ACCOUNT_NEW).list();
        if (files == null) return null;
        for (String file : files) {
            if (!file.endsWith(SUFFIX)) continue;
            MinecraftAccount account = MinecraftAccount.load(file.substring(0, file.length() - SUFFIX.length()));
            if (account != null && account.profileId != null && wanted.equals(normalizeUuid(account.profileId))) {
                return account;
            }
        }
        return null;
    }

    /**
     * After an account was saved under its new Minecraft name, remove the
     * file and the cached head saved under the old name. If the old name was
     * selected, select the new one: the game process reads the selection to
     * find the account (MainActivity, PojavProfile.getCurrentProfileContent).
     */
    public static void forgetOldName(String oldName, String newName) {
        if (oldName == null || oldName.equals(newName)) return;
        if (!new File(Tools.DIR_ACCOUNT_NEW, oldName + SUFFIX).delete()) {
            Log.w(TAG, "Could not delete the account file of a renamed profile");
        }
        //noinspection ResultOfMethodCallIgnored
        new File(Tools.DIR_CACHE, oldName + ".png").delete();
        Context context = ElymonSession.appContext();
        if (context != null && oldName.equals(PojavProfile.getCurrentProfileName(context))) {
            PojavProfile.setCurrentProfile(context, newName);
        }
        Log.i(TAG, "Minecraft profile renamed, account file moved");
    }

    /** Whether the head of this account is cached (MinecraftAccount keeps it as DIR_CACHE/<name>.png). */
    public static boolean hasHead(String name) {
        return name != null && new File(Tools.DIR_CACHE, name + ".png").isFile();
    }

    /** Remove the cached head of an account being deleted (upstream leaves it in DIR_CACHE). */
    public static void forgetHead(String name) {
        if (name == null) return;
        //noinspection ResultOfMethodCallIgnored
        new File(Tools.DIR_CACHE, name + ".png").delete();
    }

    static String normalizeUuid(String uuid) {
        return uuid.replace("-", "").toLowerCase(Locale.ROOT);
    }
}
