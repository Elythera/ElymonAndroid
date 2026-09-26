package com.elythera.elymon.sync;

import static com.elythera.elymon.sync.TestSupport.check;
import static com.elythera.elymon.sync.TestSupport.equal;
import static com.elythera.elymon.sync.TestSupport.section;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

/** Pure pieces of the engine: no network, no disk except the repository files read. */
final class SyncUnitTests {
    private SyncUnitTests() {}

    static void run(File policyFile, File stringsFile) throws Exception {
        urls();
        paths();
        maven();
        overlays();
        versions();
        meta();
        policy(policyFile);
        strings(stringsFile);
        planner();
    }

    private static void urls() {
        section("URL normalisation");
        String encoded = "https://cdn.example/files/config/defaultoptions/extra/saves/Tutorial%20World%20v4%20(1.7.2)/data/map_0.dat";
        equal(encoded, Http.normalizeUrl(encoded), "an URL already percent-encoded is left alone");
        equal("https://cdn.example/files/My%20Folder%20(1)/a%20b+c.txt",
                Http.normalizeUrl("https://cdn.example/files/My Folder (1)/a b+c.txt"), "raw spaces are encoded, + and () kept");
        equal("https://cdn.example/%C3%A9t%C3%A9.txt", Http.normalizeUrl("https://cdn.example/été.txt"), "non-ASCII is UTF-8 encoded");
        equal("https://cdn.example/100%25.txt", Http.normalizeUrl("https://cdn.example/100%.txt"), "a lone % is encoded");
        equal(100L, Http.contentRangeStart("bytes 100-199/200"), "Content-Range start");
        equal(-1L, Http.contentRangeStart("items 1-2/3"), "unreadable Content-Range");
    }

    private static void paths() {
        section("Path guard");
        String[] escaping = {"../x", "a/../b", "a/..", "/etc/passwd", "C:/x", "c:x", "a\\b", "a\u0000b", "a\nb"};
        for (String p : escaping) {
            check(PathGuard.isEscaping(p), "escaping: " + p.replace("\u0000", "\\0").replace("\n", "\\n"));
        }
        String[] fine = {"config/a b (1)/c+d.txt", "a..b/c", "mods/x.jar", "config//x", "./config/x"};
        for (String p : fine) {
            check(!PathGuard.isEscaping(p), "not escaping: " + p);
        }
        equal("a/b/c", PathGuard.normalize("a//b/./c"), "empty and dot segments dropped");
        equal(null, PathGuard.normalize("./"), "nothing left is refused");
        String[] protectedPaths = {"saves/World/level.dat", "Saves/x", "screenshots/a.png", "logs/latest.log",
                "crash-reports/c.txt", "debug/x", "backups/b.zip", "elythera/session.json", "options.txt",
                "Options.txt", "optionsof.txt", "optionsshaders.txt", "servers.dat", "servers.dat_old", "hotbar.nbt",
                "usercache.json", "usernamecache.json", "realms_persistence.json", "command_history.txt",
                "elymon_managed.json", "elymon_managed.json.tmp", "elymon_managed.json.corrupt", "distro_mods.json",
                "distro_files.json"};
        for (String p : protectedPaths) {
            check(PathGuard.isNeverTouched(p), "never touched: " + p);
        }
        String[] managed = {"config/options.txt", "mods/x.jar", "config/saves/x", "resourcepacks/a.zip"};
        for (String p : managed) {
            check(!PathGuard.isNeverTouched(p), "may be managed: " + p);
        }
        check(PathGuard.isPlainName("neoforge-21.1.249"), "plain version id");
        check(!PathGuard.isPlainName("../x") && !PathGuard.isPlainName("a/b") && !PathGuard.isPlainName("..")
                && !PathGuard.isPlainName("C:x"), "unsafe names refused");
    }

    private static void maven() {
        section("Maven names");
        Planner.Maven m = Planner.Maven.parse("generated.forgemod:accessories:1.1.0-beta.53+1.21.1@jar");
        equal("accessories-1.1.0-beta.53+1.21.1.jar", m.fileName(), "ForgeMod file name");
        equal("generated/forgemod/accessories/1.1.0-beta.53+1.21.1/accessories-1.1.0-beta.53+1.21.1.jar", m.path(),
                "ForgeMod Maven path");
        equal("net/neoforged/neoforge/21.1.249/neoforge-21.1.249-universal.jar",
                Planner.Maven.parse("net.neoforged:neoforge:21.1.249:universal").path(), "classifier");
        equal("b-1.zip", Planner.Maven.parse("a:b:1@zip").fileName(), "extension");
        equal(null, Planner.Maven.parse("21.1.249"), "a version id is not a Maven id");
    }

    private static void overlays() {
        section("Overlays");
        String fml = "#Disables File Watcher.\ndisableConfigWatcher = false\n#Should we control the window.\n"
                + "earlyWindowControl = true\nmaxThreads = -1\ndependencyOverrides = {}\n";
        Map<String, String> set = new LinkedHashMap<String, String>();
        set.put("earlyWindowControl", "false");
        String out = Overlay.tomlSet(fml, set);
        equal(fml.replace("earlyWindowControl = true", "earlyWindowControl = false"), out, "tomlSet replaces in place");
        equal(out, Overlay.tomlSet(out, set), "tomlSet is idempotent");
        Map<String, String> add = new LinkedHashMap<String, String>();
        add.put("newKey", "\"x\"");
        add.put("client.fov", "90");
        String toml = "a = 1\n\n[client]\nfov = 70\n\n[server]\nb = 2\n";
        equal("a = 1\nnewKey = \"x\"\n\n[client]\nfov = 90\n\n[server]\nb = 2\n", Overlay.tomlSet(toml, add),
                "tomlSet adds a top-level key before the first table and edits a table key");
        equal("x = 1\r\ny = 2\r\n", Overlay.tomlSet("x = 1\r\ny = 3\r\n", single("y", "2")), "tomlSet keeps CRLF");
        check(!Overlay.tomlSet("earlyWindowControlX = true\n", single("earlyWindowControl", "false"))
                .contains("earlyWindowControlX = false"), "tomlSet does not touch a longer key");

        String options = "version:3955\r\nlang:en_us\r\nrenderDistance:12\r\n";
        Map<String, String> seed = new LinkedHashMap<String, String>();
        seed.put("lang", "fr_fr");
        seed.put("renderDistance", "8");
        seed.put("toggleCrouch", "true");
        equal("version:3955\r\nlang:fr_fr\r\nrenderDistance:8\r\ntoggleCrouch:true\r\n", Overlay.colonKeySet(options, seed),
                "colonKeySet replaces, appends and keeps CRLF");
        equal("key_key.jump:key.keyboard.x:\nkey_key.use:key.mouse.right:\n",
                Overlay.colonKeySet("key_key.jump:key.keyboard.space:\nkey_key.use:key.mouse.right:\n",
                        single("key_key.jump", "key.keyboard.x:")), "colonKeySet handles DefaultOptions keybindings");
        equal("lang:fr_fr\n", Overlay.colonKeySet("", single("lang", "fr_fr")), "colonKeySet on an empty file");

        java.util.List<Overlay.Op> ops = new java.util.ArrayList<Overlay.Op>();
        ops.add(new Overlay.Op(Overlay.TOML_SET, set));
        Overlay a = new Overlay("config/fml.toml", ops);
        java.util.List<Overlay.Op> ops2 = new java.util.ArrayList<Overlay.Op>();
        ops2.add(new Overlay.Op(Overlay.TOML_SET, single("earlyWindowControl", "true")));
        check(!a.id.equals(new Overlay("config/fml.toml", ops2).id), "the overlay id changes with its content");
        check(a.id.equals(new Overlay("config/fml.toml", ops).id), "the overlay id is stable");
    }

    private static Map<String, String> single(String k, String v) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put(k, v);
        return m;
    }

    private static void versions() {
        section("Version order");
        equal(0, VersionOrder.compare("1.4.0-android.12", "1.4.0"), "qualifier ignored");
        check(VersionOrder.atLeast("1.4.0-android.3", "1.3.1"), "1.4.0-android.3 >= 1.3.1");
        check(!VersionOrder.atLeast("1.3.0", "1.3.1"), "1.3.0 < 1.3.1");
        check(VersionOrder.compare("1.10", "1.9") > 0, "numeric, not lexical");
        check(!VersionOrder.isComparable("v1.3.0"), "v1.3.0 is not comparable");
        check(VersionOrder.atLeast("1.0.0", "banana"), "an unreadable floor fails open");
        equal(0, VersionOrder.compare("1.2.0-beta.3", "1.2.0"), "1.2.0-beta.3 equals 1.2.0");
    }

    private static void meta() {
        section("Server meta");
        long now = Distribution.date("2026-09-26T12:00:00Z");
        JsonObject root = JsonParser.parseString("{\"servers\":[],"
                + "\"requires\":{\"launcher\":\"1.5.0\",\"message\":\"root msg\",\"url\":\"https://elythera.com/root\"},"
                + "\"maintenance\":{\"enabled\":true,\"message\":\"  Travaux\\u0007 en cours  \"}}").getAsJsonObject();
        JsonObject server = JsonParser.parseString("{\"id\":\"elymon-1.21.1\",\"name\":\"Elymon\",\"version\":\"2.4.6\","
                + "\"address\":\"amp.elythera.com:25573\",\"minecraftVersion\":\"1.21.1\","
                + "\"icon\":\"https://cdn.elythera.com/elylauncher/elymon-icon.png\","
                + "\"availability\":{\"enabled\":true,\"message\":\"Attrapez les tous !\",\"end\":\"2026-09-01T00:00:00Z\"},"
                + "\"requires\":{\"launcher\":\"1.3.1\",\"android\":\"0.2.0\",\"message\":\"server msg\",\"url\":\"http://insecure\"},"
                + "\"background\":{\"image\":\"https://cdn.elythera.com/elylauncher/elymon-background2.png\"}}").getAsJsonObject();
        ServerMeta m = Distribution.meta(root, server, true, now);
        equal("Elymon", m.name, "name");
        equal("amp.elythera.com:25573", m.address, "address");
        equal("2.4.6", m.packVersion, "pack version");
        equal("1.21.1", m.minecraftVersion, "minecraft version");
        equal("https://cdn.elythera.com/elylauncher/elymon-icon.png", m.iconUrl, "icon");
        equal("https://cdn.elythera.com/elylauncher/elymon-background2.png", m.backgroundImage, "background");
        equal("ended", m.availabilityState, "a past end date ends the profile");
        check(!m.available, "ended is not available");
        equal("1.5.0", m.requiresLauncher, "the higher floor wins");
        equal("root msg", m.requiresMessage, "message of the winning floor");
        equal("https://elythera.com/root", m.requiresUrl, "url of the winning floor");
        equal("0.2.0", m.requiresAndroid, "android floor");
        check(m.maintenanceActive, "maintenance active on a fresh distribution");
        equal("Travaux en cours", m.maintenanceMessage, "maintenance message cleaned");
        check(!Distribution.meta(root, server, false, now).maintenanceActive, "maintenance never active from the cache");

        JsonObject tie = JsonParser.parseString("{\"id\":\"e\",\"address\":\"a\",\"minecraftVersion\":\"1\","
                + "\"availability\":{\"enabled\":false,\"message\":\"Maintenance\"},"
                + "\"requires\":{\"launcher\":\"1.5\",\"message\":\"server msg\",\"url\":\"https://elythera.com/s\"}}").getAsJsonObject();
        ServerMeta t = Distribution.meta(root, tie, true, now);
        equal("server msg", t.requiresMessage, "the profile wins a tie");
        equal("maintenance", t.availabilityState, "enabled:false is maintenance");
        JsonObject later = JsonParser.parseString("{\"id\":\"e\",\"address\":\"a\",\"minecraftVersion\":\"1\","
                + "\"availability\":{\"start\":\"2026-10-01T18:00:00+02:00\"}}").getAsJsonObject();
        ServerMeta u = Distribution.meta(JsonParser.parseString("{\"servers\":[]}").getAsJsonObject(), later, true, now);
        equal("upcoming", u.availabilityState, "a future start is upcoming");
        check(u.availabilityStart > now, "start parsed");
        ServerMeta none = Distribution.meta(JsonParser.parseString("{\"servers\":[]}").getAsJsonObject(),
                JsonParser.parseString("{\"id\":\"e\"}").getAsJsonObject(), true, now);
        check(none.available && "open".equals(none.availabilityState) && none.requiresLauncher == null,
                "absent blocks: open, no floor");
        equal(null, Distribution.safeHttpsUrl("https://user@evil.example/"), "credentials refused");
    }

    private static void policy(File policyFile) throws Exception {
        section("Android policy asset");
        AndroidPolicy p = AndroidPolicy.parse(TestSupport.readText(policyFile));
        String[] mods = {"distanthorizons", "iris", "irisscalefix", "euphoria_patcher", "cobblemon_vocalized",
                "crash_assistant", "controlify", "c2me", "connector", "swingthrough", "unilib"};
        equal(mods.length, p.excludeMods.size(), "excluded mod count");
        for (String mod : mods) {
            check(p.isModExcluded(mod), "excluded: " + mod);
        }
        check(p.isFileExcluded("shaderpacks/ComplementaryReimagined_r5.9.1/shaders/lib/x.glsl"), "shaderpacks excluded");
        check(p.isFileExcluded("config/defaultoptions/extra/saves/Tutorial World v4 (1.7.2)/level.dat"), "tutorial world excluded");
        check(p.isFileExcluded("config/defaultoptions/extra/shaderpacks/a.txt"), "defaultoptions shaderpacks excluded");
        check(p.isFileExcluded("config/craftpresence.json"), "craftpresence excluded");
        check(!p.isFileExcluded("config/fml.toml"), "fml.toml kept");
        Overlay fml = p.overlayFor("config/fml.toml");
        check(fml != null && fml.ops.size() == 1 && "false".equals(fml.ops.get(0).set.get("earlyWindowControl")),
                "fml.toml overlay sets earlyWindowControl = false");
        Overlay keys = p.overlayFor("config/defaultoptions/keybindings.txt");
        check(keys != null && keys.ops.size() == 1 && Overlay.COLON_KEY_SET.equals(keys.ops.get(0).kind)
                        && "key.keyboard.grave.accent:".equals(keys.ops.get(0).set.get("key_key.ftbchunks.map")),
                "the keybindings overlay (controls package) moves the FTB Chunks map off M");
        equal(13, p.seedOptions.size(), "seed option count");
        equal("fr_fr", p.seedOptions.get("lang"), "seed lang");
        equal("\"false\"", p.seedOptions.get("renderClouds"), "seed renderClouds keeps its quotes");
        equal("8", p.seedOptions.get("renderDistance"), "seed renderDistance");

        boolean refused = false;
        try {
            AndroidPolicy.parse("{\"overlays\":[{\"path\":\"../x\",\"ops\":[{\"op\":\"tomlSet\",\"set\":{\"a\":\"b\"}}]}]}");
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check(refused, "an overlay path leaving the instance is refused");
        refused = false;
        try {
            AndroidPolicy.parse("{\"overlays\":[{\"path\":\"x\",\"ops\":[{\"op\":\"rm\",\"set\":{\"a\":\"b\"}}]}]}");
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check(refused, "an unknown overlay op is refused");
    }

    private static void strings(File stringsFile) throws Exception {
        section("French texts: SyncText equals res/values/elymon_sync_strings.xml");
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        Document doc = f.newDocumentBuilder().parse(stringsFile);
        NodeList nodes = doc.getElementsByTagName("string");
        Map<String, String> xml = new LinkedHashMap<String, String>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            xml.put(e.getAttribute("name"), unescapeAndroid(e.getTextContent()));
        }
        Map<String, String> defaults = SyncText.defaults();
        equal(defaults.size(), xml.size(), "same number of texts");
        for (Map.Entry<String, String> e : defaults.entrySet()) {
            equal(e.getValue(), xml.get("elymon_sync_" + e.getKey()), "text " + e.getKey());
        }
        SyncText text = new SyncText(null);
        equal("Téléchargement (3/12)", text.get(SyncText.STAGE_DOWNLOAD, 3, 12), "download label formatting");
        equal("1,5 Go", text.size(1610612736L), "size in Go");
        equal("350 Mo", text.size(350L * 1024 * 1024), "size in Mo");
        SyncText custom = new SyncText(new SyncText.Resolver() {
            @Override
            public String get(String key) {
                return SyncText.STAGE_VERIFY.equals(key) ? "Vérif" : null;
            }
        });
        equal("Vérif", custom.get(SyncText.STAGE_VERIFY), "a resolver overrides a text");
        equal("Fichiers à jour", custom.get(SyncText.STAGE_DONE), "a resolver falls back to the default");
    }

    /** Android string resource escapes: \' \" \\ \n \t and \@ \?. */
    static String unescapeAndroid(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static void planner() throws Exception {
        section("Planner: optional modules and path guard");
        AndroidPolicy none = AndroidPolicy.empty();
        String mods = "{\"id\":\"elymon-1.21.1\",\"address\":\"a\",\"minecraftVersion\":\"1.21.1\",\"modules\":["
                + mod("off", "{\"value\":false,\"def\":false}")
                + "," + mod("on", "{\"value\":false}")
                + "," + mod("req", "{\"value\":true,\"def\":false}")
                + "," + mod("plain", null)
                + "]}";
        Planner.Plan plan = Planner.plan(JsonParser.parseString(mods).getAsJsonObject(), none);
        equal(3, plan.modsPlanned, "{value:false, def:false} is the only exclusion");
        equal(1, plan.optionalExcluded, "one optional module left out");

        String[] unsafe = {
                file("../../evil.txt"), file("/etc/evil"), file("C:/evil"), file("config\\evil"),
                "{\"id\":\"evil:..:1\",\"type\":\"Library\",\"artifact\":{\"size\":1,\"MD5\":\"" + FileOps.EMPTY_MD5
                        + "\",\"url\":\"https://x/y\"}}",
                "{\"id\":\"generated.forgemod:x:1@jar\",\"type\":\"ForgeMod\",\"artifact\":{\"path\":\"../mods/x.jar\","
                        + "\"size\":1,\"MD5\":\"" + FileOps.EMPTY_MD5 + "\",\"url\":\"https://x/y\"}}",
                "{\"id\":\"generated.forgemod:off:1@jar\",\"type\":\"ForgeMod\",\"required\":{\"value\":false,\"def\":false},"
                        + "\"artifact\":{\"size\":1,\"MD5\":\"" + FileOps.EMPTY_MD5 + "\",\"url\":\"https://x/y\"},"
                        + "\"subModules\":[" + file("../hidden") + "]}",
                "{\"id\":\"x\",\"type\":\"File\",\"artifact\":{\"path\":7,\"size\":1,\"MD5\":\"" + FileOps.EMPTY_MD5
                        + "\",\"url\":\"https://x/y\"}}"};
        for (String module : unsafe) {
            String server = "{\"id\":\"elymon-1.21.1\",\"address\":\"a\",\"minecraftVersion\":\"1.21.1\",\"modules\":["
                    + module + "]}";
            boolean refused = false;
            try {
                Planner.plan(JsonParser.parseString(server).getAsJsonObject(), none);
            } catch (Planner.UnsafeException e) {
                refused = true;
            }
            check(refused, "profile refused for " + module.substring(0, Math.min(90, module.length())));
        }
    }

    private static String mod(String name, String required) {
        return "{\"id\":\"generated.forgemod:" + name + ":1.0@jar\",\"type\":\"ForgeMod\","
                + (required != null ? "\"required\":" + required + "," : "")
                + "\"artifact\":{\"size\":1,\"MD5\":\"" + FileOps.EMPTY_MD5 + "\",\"url\":\"https://x/" + name + ".jar\"}}";
    }

    private static String file(String path) {
        return "{\"id\":\"f\",\"type\":\"File\",\"artifact\":{\"path\":\"" + path.replace("\\", "\\\\")
                + "\",\"size\":1,\"MD5\":\"" + FileOps.EMPTY_MD5 + "\",\"url\":\"https://x/y\"}}";
    }
}
