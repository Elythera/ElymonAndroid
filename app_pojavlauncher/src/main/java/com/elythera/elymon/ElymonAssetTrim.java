package com.elythera.elymon;

import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Vanilla assets Elymon never downloads nor verifies (MinecraftDownloader).
 *
 * Asset index 17 (Minecraft 1.21.1) lists 787 MiB of objects, of which 520 MiB (545 MB)
 * are the 60 tracks under minecraft/sounds/music/. Elymon replaces vanilla music with
 * its own (the pack's music, and the Elymon mod's VanillaMusicSuppressor), so those
 * files would only cost download time and phone storage.
 *
 * Missing music does not break the game: the asset index still maps the names, the
 * sound manager logs "File ... does not exist, cannot add it to event" once per track
 * at resource reload, and a music event with no playable sound stays silent. Players
 * who pick the "vanilla" music mode of the Elymon mod get silence instead.
 *
 * Pure Java (no Android imports) for host-side tests.
 */
public final class ElymonAssetTrim {
    /** Asset names (keys of the index's "objects") that are skipped. */
    private static final String[] TRIMMED_PREFIXES = {
            "minecraft/sounds/music/",
    };

    private ElymonAssetTrim() {}

    /** Reads an object's hash, whatever class the index is parsed into. */
    public interface HashOf<T> {
        String hash(T info);
    }

    /** Whether the asset with this name (e.g. "minecraft/sounds/music/game/calm1.ogg") is skipped. */
    public static boolean isTrimmed(String assetName) {
        if (assetName == null) {
            return false;
        }
        for (String prefix : TRIMMED_PREFIXES) {
            if (assetName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Deletes from objectsDir (assets/objects, laid out as xx/hash) the objects that only
     * trimmed assets use, which builds older than the trim downloaded. An object that a
     * kept asset shares is never deleted. Returns the number of files deleted.
     */
    public static <T> int deleteTrimmedObjects(File objectsDir, Map<String, T> objects, HashOf<T> hashOf) {
        if (objectsDir == null || objects == null || !objectsDir.isDirectory()) {
            return 0;
        }
        Set<String> kept = new HashSet<>();
        Set<String> trimmed = new HashSet<>();
        for (Map.Entry<String, T> entry : objects.entrySet()) {
            T info = entry.getValue();
            String hash = info == null ? null : hashOf.hash(info);
            if (!isSha1(hash)) {
                continue;
            }
            hash = hash.toLowerCase(Locale.ROOT);
            if (isTrimmed(entry.getKey())) {
                trimmed.add(hash);
            } else {
                kept.add(hash);
            }
        }
        trimmed.removeAll(kept);
        int deleted = 0;
        for (String hash : trimmed) {
            File object = new File(new File(objectsDir, hash.substring(0, 2)), hash);
            if (object.isFile() && object.delete()) {
                deleted++;
            }
        }
        return deleted;
    }

    private static boolean isSha1(String hash) {
        if (hash == null || hash.length() != 40) {
            return false;
        }
        for (int i = 0; i < hash.length(); i++) {
            if (Character.digit(hash.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
