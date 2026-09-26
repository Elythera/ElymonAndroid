package com.elythera.elymon.ui;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * The few facts the home screen shows about the pack, read from the distribution the
 * last sync cached (&lt;filesDir&gt;/elymon/distribution.json).
 *
 * That file is about 6 MB, nearly all of it the module lists: it is streamed and only
 * the Elymon server's "version" and "minecraftVersion" are kept, every other value is
 * skipped unread. Pure Java (no Android imports) for host-side tests. Call off the UI
 * thread.
 */
public final class ElymonPackInfo {
    /** Pack version, e.g. "2.4.6". Never null. */
    public final String packVersion;
    /** Minecraft version, e.g. "1.21.1", or null when the entry has none. */
    public final String minecraftVersion;

    private ElymonPackInfo(String packVersion, String minecraftVersion) {
        this.packVersion = packVersion;
        this.minecraftVersion = minecraftVersion;
    }

    /**
     * @return the server's versions, or null when the file is missing, unreadable, or
     *         has no such server with a version (never throws)
     */
    public static ElymonPackInfo read(File distributionFile, String serverId) {
        if (distributionFile == null || !distributionFile.isFile()) {
            return null;
        }
        try (JsonReader reader = new JsonReader(new InputStreamReader(
                new BufferedInputStream(new FileInputStream(distributionFile)), StandardCharsets.UTF_8))) {
            reader.setLenient(true);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                return null;
            }
            reader.beginObject();
            while (reader.hasNext()) {
                if ("servers".equals(reader.nextName()) && reader.peek() == JsonToken.BEGIN_ARRAY) {
                    return findServer(reader, serverId);
                }
                reader.skipValue();
            }
        } catch (IOException | RuntimeException e) {
            // A truncated or foreign file: the home screen simply shows no pack version.
        }
        return null;
    }

    private static ElymonPackInfo findServer(JsonReader reader, String serverId) throws IOException {
        reader.beginArray();
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue();
                continue;
            }
            String id = null;
            String version = null;
            String minecraftVersion = null;
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("id".equals(name)) {
                    id = stringOrNull(reader);
                } else if ("version".equals(name)) {
                    version = stringOrNull(reader);
                } else if ("minecraftVersion".equals(name)) {
                    minecraftVersion = stringOrNull(reader);
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
            if (serverId.equals(id)) {
                return isBlank(version) ? null
                        : new ElymonPackInfo(version.trim(), isBlank(minecraftVersion) ? null : minecraftVersion.trim());
            }
        }
        return null;
    }

    private static String stringOrNull(JsonReader reader) throws IOException {
        JsonToken token = reader.peek();
        if (token == JsonToken.STRING || token == JsonToken.NUMBER) {
            return reader.nextString();
        }
        reader.skipValue();
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
