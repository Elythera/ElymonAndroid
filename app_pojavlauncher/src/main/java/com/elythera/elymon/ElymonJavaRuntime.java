package com.elythera.elymon;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Source and SHA-256 pin of the Java runtime Elymon downloads (Java 21, arm64-v8a).
 *
 * Upstream downloads it from GitHub with no checksum at all; NewJREUtil now asks this
 * class for the URL and checks the archive before anything is unpacked. Other Java
 * versions keep upstream's behaviour (they are only reachable from the runtime manager).
 *
 * Pure Java (no Android imports) for host-side tests.
 */
public final class ElymonJavaRuntime {
    private ElymonJavaRuntime() {}

    /** The URL to download Java javaVersion for arch ("arm64", "arm", ...) from, or null to keep upstream's. */
    public static String sourceUrl(int javaVersion, String arch) {
        return isElymonRuntime(javaVersion, arch) ? ElymonConfig.JRE21_ARM64_URL : null;
    }

    /** The pinned lowercase SHA-256 of that archive, or null when there is no pin. */
    public static String pinnedSha256(int javaVersion, String arch) {
        return isElymonRuntime(javaVersion, arch) ? ElymonConfig.JRE21_ARM64_SHA256 : null;
    }

    /**
     * Checks a downloaded runtime archive against its pin. Returns true when it matches
     * or when that runtime has no pin; on a mismatch, deletes the archive and returns false.
     */
    public static boolean checkPinned(File archive, int javaVersion, String arch) throws IOException {
        String expected = pinnedSha256(javaVersion, arch);
        if (expected == null) {
            return true;
        }
        if (expected.equalsIgnoreCase(sha256Hex(archive))) {
            return true;
        }
        if (!archive.delete()) {
            archive.deleteOnExit();
        }
        return false;
    }

    /** Lowercase hex SHA-256 of a file, streamed. */
    public static String sha256Hex(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return hex.toString();
    }

    private static boolean isElymonRuntime(int javaVersion, String arch) {
        return javaVersion == 21 && "arm64".equals(arch);
    }
}
