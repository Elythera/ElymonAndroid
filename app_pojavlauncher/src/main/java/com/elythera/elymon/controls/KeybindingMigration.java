package com.elythera.elymon.controls;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Brings the key bindings of an existing options.txt to the Android ones. Pure Java.
 *
 * <p>The Android key bindings reach the game through the overlay of
 * config/defaultoptions/keybindings.txt (assets/elymon/android-policy.json). The DefaultOptions
 * mod reads that file at every start and makes its keys the new defaults, but it moves a
 * binding only when options.txt has no key_ line for it yet
 * (DefaultOptionsInitializer.collectSeenKeys marks every such line "seen") and the binding is
 * still on the mod's own default (KeyMappingDefaultsHandler.loadDefaults). Minecraft writes a
 * key_ line for every binding the first time it saves options.txt, so:
 * <ul>
 * <li>a fresh install gets the Android keys from DefaultOptions at its first start (the sync
 * seeds options.txt without any key_ line);</li>
 * <li>an install that already played never gets a changed key that way. This class does it:
 * a key_ line whose value is one the key had by default before (listed in
 * assets/elymon/controls-keybindings.json: the pack's keybindings.txt, the mod's default,
 * earlier Android values) is set to the Android value; a value the player chose stays.</li>
 * </ul>
 * It runs once per version of the overlay (see {@link #id}), so a player who later picks one
 * of those old values on purpose keeps it.
 */
final class KeybindingMigration {
    static final String KEYBINDINGS_PATH = "config/defaultoptions/keybindings.txt";
    private static final String PREFIX = "key_";

    /** Mapping name (key.cobblemon.summary) to the options.txt value (key.keyboard.m or key.keyboard.k:CONTROL). */
    final Map<String, String> desired;
    /** Mapping name to the values that mean "never chosen by the player". */
    final Map<String, Set<String>> previous;
    /** Changes whenever desired or previous change. */
    final String id;

    static final class Result {
        final String text;
        final List<String> changed;

        Result(String text, List<String> changed) {
            this.text = text;
            this.changed = Collections.unmodifiableList(changed);
        }
    }

    private KeybindingMigration(Map<String, String> desired, Map<String, Set<String>> previous) {
        this.desired = Collections.unmodifiableMap(desired);
        this.previous = Collections.unmodifiableMap(previous);
        StringBuilder canonical = new StringBuilder("elymon-keybindings-v1\n");
        for (Map.Entry<String, String> e : new TreeMap<String, String>(desired).entrySet()) {
            canonical.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            Set<String> old = previous.get(e.getKey());
            if (old != null) {
                List<String> sorted = new ArrayList<String>(old);
                Collections.sort(sorted);
                for (String value : sorted) {
                    canonical.append(" <").append(value).append('\n');
                }
            }
        }
        this.id = sha256(canonical.toString());
    }

    /**
     * Reads the keybindings overlay of the Android policy and the history of
     * controls-keybindings.json ({"version": 1, "previous": {"name": ["value", ...]}}).
     */
    static KeybindingMigration parse(String policyJson, String historyJson) {
        Map<String, String> desired = new LinkedHashMap<String, String>();
        JsonObject policy = JsonParser.parseString(policyJson).getAsJsonObject();
        if (policy.has("overlays") && policy.get("overlays").isJsonArray()) {
            for (JsonElement element : policy.getAsJsonArray("overlays")) {
                JsonObject overlay = element.getAsJsonObject();
                if (!KEYBINDINGS_PATH.equals(string(overlay, "path")) || !overlay.has("ops")) {
                    continue;
                }
                for (JsonElement opElement : overlay.getAsJsonArray("ops")) {
                    JsonObject op = opElement.getAsJsonObject();
                    if (!"colonKeySet".equals(string(op, "op")) || !op.has("set")) {
                        continue;
                    }
                    for (Map.Entry<String, JsonElement> e : op.getAsJsonObject("set").entrySet()) {
                        if (e.getKey().startsWith(PREFIX) && e.getKey().length() > PREFIX.length()) {
                            desired.put(e.getKey().substring(PREFIX.length()), optionsValue(e.getValue().getAsString()));
                        }
                    }
                }
            }
        }

        Map<String, Set<String>> previous = new LinkedHashMap<String, Set<String>>();
        JsonObject history = JsonParser.parseString(historyJson).getAsJsonObject();
        if (history.has("version") && history.get("version").getAsInt() != 1) {
            throw new IllegalArgumentException("unsupported keybinding history version");
        }
        if (history.has("previous") && history.get("previous").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : history.getAsJsonObject("previous").entrySet()) {
                Set<String> values = new HashSet<String>();
                JsonArray array = e.getValue().getAsJsonArray();
                for (JsonElement value : array) {
                    values.add(value.getAsString().trim());
                }
                previous.put(e.getKey(), values);
            }
        }
        return new KeybindingMigration(desired, previous);
    }

    /**
     * keybindings.txt writes "key.keyboard.m:" (no modifier) or "key.keyboard.k:CONTROL";
     * options.txt writes "key.keyboard.m" and "key.keyboard.k:CONTROL".
     */
    static String optionsValue(String keybindingsValue) {
        String value = keybindingsValue.trim();
        int colon = value.indexOf(':');
        if (colon < 0) {
            return value;
        }
        String modifiers = value.substring(colon + 1).trim();
        return modifiers.isEmpty() ? value.substring(0, colon) : value.substring(0, colon) + ":" + modifiers;
    }

    /** The options.txt text with the bindings moved; the line endings are kept. */
    Result reconcile(String optionsText) {
        List<String> changed = new ArrayList<String>();
        if (optionsText.isEmpty() || desired.isEmpty()) {
            return new Result(optionsText, changed);
        }
        String eol = optionsText.contains("\r\n") ? "\r\n" : "\n";
        boolean trailing = optionsText.endsWith("\n");
        String[] parts = optionsText.split("\n", -1);
        int count = trailing ? parts.length - 1 : parts.length;
        StringBuilder out = new StringBuilder(optionsText.length() + 64);
        for (int i = 0; i < count; i++) {
            String line = parts[i];
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            line = migrateLine(line, changed);
            if (i > 0) {
                out.append(eol);
            }
            out.append(line);
        }
        if (trailing && count > 0) {
            out.append(eol);
        }
        return new Result(changed.isEmpty() ? optionsText : out.toString(), changed);
    }

    private String migrateLine(String line, List<String> changed) {
        if (!line.startsWith(PREFIX)) {
            return line;
        }
        int colon = line.indexOf(':');
        if (colon <= PREFIX.length()) {
            return line;
        }
        String name = line.substring(PREFIX.length(), colon);
        String wanted = desired.get(name);
        if (wanted == null) {
            return line;
        }
        String current = line.substring(colon + 1).trim();
        if (current.equals(wanted)) {
            return line;
        }
        Set<String> old = previous.get(name);
        if (old == null || !old.contains(current)) {
            return line; // chosen by the player
        }
        changed.add(name);
        return PREFIX + name + ":" + wanted;
    }

    private static String string(JsonObject o, String name) {
        return o.has(name) && o.get(name).isJsonPrimitive() ? o.get(name).getAsString() : null;
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
