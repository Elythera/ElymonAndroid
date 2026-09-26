package com.elythera.elymon.sync;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Edits applied to a distribution file after it is downloaded, so that an
 * Android-specific value (fml.toml earlyWindowControl, keybindings) survives
 * the MD5 tracking: the index remembers the distribution MD5 the edit started
 * from and the MD5 it produced, so the file is not downloaded again at every
 * Play.
 *
 * Both operations are line based and keep the file's line endings.
 * <ul>
 * <li>{@code tomlSet}: {@code key = value}. A key "table.key" goes into
 * {@code [table]} when that header exists; otherwise the whole key is looked up
 * before the first header. Missing keys are added. Multi-line values are not
 * supported.</li>
 * <li>{@code colonKeySet}: {@code key:value} lines (options.txt,
 * DefaultOptions keybindings.txt); the key is what precedes the first colon.
 * Missing keys are appended.</li>
 * </ul>
 */
final class Overlay {
    static final String TOML_SET = "tomlSet";
    static final String COLON_KEY_SET = "colonKeySet";

    static final class Op {
        final String kind;
        final Map<String, String> set;

        Op(String kind, Map<String, String> set) {
            this.kind = kind;
            this.set = Collections.unmodifiableMap(set);
        }
    }

    final String path;
    final List<Op> ops;
    /** Changes whenever the edits change, so that a new policy re-applies them. */
    final String id;

    Overlay(String path, List<Op> ops) {
        this.path = path;
        this.ops = Collections.unmodifiableList(new ArrayList<Op>(ops));
        StringBuilder canonical = new StringBuilder("elymon-overlay-v1\n");
        for (Op op : ops) {
            canonical.append(op.kind).append('\n');
            for (Map.Entry<String, String> e : op.set.entrySet()) {
                canonical.append(e.getKey()).append('\u0000').append(e.getValue()).append('\n');
            }
        }
        this.id = FileOps.md5(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The edited bytes. The input is read as UTF-8. */
    byte[] apply(byte[] original) {
        String text = new String(original, StandardCharsets.UTF_8);
        for (Op op : ops) {
            if (TOML_SET.equals(op.kind)) {
                text = tomlSet(text, op.set);
            } else {
                text = colonKeySet(text, op.set);
            }
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ lines

    /** A text split into lines, remembering its line ending. */
    static final class Lines {
        final List<String> lines = new ArrayList<String>();
        String eol = "\n";
        boolean trailingEol = true;

        static Lines parse(String text) {
            Lines l = new Lines();
            if (text.isEmpty()) {
                return l;
            }
            if (text.contains("\r\n")) {
                l.eol = "\r\n";
            }
            l.trailingEol = text.endsWith("\n");
            String[] parts = text.split("\n", -1);
            int count = l.trailingEol ? parts.length - 1 : parts.length;
            for (int i = 0; i < count; i++) {
                String line = parts[i];
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                l.lines.add(line);
            }
            return l;
        }

        String join() {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    out.append(eol);
                }
                out.append(lines.get(i));
            }
            if (trailingEol && !lines.isEmpty()) {
                out.append(eol);
            }
            return out.toString();
        }
    }

    /** Replaces "key:..." lines whose key is in the map, appends the others. */
    static String colonKeySet(String text, Map<String, String> set) {
        if (set.isEmpty()) {
            return text;
        }
        Lines l = Lines.parse(text);
        Set<String> done = new HashSet<String>();
        for (int i = 0; i < l.lines.size(); i++) {
            String line = l.lines.get(i);
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon);
            String value = set.get(key);
            if (value != null) {
                l.lines.set(i, key + ":" + value);
                done.add(key);
            }
        }
        for (Map.Entry<String, String> e : set.entrySet()) {
            if (!done.contains(e.getKey())) {
                l.lines.add(e.getKey() + ":" + e.getValue());
            }
        }
        return l.join();
    }

    /** Sets TOML "key = value" lines, adding the missing ones. */
    static String tomlSet(String text, Map<String, String> set) {
        if (set.isEmpty()) {
            return text;
        }
        Lines l = Lines.parse(text);
        for (Map.Entry<String, String> e : set.entrySet()) {
            String fullKey = e.getKey();
            String table = null;
            String key = fullKey;
            int dot = fullKey.lastIndexOf('.');
            if (dot > 0 && findHeader(l.lines, fullKey.substring(0, dot)) >= 0) {
                table = fullKey.substring(0, dot);
                key = fullKey.substring(dot + 1);
            }
            int start;
            int end;
            if (table == null) {
                start = 0;
                end = nextHeader(l.lines, 0);
            } else {
                start = findHeader(l.lines, table) + 1;
                end = nextHeader(l.lines, start);
            }
            boolean replaced = false;
            for (int i = start; i < end; i++) {
                String line = l.lines.get(i);
                if (isTomlKeyLine(line, key)) {
                    String indent = line.substring(0, line.length() - trimStart(line).length());
                    l.lines.set(i, indent + key + " = " + e.getValue());
                    replaced = true;
                }
            }
            if (!replaced) {
                int insertAt = end;
                while (insertAt > start && l.lines.get(insertAt - 1).trim().isEmpty()) {
                    insertAt--;
                }
                l.lines.add(insertAt, key + " = " + e.getValue());
            }
        }
        return l.join();
    }

    private static boolean isTomlKeyLine(String line, String key) {
        String t = trimStart(line);
        if (!t.startsWith(key)) {
            return false;
        }
        String rest = t.substring(key.length());
        int i = 0;
        while (i < rest.length() && (rest.charAt(i) == ' ' || rest.charAt(i) == '\t')) {
            i++;
        }
        return i < rest.length() && rest.charAt(i) == '=';
    }

    private static String trimStart(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return s.substring(i);
    }

    private static boolean isHeader(String line) {
        return line.trim().startsWith("[");
    }

    private static String headerName(String line) {
        String t = line.trim();
        int close = t.indexOf(']');
        if (close < 0) {
            return null;
        }
        String inner = t.substring(0, close).replace("[", "").trim();
        return inner;
    }

    private static int findHeader(List<String> lines, String table) {
        for (int i = 0; i < lines.size(); i++) {
            if (isHeader(lines.get(i)) && table.equals(headerName(lines.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    private static int nextHeader(List<String> lines, int from) {
        for (int i = from; i < lines.size(); i++) {
            if (isHeader(lines.get(i))) {
                return i;
            }
        }
        return lines.size();
    }
}
