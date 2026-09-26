package com.elythera.elymon.sync;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * What a relative path from the distribution may be, and what the engine must
 * never touch in the instance.
 *
 * Port of the Elythera launcher's app/assets/js/instancepaths.js
 * (isEscapingPath, NEVER_TOUCHED_*, BOOKKEEPING_FILES) with two Android
 * choices: servers.dat is protected too, and comparisons always fold case,
 * because Android/data is a case-insensitive file system.
 */
final class PathGuard {
    private PathGuard() {}

    /** Longest relative path accepted, like instancepaths.js MAX_PATH_CHARS. */
    static final int MAX_PATH_CHARS = 512;

    /** First segments never pruned (instancepaths.js NEVER_TOUCHED_DIRS). */
    static final Set<String> NEVER_TOUCHED_DIRS = new HashSet<String>(Arrays.asList(
            "saves", "screenshots", "logs", "crash-reports", "debug", "backups", "elythera"));

    /**
     * Files at the root of the instance that belong to the player
     * (instancepaths.js NEVER_TOUCHED_FILES, plus servers.dat: the pack does
     * not ship it and the player's server list must survive).
     */
    static final Set<String> NEVER_TOUCHED_FILES = new HashSet<String>(Arrays.asList(
            "options.txt", "optionsof.txt", "optionsshaders.txt", "servers.dat", "servers.dat_old",
            "hotbar.nbt", "usercache.json", "usernamecache.json", "realms_persistence.json",
            "command_history.txt"));

    /** Bookkeeping of this engine and of the desktop launcher, at the root of the instance. */
    static final String[] BOOKKEEPING_FILES = {
            ManagedManifest.FILE_NAME, "distro_mods.json", "distro_files.json", "forgeMods.list",
            "forgeModList.json", "liteloaderModList.json"};

    /**
     * Whether a raw path would land outside its root once joined to it: the
     * check a whole profile is refused on (instancepaths.js isEscapingPath,
     * plus control characters).
     */
    static boolean isEscaping(String raw) {
        if (raw == null) {
            return true;
        }
        if (raw.length() > MAX_PATH_CHARS * 2) {
            return true;
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        if (raw.indexOf('\\') >= 0 || raw.startsWith("/") || hasDriveLetter(raw)) {
            return true;
        }
        for (String segment : raw.split("/", -1)) {
            if (segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDriveLetter(String raw) {
        return raw.length() >= 2 && raw.charAt(1) == ':'
                && ((raw.charAt(0) >= 'A' && raw.charAt(0) <= 'Z') || (raw.charAt(0) >= 'a' && raw.charAt(0) <= 'z'));
    }

    /**
     * The path with empty and "." segments dropped, or null when it escapes or
     * nothing is left.
     */
    static String normalize(String raw) {
        if (isEscaping(raw)) {
            return null;
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (String segment : raw.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(segment);
        }
        if (out.length() == 0 || out.length() > MAX_PATH_CHARS) {
            return null;
        }
        return out.toString();
    }

    /** Whether a single name (a version id, a mod file name) is a plain file name. */
    static boolean isPlainName(String name) {
        if (name == null || name.isEmpty() || name.length() > 255) {
            return false;
        }
        if (name.equals(".") || name.equals("..")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '/' || c == '\\' || c == ':') {
                return false;
            }
        }
        return true;
    }

    /** Case-folded key used to compare two instance paths. */
    static String fold(String rel) {
        return rel.toLowerCase(Locale.ROOT);
    }

    static boolean isBookkeeping(String rel) {
        String folded = fold(rel);
        if (folded.indexOf('/') >= 0) {
            return false;
        }
        for (String name : BOOKKEEPING_FILES) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (folded.equals(lower) || folded.startsWith(lower + ".")) {
                return true;
            }
        }
        return false;
    }

    /** Whether the engine must never delete (nor overwrite) this instance path. */
    static boolean isNeverTouched(String rel) {
        String folded = fold(rel);
        int slash = folded.indexOf('/');
        String first = slash < 0 ? folded : folded.substring(0, slash);
        if (NEVER_TOUCHED_DIRS.contains(first)) {
            return true;
        }
        if (slash < 0 && NEVER_TOUCHED_FILES.contains(folded)) {
            return true;
        }
        return isBookkeeping(rel);
    }

    /** Whether the real location of a file's parent is inside the real root. */
    static boolean staysInside(File root, File file) {
        try {
            String realRoot = root.getCanonicalPath();
            File parent = file.getParentFile();
            if (parent == null) {
                return false;
            }
            String realParent = parent.getCanonicalPath();
            return realParent.equals(realRoot) || realParent.startsWith(realRoot + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    /** Resolves a normalized relative path under a root. */
    static File resolve(File root, String rel) {
        return new File(root, rel.replace('/', File.separatorChar));
    }
}
