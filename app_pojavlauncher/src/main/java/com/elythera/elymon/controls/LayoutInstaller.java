package com.elythera.elymon.controls;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Locale;

/**
 * Keeps controlmap/default.json on the layout this APK ships, without ever overwriting a
 * layout the player edited. Pure Java, so the host tests run it.
 *
 * <ul>
 * <li>no default.json: the shipped layout is written (first run);</li>
 * <li>default.json is the shipped layout: nothing to do;</li>
 * <li>default.json is a layout an Elymon build installed and the player never touched (its
 * SHA-256 is the one recorded at the last install, or one of {@link ElymonControls#LAYOUTS},
 * which include upstream Amethyst's default.json that the first builds installed): it is
 * replaced;</li>
 * <li>otherwise the player edited it: it stays, and the shipped layout is written beside it
 * as elymon-&lt;version&gt;.json, for the player to load when they want (once: an existing
 * file of that name is never replaced).</li>
 * </ul>
 * Upstream's own step (writing new_default.json when the SHA-1 differs, never replacing
 * anything) is gone; a new_default.json left by it is deleted when it is one of our layouts.
 */
final class LayoutInstaller {
    static final String DEFAULT_FILE = "default.json";
    static final String UPSTREAM_NEW_DEFAULT = "new_default.json";

    enum Action {
        /** default.json already is the shipped layout. */
        UP_TO_DATE,
        /** default.json was missing or never edited: it now holds the shipped layout. */
        INSTALLED,
        /** default.json was edited: kept, the shipped layout is beside it. */
        KEPT_PLAYER_LAYOUT
    }

    static final class Result {
        final Action action;
        /** SHA-256 to remember as "last installed", or null to keep the remembered one. */
        final String installedSha;
        /** File name of the shipped layout written beside the player's, or null. */
        final String alongside;
        /** True when {@link #alongside} was created by this call. */
        final boolean alongsideCreated;

        Result(Action action, String installedSha, String alongside, boolean alongsideCreated) {
            this.action = action;
            this.installedSha = installedSha;
            this.alongside = alongside;
            this.alongsideCreated = alongsideCreated;
        }
    }

    private LayoutInstaller() {}

    /** What to do, from hashes only. diskSha is null when default.json does not exist. */
    static Action decide(String diskSha, String assetSha, String lastInstalledSha, Collection<String> pristine) {
        if (diskSha == null) {
            return Action.INSTALLED;
        }
        if (diskSha.equals(assetSha)) {
            return Action.UP_TO_DATE;
        }
        if (diskSha.equals(lastInstalledSha) || pristine.contains(diskSha)) {
            return Action.INSTALLED;
        }
        return Action.KEPT_PLAYER_LAYOUT;
    }

    /** The name of the shipped layout when it has to live beside the player's. */
    static String alongsideName(String version) {
        String safe = version.replaceAll("[^A-Za-z0-9._-]", "_");
        return "elymon-" + safe + ".json";
    }

    /**
     * Applies {@link #decide} to controlDir.
     *
     * <p>The file beside an edited layout is written once and never replaced: the editor's save
     * dialog offers the loaded file's name, so a player who loaded elymon-&lt;version&gt;.json,
     * changed it and saved keeps their work there. It is not written again either once the
     * player was told about it (alreadyOffered): if it is gone, the player deleted it.
     *
     * @param asset the shipped default.json
     * @param assetVersion its version in {@link ElymonControls#LAYOUTS}
     * @param alreadyOffered the player was already told about this version's alongside file
     */
    static Result apply(File controlDir, byte[] asset, String assetVersion, String lastInstalledSha,
                        Collection<String> pristine, boolean alreadyOffered) throws IOException {
        if (!controlDir.isDirectory() && !controlDir.mkdirs()) {
            throw new IOException("cannot create " + controlDir.getName());
        }
        String assetSha = sha256(asset);
        File target = new File(controlDir, DEFAULT_FILE);
        String diskSha = target.isFile() ? sha256(target) : null;
        deleteUpstreamNewDefault(controlDir, assetSha, pristine);

        Action action = decide(diskSha, assetSha, lastInstalledSha, pristine);
        switch (action) {
            case UP_TO_DATE:
                return new Result(action, assetSha, null, false);
            case INSTALLED:
                writeAtomic(target, asset);
                return new Result(action, assetSha, null, false);
            default:
                String name = alongsideName(assetVersion);
                File beside = new File(controlDir, name);
                boolean create = !beside.exists() && !alreadyOffered;
                if (create) {
                    writeAtomic(beside, asset);
                }
                return new Result(action, null, name, create);
        }
    }

    /** Best effort: a leftover that cannot be read or deleted is only clutter. */
    private static void deleteUpstreamNewDefault(File controlDir, String assetSha, Collection<String> pristine) {
        File stale = new File(controlDir, UPSTREAM_NEW_DEFAULT);
        if (!stale.isFile()) {
            return;
        }
        try {
            String sha = sha256(stale);
            if (sha.equals(assetSha) || pristine.contains(sha)) {
                // a copy of one of our layouts, never the player's work
                stale.delete();
            }
        } catch (IOException ignored) {
            // left in place
        }
    }

    // ------------------------------------------------------------------ files

    static void writeAtomic(File target, byte[] data) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".elymon-tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(data);
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(target)) {
            // renameTo does not replace on every file system
            if (!target.delete() || !tmp.renameTo(target)) {
                tmp.delete();
                throw new IOException("cannot write " + target.getName());
            }
        }
    }

    static String sha256(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            MessageDigest digest = sha256Digest();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
            return hex(digest.digest());
        } finally {
            in.close();
        }
    }

    static String sha256(byte[] data) {
        return hex(sha256Digest().digest(data));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return out.toString();
    }
}
