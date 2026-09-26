package com.elythera.elymon.sync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * workDir/sync-index.json: what the engine last saw of each file it places,
 * so that a Play does not re-hash about 1 GB. A file whose size and mtime are
 * those recorded is trusted to still have the recorded MD5; any difference
 * means a re-hash.
 *
 * <pre>
 * {"version": 1, "files": {"instance/config/fml.toml":
 *     {"size": 1234, "mtime": 1790000000000, "md5": "…", "distMd5": "…", "overlayId": "…"}}}
 * </pre>
 * Keys are "&lt;root&gt;/&lt;path&gt;" with root "versions", "libraries" or "instance".
 * distMd5 and overlayId are present only when md5 is the result of that
 * overlay applied to the distribution bytes distMd5. The index is only a
 * cache: an unreadable one is an empty one.
 */
final class SyncIndex {
    static final String FILE_NAME = "sync-index.json";
    private static final int VERSION = 1;

    static final class Entry {
        long size;
        long mtime;
        String md5;
        String distMd5;
        String overlayId;
    }

    private final Map<String, Entry> entries = new HashMap<String, Entry>();
    private boolean dirty;

    static SyncIndex load(File file) {
        SyncIndex index = new SyncIndex();
        if (!file.isFile()) {
            return index;
        }
        try {
            byte[] bytes = FileOps.readFile(file);
            JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                return index;
            }
            JsonObject o = root.getAsJsonObject();
            if (!o.has("version") || o.get("version").getAsInt() != VERSION || !o.has("files")) {
                return index;
            }
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("files").entrySet()) {
                JsonObject v = e.getValue().getAsJsonObject();
                Entry entry = new Entry();
                entry.size = v.get("size").getAsLong();
                entry.mtime = v.get("mtime").getAsLong();
                entry.md5 = v.get("md5").getAsString();
                entry.distMd5 = Distribution.string(v, "distMd5");
                entry.overlayId = Distribution.string(v, "overlayId");
                index.entries.put(e.getKey(), entry);
            }
        } catch (IOException | RuntimeException e) {
            index.entries.clear();
        }
        return index;
    }

    synchronized Entry get(String key) {
        return entries.get(key);
    }

    /** The recorded MD5 when size and mtime still match, else null. */
    synchronized String trusted(String key, long size, long mtime) {
        Entry e = entries.get(key);
        if (e == null || e.size != size || e.mtime != mtime) {
            return null;
        }
        return e.md5;
    }

    /** What a hash just read on disk. Other bytes than recorded forget the overlay record. */
    synchronized void observe(String key, long size, long mtime, String md5) {
        Entry e = entries.get(key);
        if (e == null) {
            e = new Entry();
            entries.put(key, e);
        }
        if (e.md5 == null || !e.md5.equals(md5)) {
            e.distMd5 = null;
            e.overlayId = null;
        }
        e.size = size;
        e.mtime = mtime;
        e.md5 = md5;
        dirty = true;
    }

    /** A file the engine just wrote. */
    synchronized void record(String key, File file, String md5, String distMd5, String overlayId) {
        Entry e = new Entry();
        e.size = file.length();
        e.mtime = file.lastModified();
        e.md5 = md5;
        e.distMd5 = distMd5;
        e.overlayId = overlayId;
        entries.put(key, e);
        dirty = true;
    }

    synchronized void remove(String key) {
        if (entries.remove(key) != null) {
            dirty = true;
        }
    }

    /** Forgets every key not in the set. */
    synchronized void retainOnly(Set<String> keys) {
        if (entries.keySet().retainAll(keys)) {
            dirty = true;
        }
    }

    synchronized boolean isDirty() {
        return dirty;
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized void save(File file) throws IOException {
        List<String> keys = new ArrayList<String>(entries.keySet());
        Collections.sort(keys);
        StringWriter text = new StringWriter(Math.max(1024, keys.size() * 160));
        JsonWriter w = new JsonWriter(text);
        w.beginObject();
        w.name("version").value(VERSION);
        w.name("files").beginObject();
        for (String key : keys) {
            Entry e = entries.get(key);
            w.name(key).beginObject();
            w.name("size").value(e.size);
            w.name("mtime").value(e.mtime);
            w.name("md5").value(e.md5);
            if (e.distMd5 != null) {
                w.name("distMd5").value(e.distMd5);
            }
            if (e.overlayId != null) {
                w.name("overlayId").value(e.overlayId);
            }
            w.endObject();
        }
        w.endObject();
        w.endObject();
        w.close();
        FileOps.writeAtomic(file, text.toString().getBytes(StandardCharsets.UTF_8));
        dirty = false;
    }
}
