package com.elythera.elymon.sync;

import static com.elythera.elymon.sync.TestSupport.check;
import static com.elythera.elymon.sync.TestSupport.equal;
import static com.elythera.elymon.sync.TestSupport.note;
import static com.elythera.elymon.sync.TestSupport.section;

import com.elythera.elymon.ElymonConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * (b) End-to-end runs of {@link ElymonSync#run} against a tiny fake
 * distribution served by tools/elymon/sync-test-server.py (python3
 * http.server with Range, ETag and fault injection).
 */
final class EndToEndSyncTest {
    private EndToEndSyncTest() {}

    private static final String UA = "ElymonAndroid-hosttest/1";
    private static final String FML = "#Disables File Watcher.\ndisableConfigWatcher = false\n"
            + "#Should we control the window.\nearlyWindowControl = true\nmaxThreads = -1\n";
    private static final String DEFAULT_OPTIONS = "version:3955\r\nao:true\r\nlang:en_us\r\nrenderDistance:12\r\n";

    private static String base;
    private static File www;
    private static File log;
    private static File root;
    private static File gameHome;
    private static File instance;
    private static File mc;
    private static File work;
    private static String policy;

    /** The fake distribution: modules by key, rebuilt into distribution.json on publish(). */
    private static final Map<String, JsonObject> topModules = new LinkedHashMap<String, JsonObject>();
    private static JsonObject forgeHosted;

    static void run(String serverBase, File wwwDir, File logFile, File tmp, File policyFile) throws Exception {
        base = serverBase.endsWith("/") ? serverBase.substring(0, serverBase.length() - 1) : serverBase;
        www = wwwDir;
        log = logFile;
        root = new File(tmp, "e2e");
        gameHome = new File(root, "files");
        instance = new File(gameHome, ElymonConfig.INSTANCE_REL);
        mc = new File(gameHome, ".minecraft");
        work = new File(root, "work");
        policy = TestSupport.readText(policyFile);
        Http.retryDelayMs = 20;

        buildDistribution();
        firstInstall();
        secondRunDownloadsNothing();
        touchedAndOverlaidFiles();
        resume();
        optionsSeeding();
        pruning();
        cacheFallback();
        pathTraversal();
        failures();
        cancellation();
    }

    // ------------------------------------------------------------ fixtures

    private static final byte[] UNIVERSAL = TestSupport.bytes(20000, 1);
    private static final byte[] CLIENT = TestSupport.bytes(15000, 2);
    private static final byte[] LIB = TestSupport.bytes(5000, 3);
    private static final byte[] ALPHA = TestSupport.bytes(30000, 4);
    private static final byte[] IRIS = TestSupport.bytes(1000, 5);
    private static final byte[] OPTON = TestSupport.bytes(2000, 6);
    private static final byte[] BIG = TestSupport.bytes(300000, 7);
    private static final byte[] OGG = TestSupport.bytes(50000, 8);
    private static final byte[] OLD = TestSupport.bytes(4000, 9);
    private static final byte[] FLAKY = TestSupport.bytes(3000, 10);
    private static final byte[] REMOVED = TestSupport.bytes(100, 11);
    private static final byte[] VERSION_JSON =
            "{\"id\":\"neoforge-21.1.249\",\"inheritsFrom\":\"1.21.1\",\"libraries\":[]}".getBytes(StandardCharsets.UTF_8);

    /** Writes content under www and returns its URL; spaces are percent-encoded unless raw is asked. */
    private static String put(String serverPath, byte[] content, boolean rawSpaces) throws IOException {
        TestSupport.write(new File(www, serverPath), content);
        return base + "/" + (rawSpaces ? serverPath : serverPath.replace(" ", "%20"));
    }

    private static JsonObject module(String id, String type, String url, byte[] content, String path) {
        JsonObject m = new JsonObject();
        m.addProperty("id", id);
        m.addProperty("name", id);
        m.addProperty("type", type);
        JsonObject a = new JsonObject();
        a.addProperty("size", content.length);
        a.addProperty("MD5", TestSupport.md5(content));
        a.addProperty("url", url);
        if (path != null) {
            a.addProperty("path", path);
        }
        m.add("artifact", a);
        return m;
    }

    private static void fileModule(String path, byte[] content) throws IOException {
        fileModule(path, content, false);
    }

    private static void fileModule(String path, byte[] content, boolean rawSpaces) throws IOException {
        String url = put("ndist/files/" + path, content, rawSpaces);
        topModules.put("file:" + path, module(path.substring(path.lastIndexOf('/') + 1), "File", url, content, path));
    }

    private static void modModule(String id, String urlName, byte[] content, String required) throws IOException {
        String url = put("ndist/forgemods/" + urlName, content, false);
        JsonObject m = module(id, "ForgeMod", url, content, null);
        if (required != null) {
            m.add("required", JsonParser.parseString(required));
        }
        topModules.put("mod:" + id, m);
    }

    private static void buildDistribution() throws IOException {
        forgeHosted = module("net.neoforged:neoforge:21.1.249:universal", "ForgeHosted",
                put("ndist/repo/lib/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-universal.jar", UNIVERSAL, false),
                UNIVERSAL, null);
        JsonArray subs = new JsonArray();
        subs.add(module("21.1.249", "VersionManifest",
                put("ndist/repo/versions/neoforge-21.1.249/neoforge-21.1.249.json", VERSION_JSON, false), VERSION_JSON, null));
        subs.add(module("net.neoforged:neoforge:21.1.249:client", "Library",
                put("ndist/repo/lib/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-client.jar", CLIENT, false), CLIENT, null));
        subs.add(module("com.example:lib:1.0", "Library", put("ndist/repo/lib/elsewhere/lib.jar", LIB, false), LIB,
                "com/example/lib/1.0/lib-1.0.jar"));
        forgeHosted.add("subModules", subs);
        topModules.put("forge", forgeHosted);

        modModule("generated.forgemod:alpha:1.0.0@jar", "Alpha-neoforge-1.0.0.jar", ALPHA, null);
        modModule("generated.forgemod:iris:1.8.0@jar", "iris-neoforge-1.8.0.jar", IRIS, null);
        modModule("generated.forgemod:optoff:1.0@jar", "optoff.jar.disabled", IRIS, "{\"value\":false,\"def\":false}");
        modModule("generated.forgemod:opton:1.0@jar", "Opton-1.0.jar", OPTON, "{\"value\":false}");
        modModule("generated.forgemod:big:2.0@jar", "Big-2.0.jar", BIG, null);

        fileModule("config/fml.toml", FML.getBytes(StandardCharsets.UTF_8));
        fileModule("config/defaultoptions/options.txt", DEFAULT_OPTIONS.getBytes(StandardCharsets.UTF_8));
        fileModule("config/defaultoptions/keybindings.txt", "key_key.jump:key.keyboard.space:\n".getBytes(StandardCharsets.UTF_8));
        fileModule("config/My Folder (1)/a b+c.txt", "plus and parentheses".getBytes(StandardCharsets.UTF_8));
        fileModule("config/raw space.txt", "raw space in the URL".getBytes(StandardCharsets.UTF_8), true);
        fileModule("elymon-music/pokemusic/a.ogg", OGG);
        fileModule("elymon-music/pokemusic/copy/a.ogg", OGG);
        fileModule("config/empty1.json", new byte[0]);
        fileModule("config/sub/empty2.json", new byte[0]);
        fileModule("shaderpacks/Pack/shaders.properties", "shader".getBytes(StandardCharsets.UTF_8));
        fileModule("config/defaultoptions/extra/saves/World/level.dat", "tutorial".getBytes(StandardCharsets.UTF_8));
        fileModule("resourcepacks/Old-v1.zip", OLD);
        fileModule("config/flaky.cfg", FLAKY);
        fileModule("config/removed-modified.cfg", REMOVED);
        publish();
    }

    private static void publish() throws IOException {
        JsonObject server = new JsonObject();
        server.addProperty("id", ElymonConfig.SERVER_ID);
        server.addProperty("name", "Elymon");
        server.addProperty("version", "2.4.6");
        server.addProperty("address", "amp.elythera.com:25573");
        server.addProperty("minecraftVersion", "1.21.1");
        server.addProperty("mainServer", true);
        JsonArray modules = new JsonArray();
        for (JsonObject m : topModules.values()) {
            modules.add(m);
        }
        server.add("modules", modules);
        server.add("availability", JsonParser.parseString("{\"enabled\":true,\"message\":\"Attrapez les tous !\"}"));
        server.add("requires", JsonParser.parseString(
                "{\"launcher\":\"1.3.1\",\"message\":\"Mettez le launcher à jour\",\"url\":\"https://elythera.com/launcher\"}"));
        JsonObject dist = new JsonObject();
        dist.addProperty("version", "1.0.0");
        JsonArray servers = new JsonArray();
        servers.add(JsonParser.parseString("{\"id\":\"Other-1.21.1\",\"address\":\"x\",\"minecraftVersion\":\"1.21.1\",\"modules\":[]}"));
        servers.add(server);
        dist.add("servers", servers);
        TestSupport.write(new File(www, "distribution.json"), dist.toString());
    }

    private static void faults(String json) throws IOException {
        TestSupport.write(new File(www, "_faults.json"), json);
    }

    private static SyncOptions options() {
        SyncOptions o = new SyncOptions();
        o.gameHome = gameHome;
        o.minecraftDir = mc;
        o.workDir = work;
        o.policyJson = policy;
        o.distributionUrl = base + "/distribution.json";
        o.confirmThresholdBytes = 1000;
        o.threads = 4;
        o.userAgent = UA;
        return o;
    }

    private static List<String> logLines() throws IOException {
        List<String> out = new ArrayList<String>();
        if (!log.isFile()) {
            return out;
        }
        for (String line : TestSupport.readText(log).split("\n")) {
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    private static int mark() throws IOException {
        return logLines().size();
    }

    private static List<String> since(int mark) throws IOException {
        List<String> all = logLines();
        return new ArrayList<String>(all.subList(Math.min(mark, all.size()), all.size()));
    }

    private static int count(List<String> lines, String needle) {
        int n = 0;
        for (String l : lines) {
            if (l.contains(needle)) {
                n++;
            }
        }
        return n;
    }

    private static File inst(String rel) {
        return new File(instance, rel);
    }

    // ---------------------------------------------------------------- runs

    private static void firstInstall() throws Exception {
        section("(b1) first install");
        faults("{\"/ndist/files/config/flaky.cfg\": {\"mode\": \"corrupt\", \"count\": 1}}");
        int mark = mark();
        TestSupport.Recorder r = new TestSupport.Recorder();
        SyncResult res = ElymonSync.run(options(), r);
        List<String> lines = since(mark);

        equal("neoforge-21.1.249", res.versionId, "versionId");
        check(!res.distributionFromCache, "fresh distribution");
        equal("Elymon", res.meta.name, "meta name");
        equal("1.3.1", res.meta.requiresLauncher, "meta requires.launcher");
        check(res.meta.available, "available");
        equal(3, res.modsPlanned, "3 mods (alpha, opton, big): iris and optoff left out");

        equal(TestSupport.md5(VERSION_JSON), TestSupport.md5(new File(mc, "versions/neoforge-21.1.249/neoforge-21.1.249.json")),
                "version JSON at versions/<id>/<id>.json");
        equal(TestSupport.md5(UNIVERSAL), TestSupport.md5(new File(mc, "libraries/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-universal.jar")),
                "ForgeHosted at its Maven path");
        equal(TestSupport.md5(CLIENT), TestSupport.md5(new File(mc, "libraries/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-client.jar")),
                "sub-module library at its Maven path");
        equal(TestSupport.md5(LIB), TestSupport.md5(new File(mc, "libraries/com/example/lib/1.0/lib-1.0.jar")),
                "library at artifact.path");
        equal(TestSupport.md5(ALPHA), TestSupport.md5(inst("mods/alpha-1.0.0.jar")), "mod under its Maven file name");
        check(!inst("mods/Alpha-neoforge-1.0.0.jar").exists(), "not under its URL name");
        check(!inst("mods/iris-1.8.0.jar").exists(), "policy-excluded mod absent");
        check(!inst("mods/optoff-1.0.jar").exists(), "optional-off mod absent");
        check(inst("mods/opton-1.0.jar").isFile(), "optional-on mod present");
        check(TestSupport.readText(inst("config/fml.toml")).contains("earlyWindowControl = false"), "fml.toml overlay applied");
        equal(2, res.overlaysApplied, "two overlays applied: fml.toml and keybindings.txt");
        check(TestSupport.readText(inst("config/defaultoptions/keybindings.txt"))
                .contains("key_key.cobblemon.summary:key.keyboard.m:\n"), "keybindings.txt overlay applied");
        check(!inst("shaderpacks").exists(), "shaderpacks excluded");
        check(!inst("config/defaultoptions/extra").exists(), "tutorial world excluded");
        check(inst("config/empty1.json").isFile() && inst("config/empty1.json").length() == 0
                && inst("config/sub/empty2.json").isFile(), "empty files created");
        equal(0, count(lines, "empty"), "empty files never requested");
        equal(TestSupport.md5(OGG), TestSupport.md5(inst("elymon-music/pokemusic/a.ogg")), "music file");
        equal(TestSupport.md5(OGG), TestSupport.md5(inst("elymon-music/pokemusic/copy/a.ogg")), "its duplicate");
        equal(1, count(lines, "/a.ogg"), "identical content downloaded once");
        equal("plus and parentheses", TestSupport.readText(inst("config/My Folder (1)/a b+c.txt")),
                "path with spaces, parentheses and +");
        equal("raw space in the URL", TestSupport.readText(inst("config/raw space.txt")), "URL with a raw space");
        equal(TestSupport.md5(FLAKY), TestSupport.md5(inst("config/flaky.cfg")), "corrupted answer retried");
        equal(2, count(lines, "/config/flaky.cfg"), "MD5 mismatch caused exactly one retry");
        equal(1, count(lines, "/Opton-1.0.jar"), "optional-on mod requested once");

        String options = TestSupport.readText(inst("options.txt"));
        check(options.contains("lang:fr_fr\r\n") && options.contains("renderDistance:8\r\n") && options.contains("ao:true\r\n")
                && options.contains("toggleCrouch:true\r\n") && options.contains("renderClouds:\"false\"\r\n"),
                "options.txt seeded from the pack defaults plus the Android values, CRLF kept");
        check(!options.contains("lang:en_us"), "seeded values replace the pack's");
        check(res.optionsSeeded, "result says options were seeded");

        long firstDownload = UNIVERSAL.length + CLIENT.length + LIB.length + ALPHA.length + OPTON.length + BIG.length
                + FML.length() + DEFAULT_OPTIONS.length() + "key_key.jump:key.keyboard.space:\n".length()
                + "plus and parentheses".length() + "raw space in the URL".length() + OGG.length + OLD.length
                + FLAKY.length + REMOVED.length;
        equal(1, r.confirmations.size(), "confirmDownload asked once");
        equal(firstDownload, r.confirmations.isEmpty() ? -1L : r.confirmations.get(0), "confirmDownload got the bytes to download");
        equal(firstDownload + VERSION_JSON.length + FLAKY.length, res.bytesDownloaded,
                "network bytes = unique contents + version JSON + the corrupted attempt");
        equal(15 + 1, res.filesDownloaded, "15 contents + the version JSON downloaded");
        check(r.sawStageStartingWith("Vérification des fichiers"), "stage: Vérification des fichiers");
        check(r.sawStageStartingWith("Téléchargement ("), "stage: Téléchargement (x/y)");
        check(r.stages.contains("Téléchargement (19/19)"),
                "download label reaches 19/19 (15 downloads, the version JSON and one duplicate copied, 2 empty files)");
        equal(4, res.filesCopied, "4 files placed without a download");
        equal("Fichiers à jour", r.stages.get(r.stages.size() - 1), "last stage");
        check(!r.wrongThread && r.callbackThread == Thread.currentThread(), "listener called on the caller's thread only");
        check(r.progressInRange, "progress never above its total");
        check(r.lastTotal == firstDownload && r.lastDone == firstDownload, "download progress ends at its total");
        check(count(lines, "ua=" + UA) == lines.size(), "every request carries the User-Agent");
        check(java.util.Arrays.equals(TestSupport.read(new File(www, "distribution.json")),
                TestSupport.read(new File(work, "distribution.json"))), "raw distribution cached in workDir");
        check(new File(work, SyncIndex.FILE_NAME).isFile(), "sync-index.json written");
        String managed = TestSupport.readText(inst(ManagedManifest.FILE_NAME));
        check(managed.contains("\"mods/alpha-1.0.0.jar\"") && managed.contains("\"config/fml.toml\"")
                && !managed.contains("\"options.txt\""), "managed manifest lists placed files, not options.txt");
        check(!new File(mc, "versions/neoforge-21.1.249/neoforge-21.1.249.json.part").exists()
                && !inst("mods/big-2.0.jar.part").exists(), "no .part left behind");
    }

    private static void secondRunDownloadsNothing() throws Exception {
        section("(b2) second run: nothing downloaded, nothing re-hashed");
        int mark = mark();
        long hashed = FileOps.FILES_HASHED.get();
        TestSupport.Recorder r = new TestSupport.Recorder();
        SyncResult res = ElymonSync.run(options(), r);
        List<String> lines = since(mark);
        equal(0L, res.bytesDownloaded, "0 bytes downloaded");
        equal(0, res.filesDownloaded, "0 files downloaded");
        equal(0, res.overlaysApplied, "overlay not re-applied: fml.toml is not downloaded again");
        check(r.confirmations.isEmpty(), "no confirmation asked");
        check(!r.sawStageStartingWith("Téléchargement"), "no download stage");
        equal(1, lines.size(), "a single request");
        check(lines.size() == 1 && lines.get(0).contains("/distribution.json") && lines.get(0).contains("status=304"),
                "the distribution answered 304 thanks to its ETag");
        check(!res.distributionFromCache, "a 304 is a fresh distribution, not the offline cache");
        long rehashed = FileOps.FILES_HASHED.get() - hashed;
        check(rehashed <= 1, "only the cached version JSON was hashed (" + rehashed + ")");
        check(!res.optionsSeeded, "options.txt left alone");
    }

    private static void touchedAndOverlaidFiles() throws Exception {
        section("(b3) touched, reverted and modified files");
        File alpha = inst("mods/alpha-1.0.0.jar");
        alpha.setLastModified(alpha.lastModified() - 60000);
        TestSupport.write(inst("config/fml.toml"), FML);
        TestSupport.write(inst("config/flaky.cfg"), "player edit");
        int mark = mark();
        long hashed = FileOps.FILES_HASHED.get();
        SyncResult res = ElymonSync.run(options(), new TestSupport.Recorder());
        List<String> lines = since(mark);
        long rehashed = FileOps.FILES_HASHED.get() - hashed;
        equal(0, count(lines, "Alpha"), "a touched file with the same bytes is not downloaded");
        equal(0, count(lines, "fml.toml"), "fml.toml back to the published bytes: overlay re-applied without download");
        equal(1, res.overlaysApplied, "one overlay re-applied");
        check(TestSupport.readText(inst("config/fml.toml")).contains("earlyWindowControl = false"), "fml.toml is Android's again");
        equal(1, count(lines, "flaky.cfg"), "a tracked file edited by the player is restored");
        equal(TestSupport.md5(FLAKY), TestSupport.md5(inst("config/flaky.cfg")), "restored content");
        equal((long) FLAKY.length, res.bytesDownloaded, "only that file downloaded");
        check(rehashed >= 3 && rehashed <= 4, "only the 3 changed files (and the version JSON) re-hashed (" + rehashed + ")");

        SyncResult again = ElymonSync.run(options(), new TestSupport.Recorder());
        equal(0L, again.bytesDownloaded, "stable afterwards");
        equal(0, again.overlaysApplied, "overlay stable afterwards");
    }

    private static void resume() throws Exception {
        section("(b4) resume of a .part and of a dropped connection");
        File big = inst("mods/big-2.0.jar");
        big.delete();
        byte[] half = new byte[100000];
        System.arraycopy(BIG, 0, half, 0, half.length);
        TestSupport.write(new File(big.getPath() + ".part"), half);
        File opton = inst("mods/opton-1.0.jar");
        opton.delete();
        faults("{\"/ndist/forgemods/Opton-1.0.jar\": {\"mode\": \"truncate\", \"count\": 1}}");
        int mark = mark();
        TestSupport.Recorder r = new TestSupport.Recorder();
        SyncResult res = ElymonSync.run(options(), r);
        List<String> lines = since(mark);
        equal(TestSupport.md5(BIG), TestSupport.md5(big), "big mod complete");
        check(!new File(big.getPath() + ".part").exists(), ".part renamed");
        check(count(lines, "Big-2.0.jar range=bytes=100000- inm=- status=206") == 1, "Range request answered 206");
        equal(TestSupport.md5(OPTON), TestSupport.md5(opton), "opton complete after a dropped connection");
        check(count(lines, "Opton-1.0.jar range=bytes=1000- inm=- status=206") == 1,
                "the dropped transfer resumed where it stopped");
        equal((long) (BIG.length - 100000 + OPTON.length), res.bytesDownloaded,
                "only the missing bytes crossed the network");
        equal(1, r.confirmations.size(), "confirmation asked for the remaining bytes");
        equal((long) (BIG.length - 100000 + OPTON.length), r.confirmations.isEmpty() ? -1L : r.confirmations.get(0),
                "confirmation counts the partial download");
    }

    private static void optionsSeeding() throws Exception {
        section("(b5) options.txt seeded only when missing or empty");
        TestSupport.write(inst("options.txt"), "lang:en_us\nrenderDistance:16\n");
        SyncResult res = ElymonSync.run(options(), new TestSupport.Recorder());
        check(!res.optionsSeeded, "player's options kept");
        equal("lang:en_us\nrenderDistance:16\n", TestSupport.readText(inst("options.txt")), "options.txt untouched");
        TestSupport.write(inst("options.txt"), new byte[0]);
        res = ElymonSync.run(options(), new TestSupport.Recorder());
        check(res.optionsSeeded, "an empty options.txt (made by Amethyst) is seeded");
        check(TestSupport.readText(inst("options.txt")).contains("lang:fr_fr"), "seeded content");
        TestSupport.write(inst("options.txt"), "  \n");
        res = ElymonSync.run(options(), new TestSupport.Recorder());
        check(res.optionsSeeded, "a blank options.txt is seeded");
    }

    private static void pruning() throws Exception {
        section("(b6) pruning, protected paths, player files");
        TestSupport.write(inst("saves/MyWorld/level.dat"), "world");
        TestSupport.write(inst("screenshots/shot.png"), "png");
        TestSupport.write(inst("logs/latest.log"), "log");
        TestSupport.write(inst("servers.dat"), "servers");
        TestSupport.write(inst("mods/my-own-mod.jar"), "player jar");
        TestSupport.write(inst("config/player-notes.txt"), "notes");
        TestSupport.write(inst("config/removed-modified.cfg"), "modified by the player");
        String optionsBefore = TestSupport.readText(inst("options.txt"));
        // A manifest that (wrongly) claims protected paths must still not delete them.
        JsonObject manifest = JsonParser.parseString(TestSupport.readText(inst(ManagedManifest.FILE_NAME))).getAsJsonObject();
        JsonObject files = manifest.getAsJsonObject("files");
        files.add("saves/MyWorld/level.dat", hashes(TestSupport.md5(inst("saves/MyWorld/level.dat"))));
        files.add("servers.dat", hashes(TestSupport.md5(inst("servers.dat"))));
        files.add("options.txt", hashes(TestSupport.md5(inst("options.txt"))));
        TestSupport.write(inst(ManagedManifest.FILE_NAME), manifest.toString());

        topModules.remove("file:resourcepacks/Old-v1.zip");
        topModules.remove("file:config/removed-modified.cfg");
        topModules.remove("mod:generated.forgemod:opton:1.0@jar");
        publish();

        SyncOptions o = options();
        o.allowPrune = false;
        SyncResult res = ElymonSync.run(o, new TestSupport.Recorder());
        equal(0, res.filesPruned, "nothing pruned while not allowed (game running)");
        check(inst("resourcepacks/Old-v1.zip").isFile(), "old pack still there");

        res = ElymonSync.run(options(), new TestSupport.Recorder());
        equal(2, res.filesPruned, "old pack and removed mod pruned");
        check(!inst("resourcepacks/Old-v1.zip").exists(), "resourcepacks/Old-v1.zip deleted");
        check(!inst("mods/opton-1.0.jar").exists(), "mods/opton-1.0.jar deleted");
        check(inst("resourcepacks").isDirectory(), "a top-level directory is never removed");
        equal("modified by the player", TestSupport.readText(inst("config/removed-modified.cfg")),
                "a file modified since it was placed is kept");
        check(inst("saves/MyWorld/level.dat").isFile() && inst("screenshots/shot.png").isFile()
                && inst("logs/latest.log").isFile() && inst("servers.dat").isFile(), "protected paths untouched");
        equal(optionsBefore, TestSupport.readText(inst("options.txt")), "options.txt untouched");
        check(inst("mods/my-own-mod.jar").isFile(), "player-added mod untouched");
        check(inst("config/player-notes.txt").isFile(), "player-created config untouched");
        String after = TestSupport.readText(inst(ManagedManifest.FILE_NAME));
        check(!after.contains("Old-v1.zip") && !after.contains("removed-modified") && !after.contains("saves/")
                && !after.contains("servers.dat") && !after.contains("\"options.txt\""), "released paths left the manifest");
    }

    private static JsonArray hashes(String md5) {
        JsonArray a = new JsonArray();
        a.add(md5);
        return a;
    }

    private static int closedPort() throws IOException {
        ServerSocket s = new ServerSocket(0);
        int port = s.getLocalPort();
        s.close();
        return port;
    }

    private static void cacheFallback() throws Exception {
        section("(b7) server down: cached distribution, only mods/ pruned");
        TestSupport.write(inst("resourcepacks/Old-v1.zip"), OLD);
        // A jar left behind by an update that stopped before pruning: FML would crash on
        // the duplicate mod id, so it goes even while the distribution comes from the cache.
        byte[] staleJar = "stale jar".getBytes(StandardCharsets.UTF_8);
        TestSupport.write(inst("mods/stale-1.0.jar"), staleJar);
        JsonObject manifest = JsonParser.parseString(TestSupport.readText(inst(ManagedManifest.FILE_NAME))).getAsJsonObject();
        manifest.getAsJsonObject("files").add("resourcepacks/Old-v1.zip", hashes(TestSupport.md5(OLD)));
        manifest.getAsJsonObject("files").add("mods/stale-1.0.jar", hashes(TestSupport.md5(staleJar)));
        TestSupport.write(inst(ManagedManifest.FILE_NAME), manifest.toString());
        byte[] cacheBefore = TestSupport.read(new File(work, "distribution.json"));

        SyncOptions o = options();
        o.distributionUrl = "http://127.0.0.1:" + closedPort() + "/distribution.json";
        SyncResult res = ElymonSync.run(o, new TestSupport.Recorder());
        check(res.distributionFromCache, "distribution read from the cache");
        equal(1, res.filesPruned, "from a cached distribution only the stale mod jar is pruned");
        check(!inst("mods/stale-1.0.jar").exists(), "stale mod jar removed while offline");
        check(inst("resourcepacks/Old-v1.zip").isFile(), "claimed non-mod file kept while offline");
        equal("neoforge-21.1.249", res.versionId, "version id from the cached manifest");
        equal(0L, res.bytesDownloaded, "nothing downloaded");
        check(!res.meta.maintenanceActive, "maintenance never active from the cache");

        faults("{\"/distribution.json\": {\"mode\": \"html\", \"count\": 3}}");
        res = ElymonSync.run(options(), new TestSupport.Recorder());
        check(res.distributionFromCache, "a captive-portal page falls back to the cache");
        check(java.util.Arrays.equals(cacheBefore, TestSupport.read(new File(work, "distribution.json"))),
                "the cache is not overwritten by a page that is not a distribution");

        res = ElymonSync.run(options(), new TestSupport.Recorder());
        check(!res.distributionFromCache, "back online");
        equal(1, res.filesPruned, "the claimed file is pruned once the distribution is fresh");

        SyncOptions fresh = options();
        fresh.workDir = new File(root, "work-empty");
        fresh.distributionUrl = o.distributionUrl;
        try {
            ElymonSync.run(fresh, new TestSupport.Recorder());
            check(false, "no cache and no network must fail");
        } catch (SyncException e) {
            check(!e.cancelled, "not a cancellation");
            equal(SyncText.defaults().get(SyncText.ERROR_UNREACHABLE), e.getMessage(), "French message");
        }
    }

    private static void pathTraversal() throws Exception {
        section("(b8) path traversal refused");
        byte[] cacheBefore = TestSupport.read(new File(work, "distribution.json"));
        File escaped = new File(gameHome, "evil.txt");
        fileModule("../../evil.txt".replace("../../", "evil/"), "evil".getBytes(StandardCharsets.UTF_8));
        JsonObject evil = topModules.remove("file:evil/evil.txt");
        evil.getAsJsonObject("artifact").addProperty("path", "../../evil.txt");
        topModules.put("file:../../evil.txt", evil);
        publish();
        try {
            ElymonSync.run(options(), new TestSupport.Recorder());
            check(false, "a profile with ../ must be refused");
        } catch (SyncException e) {
            check(e.getMessage().contains("chemin de fichier dangereux") && e.getMessage().contains("evil.txt"),
                    "French message naming the module: " + e.getMessage());
        }
        check(!escaped.exists(), "nothing written outside the instance");
        check(java.util.Arrays.equals(cacheBefore, TestSupport.read(new File(work, "distribution.json"))),
                "the refused distribution is not cached");
        topModules.remove("file:../../evil.txt");
        publish();
    }

    private static void failures() throws Exception {
        section("(b9) download failures name the file");
        fileModule("config/broken.cfg", "broken".getBytes(StandardCharsets.UTF_8));
        publish();
        faults("{\"/ndist/files/config/broken.cfg\": {\"mode\": \"error500\", \"count\": 99}}");
        int mark = mark();
        try {
            ElymonSync.run(options(), new TestSupport.Recorder());
            check(false, "a file that never downloads must fail the sync");
        } catch (SyncException e) {
            check(!e.cancelled, "not a cancellation");
            check(e.getMessage().contains("config/broken.cfg"), "message names the file: " + e.getMessage());
        }
        equal(3, count(since(mark), "broken.cfg"), "3 attempts");
        faults("{}");
        new File(www, "ndist/files/config/broken.cfg").delete();
        mark = mark();
        try {
            ElymonSync.run(options(), new TestSupport.Recorder());
            check(false, "a missing file must fail the sync");
        } catch (SyncException e) {
            check(e.getMessage().contains("config/broken.cfg"), "404 names the file");
        }
        equal(1, count(since(mark), "broken.cfg"), "a 404 is not retried");
        topModules.remove("file:config/broken.cfg");
        publish();
        SyncResult ok = ElymonSync.run(options(), new TestSupport.Recorder());
        check(ok.versionId != null, "healthy again once the module is gone");
    }

    private static void cancellation() throws Exception {
        section("(b10) cancellation and refusal");
        fileModule("config/later.bin", TestSupport.bytes(200000, 12));
        publish();
        int mark = mark();
        TestSupport.Recorder refuse = new TestSupport.Recorder();
        refuse.answer = false;
        try {
            ElymonSync.run(options(), refuse);
            check(false, "a refused confirmation must stop the sync");
        } catch (SyncException e) {
            check(e.cancelled, "refusal is a cancellation");
            equal(SyncText.defaults().get(SyncText.ERROR_DECLINED), e.getMessage(), "French refusal message");
        }
        equal(0, count(since(mark), "later.bin"), "nothing downloaded after a refusal");

        TestSupport.Recorder cancel = new TestSupport.Recorder();
        cancel.cancelAtFirstProgress = true;
        try {
            ElymonSync.run(options(), cancel);
            check(false, "a cancelled sync must stop");
        } catch (SyncException e) {
            check(e.cancelled, "cancel is a cancellation");
        }
        check(!inst("config/later.bin").exists(), "cancelled before the file landed");

        SyncResult res = ElymonSync.run(options(), new TestSupport.Recorder());
        check(inst("config/later.bin").isFile() && res.filesDownloaded == 1, "the next run finishes the job");

        section("(b11) cancel in the middle of a download, then resume");
        byte[] slow = TestSupport.bytes(400000, 13);
        fileModule("config/slow.bin", slow);
        publish();
        faults("{\"/ndist/files/config/slow.bin\": {\"mode\": \"slow\", \"count\": 1}}");
        TestSupport.Recorder midway = new TestSupport.Recorder();
        midway.cancelWhileDownloading = true;
        long started = System.currentTimeMillis();
        try {
            ElymonSync.run(options(), midway);
            check(false, "the sync must stop when cancelled mid-download");
        } catch (SyncException e) {
            check(e.cancelled, "cancelled");
            equal(SyncText.defaults().get(SyncText.ERROR_CANCELLED), e.getMessage(), "French cancel message");
        }
        long elapsed = System.currentTimeMillis() - started;
        check(elapsed < 900, "the download stopped quickly (" + elapsed + " ms, a full transfer takes 1 s)");
        File slowPart = new File(inst("config/slow.bin").getPath() + ".part");
        long kept = slowPart.length();
        check(!inst("config/slow.bin").exists() && kept > 0 && kept < slow.length,
                "a partial .part is kept (" + kept + " bytes)");
        mark = mark();
        res = ElymonSync.run(options(), new TestSupport.Recorder());
        equal(TestSupport.md5(slow), TestSupport.md5(inst("config/slow.bin")), "resumed file complete");
        equal(1, count(since(mark), "slow.bin range=bytes=" + kept + "- inm=- status=206"), "resumed with Range");
        equal(slow.length - kept, res.bytesDownloaded, "only the rest was downloaded");

        section("(b12) a server that ignores Range");
        byte[] plain = TestSupport.bytes(60000, 14);
        fileModule("config/norange.bin", plain);
        publish();
        TestSupport.write(new File(inst("config/norange.bin").getPath() + ".part"), java.util.Arrays.copyOf(plain, 20000));
        faults("{\"/ndist/files/config/norange.bin\": {\"mode\": \"norange\", \"count\": 1}}");
        mark = mark();
        res = ElymonSync.run(options(), new TestSupport.Recorder());
        equal(TestSupport.md5(plain), TestSupport.md5(inst("config/norange.bin")), "file complete from a 200 answer");
        equal(1, count(since(mark), "norange.bin range=bytes=20000- inm=- status=200"), "Range asked, whole file sent");
        equal((long) plain.length, res.bytesDownloaded, "the .part was restarted from zero");

        section("(b13) a listener that throws leaves no worker behind");
        byte[] third = TestSupport.bytes(400000, 15);
        fileModule("config/third.bin", third);
        publish();
        faults("{\"/ndist/files/config/third.bin\": {\"mode\": \"slow\", \"count\": 1}}");
        TestSupport.Recorder throwing = new TestSupport.Recorder() {
            @Override
            public void onProgress(long doneBytes, long totalBytes, String currentPath) {
                super.onProgress(doneBytes, totalBytes, currentPath);
                if (doneBytes > 0 && stages.get(stages.size() - 1).startsWith("Téléchargement")) {
                    throw new IllegalStateException("listener bug");
                }
            }
        };
        try {
            ElymonSync.run(options(), throwing);
            check(false, "the listener's exception must surface");
        } catch (IllegalStateException e) {
            check(true, "the listener's exception surfaced");
        }
        boolean alive = false;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            alive |= t.getName().startsWith("ElymonSync-") && t.isAlive();
        }
        check(!alive, "no ElymonSync worker still running");
        res = ElymonSync.run(options(), new TestSupport.Recorder());
        equal(TestSupport.md5(third), TestSupport.md5(inst("config/third.bin")), "the next run completes the file");
        note("final instance: " + res.filesPlanned + " files planned, " + res.modsPlanned + " mods");
    }
}
