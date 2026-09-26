package com.elythera.elymon.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What Android does differently from the desktop launcher, read from
 * assets/elymon/android-policy.json:
 * <pre>
 * {
 *   "version": 1,
 *   "excludeMods": ["iris", ...],                 // mod id = 2nd segment of "generated.forgemod:&lt;id&gt;:&lt;ver&gt;@jar"
 *   "excludeFilePrefixes": ["shaderpacks/", ...], // File modules whose artifact.path starts with one (case ignored)
 *   "overlays": [                                 // edits applied after download, in order
 *     {"path": "config/fml.toml", "ops": [{"op": "tomlSet", "set": {"earlyWindowControl": "false"}}]},
 *     {"path": "config/defaultoptions/keybindings.txt", "ops": [{"op": "colonKeySet", "set": {}}]}
 *   ],
 *   "seedOptions": {"lang": "fr_fr", ...}         // values written into a missing or empty options.txt
 * }
 * </pre>
 * Values in "set" and "seedOptions" are written as they are: a TOML string
 * needs its quotes ("\"text\""), an options.txt string too.
 */
final class AndroidPolicy {
    static final int VERSION = 1;

    final Set<String> excludeMods;
    final List<String> excludeFilePrefixes;
    /** Case-folded path to overlay. Overlays that change nothing are left out. */
    final Map<String, Overlay> overlays;
    final Map<String, String> seedOptions;

    private AndroidPolicy(Set<String> excludeMods, List<String> excludeFilePrefixes,
                          Map<String, Overlay> overlays, Map<String, String> seedOptions) {
        this.excludeMods = excludeMods;
        this.excludeFilePrefixes = excludeFilePrefixes;
        this.overlays = overlays;
        this.seedOptions = seedOptions;
    }

    static AndroidPolicy empty() {
        return new AndroidPolicy(Collections.<String>emptySet(), Collections.<String>emptyList(),
                Collections.<String, Overlay>emptyMap(), Collections.<String, String>emptyMap());
    }

    /** Parses the policy; null or blank text is an empty policy. */
    static AndroidPolicy parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return empty();
        }
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("policy is not an object");
        }
        JsonObject o = root.getAsJsonObject();
        if (o.has("version") && o.get("version").getAsInt() != VERSION) {
            throw new IllegalArgumentException("unsupported policy version");
        }

        Set<String> mods = new HashSet<String>();
        for (String id : strings(o, "excludeMods")) {
            mods.add(id.toLowerCase(Locale.ROOT));
        }

        List<String> prefixes = new ArrayList<String>();
        for (String prefix : strings(o, "excludeFilePrefixes")) {
            if (!prefix.isEmpty()) {
                prefixes.add(prefix.toLowerCase(Locale.ROOT));
            }
        }

        Map<String, Overlay> overlays = new LinkedHashMap<String, Overlay>();
        if (o.has("overlays") && !o.get("overlays").isJsonNull()) {
            for (JsonElement e : o.getAsJsonArray("overlays")) {
                JsonObject entry = e.getAsJsonObject();
                String rawPath = entry.get("path").getAsString();
                String path = PathGuard.normalize(rawPath);
                if (path == null) {
                    throw new IllegalArgumentException("overlay path is not safe");
                }
                List<Overlay.Op> ops = new ArrayList<Overlay.Op>();
                if (entry.has("ops")) {
                    for (JsonElement opElement : entry.getAsJsonArray("ops")) {
                        JsonObject op = opElement.getAsJsonObject();
                        String kind = op.get("op").getAsString();
                        if (!Overlay.TOML_SET.equals(kind) && !Overlay.COLON_KEY_SET.equals(kind)) {
                            throw new IllegalArgumentException("unknown overlay op " + kind);
                        }
                        Map<String, String> set = stringMap(op, "set");
                        if (!set.isEmpty()) {
                            ops.add(new Overlay.Op(kind, set));
                        }
                    }
                }
                if (ops.isEmpty()) {
                    continue;
                }
                String key = PathGuard.fold(path);
                Overlay previous = overlays.get(key);
                if (previous != null) {
                    List<Overlay.Op> merged = new ArrayList<Overlay.Op>(previous.ops);
                    merged.addAll(ops);
                    ops = merged;
                }
                overlays.put(key, new Overlay(path, ops));
            }
        }

        Map<String, String> seed = stringMap(o, "seedOptions");
        return new AndroidPolicy(Collections.unmodifiableSet(mods), Collections.unmodifiableList(prefixes),
                Collections.unmodifiableMap(overlays), Collections.unmodifiableMap(seed));
    }

    boolean isModExcluded(String modId) {
        return modId != null && excludeMods.contains(modId.toLowerCase(Locale.ROOT));
    }

    boolean isFileExcluded(String rel) {
        String folded = PathGuard.fold(rel);
        for (String prefix : excludeFilePrefixes) {
            if (folded.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    Overlay overlayFor(String rel) {
        return overlays.get(PathGuard.fold(rel));
    }

    private static List<String> strings(JsonObject o, String name) {
        List<String> out = new ArrayList<String>();
        if (!o.has(name) || o.get(name).isJsonNull()) {
            return out;
        }
        JsonArray array = o.getAsJsonArray(name);
        for (JsonElement e : array) {
            out.add(e.getAsString());
        }
        return out;
    }

    /** A JSON object of primitives as ordered strings (true becomes "true", 8 becomes "8"). */
    private static Map<String, String> stringMap(JsonObject o, String name) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (!o.has(name) || o.get(name).isJsonNull()) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject(name).entrySet()) {
            if (e.getKey().isEmpty() || e.getKey().indexOf('\n') >= 0 || e.getKey().indexOf('\r') >= 0) {
                throw new IllegalArgumentException("invalid key in " + name);
            }
            String value = e.getValue().getAsString();
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("multi-line value in " + name);
            }
            out.put(e.getKey(), value);
        }
        return out;
    }
}
