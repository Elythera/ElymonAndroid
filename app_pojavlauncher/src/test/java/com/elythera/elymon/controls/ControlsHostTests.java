package com.elythera.elymon.controls;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.objecthunter.exp4j.ExpressionBuilder;
import net.objecthunter.exp4j.function.Function;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Host tests of the controls package, run by tools/elymon/test-controls.sh (no JUnit, no Android):
 * <pre>
 * java com.elythera.elymon.controls.ControlsHostTests --layout FILE --policy FILE --history FILE
 *      --pack-keybindings FILE --positions FILE --tmp DIR
 * </pre>
 * <ol>
 * <li>LayoutInstaller: first install, update, a layout the player edited;</li>
 * <li>KeybindingMigration against the real policy and history, on an options.txt such as the
 * first builds left, and on a fresh install;</li>
 * <li>every position expression of the shipped layout recomputed with the real exp4j jar
 * (libs/exp4j-0.4.9-SNAPSHOT.jar) the way ControlData.insertDynamicPos does, against what
 * tools/elymon/check-controls.py computed (--positions).</li>
 * </ol>
 */
public final class ControlsHostTests {
    private static int passed;
    private static final List<String> failures = new ArrayList<String>();
    private static String section = "";

    private ControlsHostTests() {}

    public static void main(String[] args) {
        Map<String, String> a = new HashMap<String, String>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            a.put(args[i].substring(2), args[i + 1]);
        }
        try {
            layoutInstaller(new File(a.get("layout")), new File(a.get("tmp")));
        } catch (Throwable t) {
            fail("layout installer", t);
        }
        try {
            keybindings(new File(a.get("policy")), new File(a.get("history")), new File(a.get("pack-keybindings")));
        } catch (Throwable t) {
            fail("key bindings", t);
        }
        try {
            positions(new File(a.get("layout")), new File(a.get("positions")));
        } catch (Throwable t) {
            fail("positions", t);
        }
        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("CONTROLS TESTS PASSED: " + passed + " checks");
            System.exit(0);
        }
        System.out.println("CONTROLS TESTS FAILED: " + failures.size() + " of " + (passed + failures.size()));
        for (String f : failures) {
            System.out.println(" - " + f);
        }
        System.exit(1);
    }

    // ------------------------------------------------------------------ layout installer

    private static void layoutInstaller(File layoutFile, File tmp) throws IOException {
        section("LayoutInstaller");
        byte[] shipped = read(layoutFile);
        byte[] older = "{\"version\":8,\"older\":true}".getBytes(StandardCharsets.UTF_8);
        byte[] edited = "{\"version\":8,\"edited\":true}".getBytes(StandardCharsets.UTF_8);
        String shippedSha = LayoutInstaller.sha256(shipped);
        String olderSha = LayoutInstaller.sha256(older);
        List<String> known = Arrays.asList(olderSha, shippedSha);

        equal(LayoutInstaller.Action.INSTALLED, LayoutInstaller.decide(null, shippedSha, null, known), "missing: install");
        equal(LayoutInstaller.Action.UP_TO_DATE, LayoutInstaller.decide(shippedSha, shippedSha, null, known), "same: nothing");
        equal(LayoutInstaller.Action.INSTALLED, LayoutInstaller.decide(olderSha, shippedSha, null, known), "an older shipped layout: replace");
        String editedSha = LayoutInstaller.sha256(edited);
        equal(LayoutInstaller.Action.INSTALLED, LayoutInstaller.decide(editedSha, shippedSha, editedSha, known),
                "what the app installed last time: replace");
        equal(LayoutInstaller.Action.KEPT_PLAYER_LAYOUT, LayoutInstaller.decide(editedSha, shippedSha, olderSha, known),
                "edited by the player: keep");
        equal("elymon-2.json", LayoutInstaller.alongsideName("2"), "alongside name");
        equal("elymon-1.0_b.json", LayoutInstaller.alongsideName("1.0/b"), "alongside name is a plain file name");

        File dir = new File(tmp, "controlmap");
        File def = new File(dir, "default.json");
        LayoutInstaller.Result r = LayoutInstaller.apply(dir, shipped, "1", null, known, false);
        equal(LayoutInstaller.Action.INSTALLED, r.action, "first run installs");
        equal(shippedSha, LayoutInstaller.sha256(def), "first run: default.json is the shipped layout");
        equal(shippedSha, r.installedSha, "first run records the SHA");

        r = LayoutInstaller.apply(dir, shipped, "1", r.installedSha, known, false);
        equal(LayoutInstaller.Action.UP_TO_DATE, r.action, "second run: nothing to do");

        write(def, older);
        write(new File(dir, "new_default.json"), older);
        r = LayoutInstaller.apply(dir, shipped, "1", olderSha, known, false);
        equal(LayoutInstaller.Action.INSTALLED, r.action, "update over an untouched layout replaces it");
        equal(shippedSha, LayoutInstaller.sha256(def), "update: default.json is the new layout");
        check(!new File(dir, "new_default.json").exists(), "upstream's new_default.json holding one of our layouts is removed");

        write(new File(dir, "new_default.json"), edited);
        write(def, edited);
        r = LayoutInstaller.apply(dir, shipped, "2", olderSha, known, false);
        equal(LayoutInstaller.Action.KEPT_PLAYER_LAYOUT, r.action, "update over an edited layout keeps it");
        equal(editedSha, LayoutInstaller.sha256(def), "the player's default.json is untouched");
        equal("elymon-2.json", r.alongside, "the new layout is saved beside it");
        check(r.alongsideCreated, "alongside file created");
        equal(shippedSha, LayoutInstaller.sha256(new File(dir, "elymon-2.json")), "alongside file is the shipped layout");
        check(r.installedSha == null, "the remembered SHA stays the one the app wrote");
        check(new File(dir, "new_default.json").exists(), "a new_default.json that is not ours stays");

        r = LayoutInstaller.apply(dir, shipped, "2", olderSha, known, true);
        check(!r.alongsideCreated, "next start: the alongside file is not rewritten");
        check(!new File(dir, "default.json.elymon-tmp").exists(), "no temporary file left");

        // The editor's save dialog offers the loaded file's name: a player who loaded
        // elymon-2.json, changed it and saved it under that name keeps their work.
        byte[] editedAlongside = "{\"version\":8,\"alongside\":\"edited\"}".getBytes(StandardCharsets.UTF_8);
        write(new File(dir, "elymon-2.json"), editedAlongside);
        r = LayoutInstaller.apply(dir, shipped, "2", olderSha, known, true);
        equal(LayoutInstaller.sha256(editedAlongside), LayoutInstaller.sha256(new File(dir, "elymon-2.json")),
                "an edited elymon-2.json is never replaced");
        r = LayoutInstaller.apply(dir, shipped, "2", olderSha, known, false);
        check(!r.alongsideCreated, "not even before the notice was recorded");
        equal(LayoutInstaller.sha256(editedAlongside), LayoutInstaller.sha256(new File(dir, "elymon-2.json")),
                "the edited elymon-2.json is still the player's");
        equal("elymon-2.json", r.alongside, "the notice still names it");

        // Deleted by the player after the notice: not written again.
        check(new File(dir, "elymon-2.json").delete(), "elymon-2.json deleted");
        r = LayoutInstaller.apply(dir, shipped, "2", olderSha, known, true);
        check(!r.alongsideCreated && !new File(dir, "elymon-2.json").exists(),
                "a deleted alongside file is not written again once the player was told");
    }

    // ------------------------------------------------------------------ key bindings

    private static void keybindings(File policyFile, File historyFile, File packFile) throws IOException {
        section("KeybindingMigration");
        String policy = text(policyFile);
        String history = text(historyFile);
        KeybindingMigration m = KeybindingMigration.parse(policy, history);
        check(m.desired.size() > 40, "overlay read from android-policy.json (" + m.desired.size() + " keys)");
        equal("key.keyboard.grave.accent", m.desired.get("key.ftbchunks.map"), "options.txt form without modifier");
        equal("key.mouse.5", m.desired.get("key.push_to_talk"), "push-to-talk on mouse 5");
        equal("key.keyboard.k:CONTROL", KeybindingMigration.optionsValue("key.keyboard.k:CONTROL"), "modifier kept");
        equal("key.keyboard.m", KeybindingMigration.optionsValue("key.keyboard.m:"), "empty modifier dropped");
        for (String name : m.desired.keySet()) {
            check(m.previous.containsKey(name), "history covers " + name);
        }
        equal(m.id, KeybindingMigration.parse(policy, history).id, "id is stable");
        String otherHistory = history.replace("\"key.keyboard.b\"", "\"key.keyboard.b\", \"key.keyboard.f8\"");
        check(!m.id.equals(KeybindingMigration.parse(policy, otherHistory).id), "id changes with the history");

        // What the game wrote after a start with the first builds (empty overlay): every mapping
        // at the pack's keybindings.txt value, or at its mod default when the pack does not list it.
        Map<String, String> pack = new LinkedHashMap<String, String>();
        Pattern keyLine = Pattern.compile("key_([^:]+):([^:]+)(?::(.+)?)?");
        for (String line : text(packFile).split("\r?\n")) {
            Matcher matcher = keyLine.matcher(line.trim());
            if (matcher.matches()) {
                pack.put(matcher.group(1), matcher.group(2) + (matcher.group(3) != null ? ":" + matcher.group(3) : ""));
            }
        }
        Map<String, String> modDefaults = new LinkedHashMap<String, String>();
        modDefaults.put("key.cobblemon_smartphone.scanner", "key.keyboard.c");
        modDefaults.put("key.mute_microphone", "key.keyboard.m");
        modDefaults.put("key.disable_voice_chat", "key.keyboard.n");
        modDefaults.put("key.hide_icons", "key.keyboard.h");
        modDefaults.put("key.voice_chat_group", "key.keyboard.g");
        modDefaults.put("key.ftbchunks.map", "key.keyboard.m");
        modDefaults.put("key.pokebike.headlight", "key.keyboard.o");
        modDefaults.put("key.sophisticatedbackpacks.inventory_interaction", "key.keyboard.c");
        modDefaults.put("key.sophisticatedbackpacks.toggle_upgrade_1", "key.keyboard.z:ALT");
        modDefaults.put("key.sophisticatedbackpacks.toggle_upgrade_2", "key.keyboard.x:ALT");
        modDefaults.put("justzoom.keybinds.keybind.zoom", "key.keyboard.z");
        modDefaults.put("key.push_to_talk", "key.keyboard.unknown");
        StringBuilder played = new StringBuilder("version:3955\r\nlang:fr_fr\r\ntoggleCrouch:true\r\n");
        for (Map.Entry<String, String> e : pack.entrySet()) {
            played.append("key_").append(e.getKey()).append(':').append(e.getValue()).append("\r\n");
        }
        for (Map.Entry<String, String> e : modDefaults.entrySet()) {
            played.append("key_").append(e.getKey()).append(':').append(e.getValue()).append("\r\n");
        }
        played.append("key_key.voice_chat:key.keyboard.v\r\n");
        String before = played.toString()
                // two bindings the player changed by hand: they stay
                .replace("key_key.cobblemon.throwpartypokemon:key.keyboard.r\r\n", "key_key.cobblemon.throwpartypokemon:key.keyboard.k\r\n")
                .replace("key_key.hide_icons:key.keyboard.h\r\n", "key_key.hide_icons:key.keyboard.i\r\n");
        KeybindingMigration.Result r = m.reconcile(before);
        List<String> expected = new ArrayList<String>(Arrays.asList(
                "key.saveToolbarActivator", "key.loadToolbarActivator", "gui.xaero_new_waypoint",
                "key.cobblemon_smartphone.scanner", "key.mute_microphone", "key.disable_voice_chat",
                "key.voice_chat_group", "key.ftbchunks.map", "key.pokebike.headlight",
                "key.sophisticatedbackpacks.inventory_interaction", "key.sophisticatedbackpacks.toggle_upgrade_1",
                "key.sophisticatedbackpacks.toggle_upgrade_2", "justzoom.keybinds.keybind.zoom", "key.push_to_talk"));
        List<String> changed = new ArrayList<String>(r.changed);
        Collections.sort(expected);
        Collections.sort(changed);
        equal(expected, changed, "an install of the first builds: exactly the conflicting bindings move");
        check(r.text.contains("key_key.ftbchunks.map:key.keyboard.grave.accent\r\n"), "FTB map moves off M");
        check(r.text.contains("key_key.sophisticatedbackpacks.toggle_upgrade_1:key.keyboard.unknown\r\n"),
                "Alt+Z backpack toggle unbound (modifier dropped)");
        check(r.text.contains("key_key.push_to_talk:key.mouse.5\r\n"), "push-to-talk bound");
        check(r.text.contains("key_key.cobblemon.throwpartypokemon:key.keyboard.k\r\n"), "the player's send-out key stays");
        check(r.text.contains("key_key.hide_icons:key.keyboard.i\r\n"), "the player's voice-chat key stays");
        check(r.text.startsWith("version:3955\r\nlang:fr_fr\r\ntoggleCrouch:true\r\n"), "other options untouched");
        check(!r.text.replace("\r\n", "").contains("\n"), "CRLF kept");
        equal(before.length() - before.replace("\n", "").length(), r.text.length() - r.text.replace("\n", "").length(),
                "same line count");
        equal(0, m.reconcile(r.text).changed.size(), "a second pass changes nothing");

        String fresh = "version:3955\nlang:fr_fr\ntoggleCrouch:true\n";
        KeybindingMigration.Result none = m.reconcile(fresh);
        check(none.changed.isEmpty() && none.text.equals(fresh),
                "a fresh options.txt (no key_ line) is left to DefaultOptions");
        equal(0, m.reconcile("").changed.size(), "empty file");
    }

    // ------------------------------------------------------------------ exp4j cross-check

    /**
     * Recomputes every position of the checker's dump with exp4j, mirroring
     * ControlData.fillConversionMap / insertDynamicPos and ControlInterface.preProcessProperties
     * (sizes in float, Float.toString, (int) margin).
     */
    private static void positions(File layoutFile, File dump) throws IOException {
        section("Positions: Python checker vs exp4j");
        JsonObject layout = JsonParser.parseString(text(layoutFile)).getAsJsonObject();
        float scaledAt = layout.get("scaledAt").getAsFloat();
        int lines = 0;
        int mismatches = 0;
        for (String line : text(dump).split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split("\t");
            int width = Integer.parseInt(f[0]);
            int height = Integer.parseInt(f[1]);
            final float density = Float.parseFloat(f[2]);
            float scale = Float.parseFloat(f[3]);
            JsonObject data = at(layout, f[4]);
            String expression = data.get(f[5]).getAsString();
            double python = Double.parseDouble(f[6]);

            float w = size(data.get("width").getAsFloat(), density, scaledAt, scale);
            float h = size(data.get("height").getAsFloat(), density, scaledAt, scale);
            Map<String, String> values = new LinkedHashMap<String, String>();
            values.put("top", "0");
            values.put("left", "0");
            values.put("right", Float.toString(width - w));
            values.put("bottom", Float.toString(height - h));
            values.put("width", Float.toString(w));
            values.put("height", Float.toString(h));
            values.put("screen_width", Integer.toString(width));
            values.put("screen_height", Integer.toString(height));
            values.put("margin", Integer.toString((int) (2 * density)));
            values.put("preferred_scale", Float.toString(scale));
            String inserted = expression;
            for (Map.Entry<String, String> e : values.entrySet()) {
                inserted = inserted.replace("${" + e.getKey() + "}", e.getValue());
            }
            double exp4j = new ExpressionBuilder(inserted)
                    .function(new Function("dp", 1) {
                        @Override
                        public double apply(double... args) {
                            return (float) args[0] / density;
                        }
                    })
                    .function(new Function("px", 1) {
                        @Override
                        public double apply(double... args) {
                            return (float) args[0] * density;
                        }
                    })
                    .build().evaluate();
            lines++;
            if (Math.abs(exp4j - python) > 0.05 || Double.isNaN(exp4j) || Double.isInfinite(exp4j)) {
                mismatches++;
                if (mismatches <= 5) {
                    System.out.println("   " + f[4] + " " + f[5] + " on " + width + "x" + height + "@" + density
                            + " size " + scale + ": exp4j " + exp4j + ", checker " + python);
                }
            }
        }
        check(lines > 1000, "the dump holds every expression (" + lines + ")");
        equal(0, mismatches, "exp4j and the checker agree within 0.05 px");
    }

    /** ControlInterface.preProcessProperties: setWidth(getWidth() / scaledAt * PREF_BUTTONSIZE), in float. */
    private static float size(float dp, float density, float scaledAt, float scale) {
        float px = dp * density;
        float scaled = px / scaledAt * scale;
        return (scaled / density) * density;
    }

    private static JsonObject at(JsonObject root, String path) {
        JsonElement current = root;
        for (String part : path.split("/")) {
            if (current.isJsonArray()) {
                JsonArray array = current.getAsJsonArray();
                current = array.get(Integer.parseInt(part));
            } else {
                current = current.getAsJsonObject().get(part);
            }
        }
        return current.getAsJsonObject();
    }

    // ------------------------------------------------------------------ harness

    private static void section(String name) {
        section = name;
        System.out.println("== " + name);
    }

    private static void check(boolean condition, String what) {
        if (condition) {
            passed++;
        } else {
            failures.add(section + ": " + what);
            System.out.println("   FAIL " + what);
        }
    }

    private static void equal(Object expected, Object actual, String what) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (same) {
            passed++;
        } else {
            failures.add(section + ": " + what + " (expected <" + expected + ">, got <" + actual + ">)");
            System.out.println("   FAIL " + what + " (expected <" + expected + ">, got <" + actual + ">)");
        }
    }

    private static void fail(String what, Throwable t) {
        failures.add(section + ": " + what + " threw " + t);
        System.out.println("   FAIL " + what + " threw " + t);
        t.printStackTrace(System.out);
    }

    private static byte[] read(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static String text(File file) throws IOException {
        return new String(read(file), StandardCharsets.UTF_8);
    }

    private static void write(File file, byte[] data) throws IOException {
        file.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }
}
