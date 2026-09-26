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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * &lt;instance&gt;/elymon_managed.json: the instance files (mods included) this
 * engine deposited, with the MD5 of the bytes it deposited, most recent first.
 * It is what allows pruning: a file is deleted only if it is listed here, the
 * distribution no longer places it, and its bytes are still one of these MD5.
 * Same discipline as the desktop's distro_files.json (instancefiles.js):
 * <ul>
 * <li>a path is claimed before its bytes land, so a sync killed at any point
 * finds its files listed next time;</li>
 * <li>the file is written atomically, and an unreadable one is set aside as
 * .corrupt and never trusted in part.</li>
 * </ul>
 * <pre>{"version": 1, "files": {"mods/alpha-1.0.jar": ["md5", …]}}</pre>
 * Paths keep the distribution's spelling and are compared case-folded
 * (Android/data ignores case).
 */
final class ManagedManifest {
    static final String FILE_NAME = "elymon_managed.json";
    private static final int VERSION = 1;
    /** Like MAX_HASHES_PER_FILE on the desktop. */
    static final int MAX_HASHES = 4;
    private static final Pattern MD5 = Pattern.compile("^[0-9a-f]{32}$");

    /** Folded key to [original path, hashes...]. */
    private final Map<String, Entry> files = new LinkedHashMap<String, Entry>();
    private String persisted;

    static final class Entry {
        final String path;
        final List<String> hashes = new ArrayList<String>();

        Entry(String path) {
            this.path = path;
        }
    }

    /** Reads the manifest; an unreadable one is renamed to .corrupt and an empty one returned. */
    static ManagedManifest load(File instanceDir, List<String> warnings) {
        ManagedManifest m = new ManagedManifest();
        File file = new File(instanceDir, FILE_NAME);
        if (!file.exists()) {
            return m;
        }
        try {
            String text = new String(FileOps.readFile(file), StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            if (o.get("version").getAsInt() != VERSION) {
                throw new IllegalStateException("version");
            }
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("files").entrySet()) {
                String path = PathGuard.normalize(e.getKey());
                if (path == null || !path.equals(e.getKey())) {
                    throw new IllegalStateException("key");
                }
                Entry entry = new Entry(path);
                for (JsonElement h : e.getValue().getAsJsonArray()) {
                    String hash = h.getAsString();
                    if (!MD5.matcher(hash).matches()) {
                        throw new IllegalStateException("md5");
                    }
                    entry.hashes.add(hash);
                }
                m.files.put(PathGuard.fold(path), entry);
            }
            m.persisted = m.serialize();
        } catch (IOException | RuntimeException e) {
            m.files.clear();
            m.persisted = null;
            File corrupt = new File(instanceDir, FILE_NAME + ".corrupt");
            corrupt.delete();
            if (!file.renameTo(corrupt)) {
                file.delete();
            }
            warnings.add(FILE_NAME + " was unreadable and was set aside as " + FILE_NAME + ".corrupt");
        }
        return m;
    }

    Entry get(String rel) {
        return files.get(PathGuard.fold(rel));
    }

    /** Puts an MD5 first in the list of a path. */
    void claim(String rel, String md5) {
        String key = PathGuard.fold(rel);
        Entry old = files.get(key);
        Entry entry = new Entry(rel);
        entry.hashes.add(md5);
        if (old != null) {
            for (String h : old.hashes) {
                if (!entry.hashes.contains(h) && entry.hashes.size() < MAX_HASHES) {
                    entry.hashes.add(h);
                }
            }
        }
        files.put(key, entry);
    }

    void release(String rel) {
        files.remove(PathGuard.fold(rel));
    }

    List<String> paths() {
        List<String> out = new ArrayList<String>();
        for (Entry e : files.values()) {
            out.add(e.path);
        }
        return out;
    }

    boolean isEmpty() {
        return files.isEmpty();
    }

    String serialize() {
        List<Entry> sorted = new ArrayList<Entry>(files.values());
        Collections.sort(sorted, new java.util.Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.path.compareTo(b.path);
            }
        });
        try {
            StringWriter text = new StringWriter();
            JsonWriter w = new JsonWriter(text);
            w.setIndent("  ");
            w.beginObject();
            w.name("version").value(VERSION);
            w.name("files").beginObject();
            for (Entry e : sorted) {
                w.name(e.path).beginArray();
                for (String h : e.hashes) {
                    w.value(h);
                }
                w.endArray();
            }
            w.endObject();
            w.endObject();
            w.close();
            return text.toString() + "\n";
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Writes the manifest when its content changed. */
    void save(File instanceDir) throws IOException {
        String text = serialize();
        if (text.equals(persisted)) {
            return;
        }
        if (persisted == null && files.isEmpty()) {
            return;
        }
        FileOps.writeAtomic(new File(instanceDir, FILE_NAME), text.getBytes(StandardCharsets.UTF_8));
        persisted = text;
    }
}
