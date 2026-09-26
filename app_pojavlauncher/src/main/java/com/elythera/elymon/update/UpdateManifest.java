package com.elythera.elymon.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.Locale;

/**
 * latest.json, the self-update feed (format in docs/elymon/RELEASE.md):
 * <pre>
 * {"versionCode": 2, "versionName": "1.0.0-alpha.2",
 *  "url": "https://cdn.elythera.com/elylauncher/android/Elymon-1.0.0-alpha.2.apk",
 *  "sha256": "&lt;64 hex&gt;", "size": 81234567,
 *  "minVersionCode": 1, "notes": "Nouveautés en français"}
 * </pre>
 * minVersionCode and notes are optional. Unknown fields are ignored, so the format can grow.
 *
 * Pure Java (no Android imports) for host-side tests.
 */
public final class UpdateManifest {
    /** No APK of this app comes close; anything bigger is a mistake in the feed. */
    static final long MAX_APK_BYTES = 1024L * 1024L * 1024L;
    static final int MAX_NOTES_CHARS = 4000;

    public final int versionCode;
    public final String versionName;
    public final String url;
    /** Lowercase hex. */
    public final String sha256;
    public final long size;
    /** 0 when absent. */
    public final int minVersionCode;
    /** Trimmed, "" when absent. */
    public final String notes;

    private UpdateManifest(int versionCode, String versionName, String url, String sha256, long size,
                           int minVersionCode, String notes) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.url = url;
        this.sha256 = sha256;
        this.size = size;
        this.minVersionCode = minVersionCode;
        this.notes = notes;
    }

    /** Parses and validates latest.json. The message of the exception is English, for logs. */
    public static UpdateManifest parse(String json) throws IllegalArgumentException {
        JsonObject root;
        try {
            JsonElement element = JsonParser.parseString(json == null ? "" : json);
            if (element == null || !element.isJsonObject()) {
                throw new IllegalArgumentException("latest.json is not a JSON object");
            }
            root = element.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IllegalArgumentException("latest.json is not valid JSON", e);
        }

        long versionCode = requireLong(root, "versionCode");
        if (versionCode < 1 || versionCode > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("versionCode out of range: " + versionCode);
        }
        String versionName = requireString(root, "versionName").trim();
        if (versionName.isEmpty() || versionName.length() > 64) {
            throw new IllegalArgumentException("versionName is empty or too long");
        }
        String url = requireString(root, "url").trim();
        if (!url.toLowerCase(Locale.ROOT).startsWith("https://") || url.length() > 2048 || containsWhitespace(url)) {
            throw new IllegalArgumentException("url must be an https:// address");
        }
        String sha256 = requireString(root, "sha256").trim().toLowerCase(Locale.ROOT);
        if (!isHex(sha256, 64)) {
            throw new IllegalArgumentException("sha256 must be 64 hex digits");
        }
        long size = requireLong(root, "size");
        if (size <= 0 || size > MAX_APK_BYTES) {
            throw new IllegalArgumentException("size out of range: " + size);
        }
        long minVersionCode = 0;
        if (root.has("minVersionCode") && !root.get("minVersionCode").isJsonNull()) {
            minVersionCode = requireLong(root, "minVersionCode");
            if (minVersionCode < 0 || minVersionCode > versionCode) {
                // An update could never satisfy it: every player would be locked out.
                throw new IllegalArgumentException("minVersionCode must be between 0 and versionCode");
            }
        }
        String notes = "";
        if (root.has("notes") && !root.get("notes").isJsonNull()) {
            notes = requireString(root, "notes").trim();
            if (notes.length() > MAX_NOTES_CHARS) {
                notes = notes.substring(0, MAX_NOTES_CHARS).trim() + "…";
            }
        }
        return new UpdateManifest((int) versionCode, versionName, url, sha256, size, (int) minVersionCode, notes);
    }

    /** Whether this release is newer than the installed build. */
    public boolean isNewerThan(int installedVersionCode) {
        return versionCode > installedVersionCode;
    }

    /** Whether the installed build must update before playing. */
    public boolean isRequiredFor(int installedVersionCode) {
        return minVersionCode > installedVersionCode && isNewerThan(installedVersionCode);
    }

    private static long requireLong(JsonObject root, String name) {
        JsonElement element = root.get(name);
        if (element == null || !element.isJsonPrimitive() || !((JsonPrimitive) element).isNumber()) {
            throw new IllegalArgumentException(name + " must be a number");
        }
        double value = element.getAsDouble();
        if (value != Math.rint(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return element.getAsLong();
    }

    private static String requireString(JsonObject root, String name) {
        JsonElement element = root.get(name);
        if (element == null || !element.isJsonPrimitive() || !((JsonPrimitive) element).isString()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return element.getAsString();
    }

    static boolean isHex(String value, int length) {
        if (value == null || value.length() != length) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
