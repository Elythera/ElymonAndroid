package com.elythera.elymon.sync;

import static com.elythera.elymon.sync.TestSupport.check;
import static com.elythera.elymon.sync.TestSupport.equal;
import static com.elythera.elymon.sync.TestSupport.note;
import static com.elythera.elymon.sync.TestSupport.section;

import com.elythera.elymon.ElymonConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * (a) Planning against a real copy of the Elythera distribution
 * (elymon-android-notes/distribution-2026-09-26.json). Nothing is downloaded:
 * the VersionManifest URL is pointed at a local copy of the NeoForge JSON.
 */
final class PlanRealDistributionTest {
    private PlanRealDistributionTest() {}

    static void run(File distFile, File policyFile, File versionJson, File tmp, boolean live) throws Exception {
        section("(a) Plan of the real distribution with the Android policy");
        byte[] raw = TestSupport.read(distFile);
        JsonObject root = Distribution.parse(raw);
        JsonObject server = Distribution.findServer(root, ElymonConfig.SERVER_ID);
        check(server != null, "elymon-1.21.1 is in the distribution");
        AndroidPolicy policy = AndroidPolicy.parse(TestSupport.readText(policyFile));
        Planner.Plan plan = Planner.plan(server, policy);

        equal(113, plan.modsPlanned, "113 mods after the Android exclusions");
        equal(1, plan.optionalExcluded, "Controlify left out as an optional mod off by default");
        equal(10, plan.modsExcludedByPolicy, "10 more mods left out by the Android policy");
        int libraries = 0;
        int files = 0;
        int empty = 0;
        long bytes = 0;
        Map<String, Long> unique = new HashMap<String, Long>();
        Set<String> rels = new HashSet<String>();
        Set<String> modIds = new HashSet<String>();
        for (Planner.Item item : plan.items) {
            bytes += item.size;
            unique.put(item.md5, item.size);
            if (item.root == Planner.Root.LIBRARIES) {
                libraries++;
            } else if ("File".equals(item.moduleType)) {
                files++;
                if (FileOps.EMPTY_MD5.equals(item.md5)) {
                    empty++;
                }
            }
            if ("ForgeMod".equals(item.moduleType)) {
                modIds.add(Planner.Maven.parse(item.moduleId).artifact);
            }
            rels.add(item.root + "/" + item.rel);
        }
        equal(52, libraries, "NeoForge universal jar + 51 libraries in libraries/");
        equal(445, files, "445 instance files once shaderpacks, the tutorial world and craftpresence are left out");
        equal(2, empty, "2 empty files remain (273 of the 275 were in the excluded folders)");
        for (String excluded : policy.excludeMods) {
            check(!modIds.contains(excluded), "mod " + excluded + " not planned");
        }
        check(modIds.contains("controlling") && modIds.contains("searchables"), "optional-on mods planned");
        check(modIds.contains("elythera"), "ElytheraMod planned");
        check(rels.contains("INSTANCE/mods/accessories-1.1.0-beta.53+1.21.1.jar"), "mods use the Maven file name");
        check(!rels.contains("INSTANCE/mods/accessories-neoforge-1.1.0-beta.53+1.21.1.jar"), "never the URL file name");
        check(rels.contains("LIBRARIES/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-universal.jar"), "universal jar");
        check(rels.contains("LIBRARIES/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-client.jar"), "processed client jar");
        check(rels.contains("LIBRARIES/net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-srg.jar"),
                "processed srg jar");
        boolean shader = false;
        boolean tutorial = false;
        for (String r : rels) {
            shader |= r.toLowerCase(Locale.ROOT).startsWith("instance/shaderpacks/");
            tutorial |= r.startsWith("INSTANCE/config/defaultoptions/extra/saves/");
        }
        check(!shader && !tutorial, "no shaderpacks nor tutorial world");
        check(rels.contains("INSTANCE/config/defaultoptions/options.txt"), "the pack's default options are placed");
        Planner.Item fml = null;
        for (Planner.Item item : plan.items) {
            if (item.rel.equals("config/fml.toml")) {
                fml = item;
            }
        }
        check(fml != null && fml.overlay != null, "config/fml.toml carries the tomlSet overlay");
        check(plan.manifest != null && "f6b79cd3ac09a3b17771ac9de25dd194".equals(plan.manifest.md5),
                "the VersionManifest is the known NeoForge 21.1.249 JSON");
        long uniqueBytes = 0;
        for (Long size : unique.values()) {
            uniqueBytes += size;
        }
        note(String.format(Locale.ROOT, "planned: %d items, %.1f MiB; %d distinct contents, %.1f MiB to download on a first install",
                plan.items.size() + 1, bytes / 1048576.0, unique.size(), uniqueBytes / 1048576.0));
        for (String w : plan.warnings) {
            note("warning: " + w);
        }

        section("(a) prepare(): distribution + VersionManifest id");
        if (!versionJson.isFile()) {
            note("SKIPPED: " + versionJson + " is missing (the desktop launcher's copy of the NeoForge JSON)");
            return;
        }
        String versionMd5 = TestSupport.md5(versionJson);
        check("f6b79cd3ac09a3b17771ac9de25dd194".equals(versionMd5), "local NeoForge JSON has the published MD5");
        JsonObject copy = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject copyServer = Distribution.findServer(copy, ElymonConfig.SERVER_ID);
        JsonObject manifestModule = findVersionManifest(copyServer.getAsJsonArray("modules"));
        manifestModule.getAsJsonObject("artifact").addProperty("url", versionJson.toURI().toString());
        File dist = new File(tmp, "plan/distribution.json");
        TestSupport.write(dist, copy.toString());
        SyncOptions o = new SyncOptions();
        o.gameHome = new File(tmp, "plan/files");
        o.minecraftDir = new File(o.gameHome, ".minecraft");
        o.workDir = new File(tmp, "plan/work");
        o.policyJson = TestSupport.readText(policyFile);
        o.distributionUrl = dist.toURI().toString();
        boolean before = Http.allowNonHttpUrls;
        Http.allowNonHttpUrls = true;
        try {
            SyncSession.Prepared p = ElymonSync.prepare(o, null);
            equal("neoforge-21.1.249", p.versionId, "versionId read from the VersionManifest");
            equal("neoforge-21.1.249/neoforge-21.1.249.json", p.versionItem.rel, "versions/<id>/<id>.json");
            check(!p.fromCache, "distribution read from its URL");
            equal("Elymon", p.meta.name, "meta name");
            equal("amp.elythera.com:25573", p.meta.address, "meta address");
            equal("2.4.6", p.meta.packVersion, "meta pack version");
            equal("1.21.1", p.meta.minecraftVersion, "meta Minecraft version");
            equal("1.3.1", p.meta.requiresLauncher, "meta requires.launcher");
            check(VersionOrder.atLeast(ElymonConfig.ELP_LEVEL, p.meta.requiresLauncher), "ELP_LEVEL passes the floor");
            check(p.meta.available, "Elymon is open");
            equal("Attrapez les tous !", p.meta.availabilityMessage, "availability message");
            check(new File(o.workDir, SyncSession.DISTRIBUTION_CACHE).isFile(), "distribution cached in workDir");
        } finally {
            Http.allowNonHttpUrls = before;
        }

        if (live) {
            section("(a) live distribution.json (one request)");
            try {
                Http.Document d = Http.fetchDocument(ElymonConfig.DISTRIBUTION_URL, "ElymonAndroid-hosttest", null);
                JsonObject liveRoot = Distribution.parse(d.body);
                JsonObject liveServer = Distribution.findServer(liveRoot, ElymonConfig.SERVER_ID);
                check(liveServer != null, "live distribution has elymon-1.21.1");
                Planner.Plan livePlan = Planner.plan(liveServer, policy);
                ServerMeta m = Distribution.meta(liveRoot, liveServer, true, System.currentTimeMillis());
                note(String.format(Locale.ROOT, "live: %d bytes, ETag %s, pack %s, %d mods planned, %d items, requires.launcher %s, %s",
                        d.body.length, d.etag != null ? "present" : "absent", m.packVersion, livePlan.modsPlanned,
                        livePlan.items.size() + 1, m.requiresLauncher, m.availabilityState));
                check(livePlan.manifest != null, "live VersionManifest present");
            } catch (Exception e) {
                note("live check skipped: " + e);
            }
        }
    }

    private static JsonObject findVersionManifest(JsonArray modules) {
        for (JsonElement e : modules) {
            JsonObject m = e.getAsJsonObject();
            if ("VersionManifest".equals(Distribution.string(m, "type"))) {
                return m;
            }
            if (m.has("subModules")) {
                JsonObject found = findVersionManifest(m.getAsJsonArray("subModules"));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
