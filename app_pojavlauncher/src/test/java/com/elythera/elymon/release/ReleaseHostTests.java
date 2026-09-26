package com.elythera.elymon.release;

import com.elythera.elymon.ElymonAssetTrim;
import com.elythera.elymon.ElymonEligibilityRules;
import com.elythera.elymon.support.LaunchDiagnostics;
import com.elythera.elymon.support.LogBundle;
import com.elythera.elymon.support.LogRedactor;
import com.elythera.elymon.update.UpdateHttp;
import com.elythera.elymon.update.UpdateManifest;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Host-side tests of the release package's pure-Java parts: log redaction and the support
 * zip, launch diagnostics, the update feed and APK download, eligibility thresholds and the
 * vanilla asset trim. No JUnit (the app module has no test dependency): run with
 * tools/elymon/test-release.sh.
 *
 * Every token below is FAKE, built from random bytes with the shape of the real thing.
 */
public final class ReleaseHostTests {
    private static int sChecks;
    private static int sFailures;
    private static File sTmp;

    public static void main(String[] args) throws Exception {
        String assetIndex = null;
        for (int i = 0; i < args.length; i++) {
            if ("--tmp".equals(args[i])) {
                sTmp = new File(args[++i]);
            } else if ("--asset-index".equals(args[i])) {
                assetIndex = args[++i];
            }
        }
        if (sTmp == null) {
            sTmp = new File(System.getProperty("java.io.tmpdir"), "elymon-release-test-" + System.nanoTime());
        }
        if (!sTmp.isDirectory() && !sTmp.mkdirs()) {
            throw new IOException("cannot create " + sTmp);
        }

        redaction();
        redactionKeepsOrdinaryLines();
        bundle();
        diagnostics();
        manifest();
        http();
        eligibility();
        assetTrim(assetIndex);

        System.out.println(sChecks + " checks, " + sFailures + " failure(s)");
        if (sFailures > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ fake secrets

    private static final Random RANDOM = new Random(42);

    private static String b64url(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String randomB64url(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static String randomB64(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        return Base64.getEncoder().encodeToString(data);
    }

    private static String randomHex(int chars) {
        StringBuilder out = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) {
            out.append(Character.forDigit(RANDOM.nextInt(16), 16));
        }
        return out.toString();
    }

    /** Shape of a Minecraft services access token (RS256 JWT, ~ 1.2 KB). */
    private static String minecraftJwt() {
        String header = b64url("{\"kid\":\"04b8e4\",\"alg\":\"RS256\"}");
        String payload = b64url("{\"xuid\":\"2535405290837465\",\"agg\":\"Adult\",\"sub\":\"8d2b9c1e-44f3-4c2a-9a5e-3f1d7b6c0e21\","
                + "\"auth\":\"XBOX\",\"ns\":\"default\",\"roles\":[],\"iss\":\"authentication\","
                + "\"flags\":[\"twofactorauth\",\"msamigration\",\"orders_2022\",\"multiplayer\"],"
                + "\"profiles\":{\"mc\":\"5c3f1a7e-9d2b-4e6f-8a1c-0b7d3e9f2a64\"},\"platform\":\"PC_LAUNCHER\","
                + "\"yuid\":\"" + randomHex(32) + "\",\"nbf\":1790000000,\"exp\":1790086400,\"iat\":1790000000}");
        return header + "." + payload + "." + randomB64url(256);
    }

    /** Shape of a Microsoft (MSA) compact access token. */
    private static String msaAccessToken() {
        return "EwA" + randomB64(900).replace("\n", "").substring(3);
    }

    private static String msaRefreshToken() {
        return "M.C540_BAY.0.U.-C" + randomB64url(120).replace('_', '!') + "$$";
    }

    private static String msaCode() {
        return "M.C507_SN1.2.U." + randomHex(8) + "-" + randomHex(4) + "-" + randomHex(4) + "-" + randomHex(4) + "-" + randomHex(12);
    }

    // ------------------------------------------------------------ redaction

    private static void redaction() {
        String jwt = minecraftJwt();
        String ewa = msaAccessToken();
        String refresh = msaRefreshToken();
        String code = msaCode();
        String key = randomHex(64);
        String xuid = "2535405290837465";

        String[] secrets = {jwt, ewa, refresh, code, key, xuid};
        String[] lines = {
                // JREUtils' old System.out line (List#toString of the game arguments)
                "[--username, Pikachu, --version, neoforge-21.1.249, --gameDir, /storage/emulated/0/Android/data/com.elythera.elymon/files/custom_instances/elymon-1.21.1, "
                        + "--assetsDir, /storage/emulated/0/Android/data/com.elythera.elymon/files/.minecraft/assets, --assetIndex, 17, --uuid, 5c3f1a7e9d2b4e6f8a1c0b7d3e9f2a64, "
                        + "--accessToken, " + jwt + ", --clientId, " + randomB64url(24) + ", --xuid, " + xuid + ", --userType, msa, --versionType, release]",
                // A command line (hs_err_pid "java_command:")
                "java_command: cpw.mods.bootstraplauncher.BootstrapLauncher --username Pikachu --accessToken " + jwt + " --xuid " + xuid + " --userType msa",
                "--accessToken=" + jwt,
                // Environment line an old build wrote
                "Added custom env: ELYTHERA_KEY=" + key,
                "ELYTHERA_KEY: \"" + key + "\"",
                // OAuth responses (auth logs of an old build)
                "{\"token_type\":\"bearer\",\"expires_in\":3600,\"scope\":\"XboxLive.signin XboxLive.offline_access\",\"access_token\":\"" + ewa
                        + "\",\"refresh_token\":\"" + refresh + "\",\"user_id\":\"a1b2c3d4e5f60718\"}",
                "{\"IssueInstant\":\"2026-09-26T10:00:00.0000000Z\",\"NotAfter\":\"2026-09-27T02:00:00.0000000Z\",\"Token\":\"" + jwt + "\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"1234567890123456789\"}]}}",
                "{\"username\":\"8d2b9c1e-44f3\",\"roles\":[],\"accessToken\":\"" + jwt + "\",\"token_type\":\"Bearer\",\"expires_in\":86400}",
                "{\"msaRefreshToken\":\"" + refresh + "\",\"profileId\":\"5c3f1a7e\"}",
                // Redirect URLs and form bodies
                "WebView redirect: https://login.microsoftonline.com/common/oauth2/nativeclient?code=" + code + "&state=abc",
                "POST body: client_id=6aff0b13&grant_type=refresh_token&refresh_token=" + refresh + "&scope=XboxLive.signin",
                "Authorization: Bearer " + jwt,
                "Authorization: XBL3.0 x=1234567890123456789;" + jwt,
                // Tokens alone, as a mod might print them
                "D/Auth: token " + ewa,
                "D/Auth: code " + code,
                "D/Auth: refresh " + refresh,
                "[ElytheraMod] signing key " + key + " loaded",
        };
        for (String line : lines) {
            String out = LogRedactor.redactLine(line);
            for (String secret : secrets) {
                if (line.contains(secret)) {
                    check(!out.contains(secret), "secret removed from: " + abbreviate(line));
                }
            }
            check(out.contains(LogRedactor.MASK), "mask written in: " + abbreviate(line));
        }

        // A token cut across two lines by a 1 KB log writer: both halves must go.
        String split = "jrelog: args " + jwt;
        String first = split.substring(0, 600);
        String second = split.substring(600);
        check(!LogRedactor.redactLine(first).contains(first.substring(first.length() - 100)), "first half of a cut token");
        check(!LogRedactor.redactLine(second).contains(second.substring(0, Math.min(100, second.length()))), "second half of a cut token");
        String ewaSplit = "token " + ewa;
        String ewaSecond = ewaSplit.substring(500);
        check(!LogRedactor.redactLine(ewaSecond).contains(ewaSecond.substring(0, 100)), "second half of a cut EwA token");

        // Already masked stays as is (idempotent).
        String masked = "Added custom env: ELYTHERA_KEY=<masqué>";
        check(masked.equals(LogRedactor.redactLine(masked)), "already masked line unchanged");
        String modlauncher = "ModLauncher running: args [--username, Pikachu, --accessToken, ❄❄❄❄❄❄❄❄, --userType, msa]";
        String redactedModlauncher = LogRedactor.redactLine(modlauncher);
        check(redactedModlauncher.contains("--username, Pikachu") && redactedModlauncher.contains("--userType, msa"),
                "modlauncher line keeps its public arguments");
        check(LogRedactor.redactLine(LogRedactor.redactLine(lines[0])).equals(LogRedactor.redactLine(lines[0])), "idempotent");
    }

    private static void redactionKeepsOrdinaryLines() {
        String[] ordinary = {
                "[12:00:01] [main/INFO] [net.neoforged.fml.loading.moddiscovery.ModDiscoverer/SCAN]: Found 113 mods",
                "Java Exit code: 0",
                "Added custom env: LIBGL_ES=3",
                "Added custom env: POJAV_NATIVEDIR=/data/app/~~Zx3kQ==/com.elythera.elymon-Yb2Q==/lib/arm64",
                "Info: Device model: samsung SM-S928B",
                "-Delythera.launcher=elythera -Delythera.launcher.version=1.4.0-android.3 -Delythera.launcher.profile=elymon-1.21.1",
                "[Render thread/INFO]: Setting user: Pikachu",
                "Library sha1 3cd63d075497751784b2fa84be59432f4905bf7c ok",
                "---------------------------------------------------------------------------------------------------------------------------------------------",
                "[main/WARN]: File minecraft:sounds/music/game/calm1.ogg does not exist, cannot add it to event minecraft:music.game",
                "exit code=1",
                "at net.minecraft.client.Minecraft.run(Minecraft.java:787) ~[client-1.21.1-20240808.144430-srg.jar%23394!/:?]",
                "Elythera : launcher=elythera version=1.4.0-android.3 profil=elymon-1.21.1 clé=présente",
        };
        for (String line : ordinary) {
            check(line.equals(LogRedactor.redactLine(line)), "ordinary line unchanged: " + abbreviate(line));
        }
    }

    // ------------------------------------------------------------ support zip

    private static void bundle() throws IOException {
        File dir = new File(sTmp, "bundle");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("mkdirs " + dir);
        String jwt = minecraftJwt();
        String key = randomHex(64);

        File latestlog = new File(dir, "latestlog.txt");
        write(latestlog, "Info: Launcher version: 1.0.0-alpha.1\r\nAdded custom env: ELYTHERA_KEY=" + key + "\n"
                + "[--accessToken, " + jwt + ", --userType, msa]\n");

        // 6 MB of log with a secret at the start (cut away) and one near the end (kept, masked).
        File big = new File(dir, "latest.log");
        try (OutputStream out = new FileOutputStream(big)) {
            out.write(("FIRST LINE accessToken " + jwt + "\n").getBytes(StandardCharsets.UTF_8));
            byte[] filler = "[12:00:00] [Server thread/INFO]: Cobblemon battle tick ok\n".getBytes(StandardCharsets.UTF_8);
            long written = 0;
            while (written < 6L * 1024 * 1024) {
                out.write(filler);
                written += filler.length;
            }
            out.write(("LAST LINE --accessToken " + jwt + "\n").getBytes(StandardCharsets.UTF_8));
            out.write(new byte[]{(byte) 0xC3, (byte) 0x28, '\n'}); // malformed UTF-8
        }
        File crash = new File(dir, "crash-2026-09-26_10.00.00-client.txt");
        write(crash, "---- Minecraft Crash Report ----\nDescription: Rendering overlay\n");

        List<LogBundle.Entry> entries = new ArrayList<>();
        entries.add(new LogBundle.Entry("launcher/latestlog.txt", latestlog, 0));
        entries.add(new LogBundle.Entry("jeu/latest.log", big, 5L * 1024 * 1024));
        entries.add(new LogBundle.Entry("jeu/debug.log", new File(dir, "debug.log"), 5L * 1024 * 1024));
        entries.add(new LogBundle.Entry("jeu/crash-reports/" + crash.getName(), crash, 1024 * 1024));
        File zip = new File(dir, "journaux.zip");
        List<String> missing = LogBundle.write(zip, entries, "Application : Elymon 1.0.0-alpha.1 (1)\nClé : " + key + "\n");
        check(missing.equals(Arrays.asList("jeu/debug.log")), "missing file reported: " + missing);
        check(!new File(dir, "journaux.zip.part").exists(), "no .part left");

        Map<String, String> contents = readZip(zip);
        check(contents.keySet().equals(new java.util.HashSet<>(Arrays.asList(
                "launcher/latestlog.txt", "jeu/latest.log", "jeu/crash-reports/" + crash.getName(), "infos.txt"))),
                "zip entries: " + contents.keySet());
        for (Map.Entry<String, String> entry : contents.entrySet()) {
            check(!entry.getValue().contains(jwt), entry.getKey() + " has no token");
            check(!entry.getValue().contains(key), entry.getKey() + " has no key");
        }
        String log = contents.get("jeu/latest.log");
        check(log != null && log.startsWith("[Elymon] début du fichier omis"), "cut note first");
        check(log != null && !log.contains("FIRST LINE"), "start of a big log cut");
        check(log != null && log.contains("LAST LINE --accessToken <masqué>"), "end of a big log kept and masked");
        check(log != null && log.length() < 5L * 1024 * 1024 + 4096, "big log capped near 5 MB: " + (log == null ? 0 : log.length()));
        check(contents.get("launcher/latestlog.txt").contains("Info: Launcher version: 1.0.0-alpha.1\n"), "CRLF normalised, ordinary line kept");
        check(contents.get("infos.txt").contains("Fichiers absents :\n  jeu/debug.log"), "infos lists absent files");
        check(contents.get("infos.txt").contains("Application : Elymon 1.0.0-alpha.1 (1)"), "infos kept");

        File newestCrash = LogBundle.newest(dir, "crash-", ".txt");
        check(crash.equals(newestCrash), "newest crash report found");
        check(LogBundle.newest(new File(dir, "nope"), "crash-", ".txt") == null, "newest in a missing dir is null");
    }

    private static Map<String, String> readZip(File zip) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new java.io.FileInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    bytes.write(buffer, 0, read);
                }
                out.put(entry.getName(), new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    // ------------------------------------------------------------ launch diagnostics

    private static void diagnostics() {
        String jwt = minecraftJwt();
        List<String> args = new ArrayList<>();
        args.add("-Xms1024M");
        args.add("-Xmx4096M");
        args.add("-javaagent:/data/user/0/com.elythera.elymon/MioLibPatcher/MioLibPatcher.jar");
        StringBuilder classpath = new StringBuilder();
        for (int i = 0; i < 160; i++) {
            classpath.append("/storage/emulated/0/Android/data/com.elythera.elymon/files/.minecraft/libraries/net/neoforged/lib")
                    .append(i).append("/1.0/lib").append(i).append("-1.0.jar:");
        }
        args.add("-cp");
        args.add(classpath.toString());
        args.add("-Dsodium.checks.issue2561=false");
        args.add("-Delythera.launcher=elythera");
        args.add("-Delythera.launcher.version=1.4.0-android.3");
        args.add("-Delythera.launcher.profile=elymon-1.21.1");
        args.add("cpw.mods.bootstraplauncher.BootstrapLauncher");
        args.addAll(Arrays.asList("--username", "Pikachu", "--accessToken", jwt, "--xuid", "2535405290837465",
                "--clientId", "YzFmZGI0", "--uuid", "5c3f1a7e9d2b4e6f8a1c0b7d3e9f2a64", "--userType", "msa"));

        final List<String> lines = new ArrayList<>();
        LaunchDiagnostics.write(args, true, lines::add);
        check("Elythera : launcher=elythera version=1.4.0-android.3 profil=elymon-1.21.1 clé=présente".equals(lines.get(0)),
                "summary line: " + lines.get(0));
        check(LaunchDiagnostics.summaryLine(new ArrayList<String>(), false)
                        .equals("Elythera : launcher=absent version=absent profil=absent clé=absente"),
                "summary without properties");
        StringBuilder joined = new StringBuilder();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            int bytes = line.getBytes(StandardCharsets.UTF_8).length;
            check(bytes <= LaunchDiagnostics.MAX_LINE_BYTES, "line " + i + " is " + bytes + " bytes");
            check(line.startsWith("Arguments JVM (" + i + "/" + (lines.size() - 1) + ") : "), "numbered line " + i);
            check(!line.contains(jwt) && !line.contains("2535405290837465") && !line.contains("YzFmZGI0"), "line " + i + " has no secret");
            joined.append(line.substring(line.indexOf(" : ") + 3));
        }
        check(lines.size() > 4, "the class path spans several lines: " + lines.size());
        String all = joined.toString();
        check(all.contains("-Delythera.launcher.version=1.4.0-android.3") && all.contains("-Delythera.launcher.profile=elymon-1.21.1"),
                "-Delythera.* visible");
        check(all.contains("Pikachu") && all.contains("msa"), "public game arguments kept");
        String redacted = LaunchDiagnostics.redactArgs(args).toString();
        check(redacted.contains("--accessToken, <masqué>") && redacted.contains("--xuid, <masqué>")
                && redacted.contains("--clientId, <masqué>") && redacted.contains("--uuid, <masqué>")
                && redacted.contains("--username, Pikachu"), "game arguments masked: " + abbreviate(redacted));
        check(all.contains("lib159-1.0.jar:"), "class path complete");
        List<String> attached = LaunchDiagnostics.redactArgs(Arrays.asList("--accessToken=" + jwt, "--session", "abc"));
        check(attached.equals(Arrays.asList("--accessToken=<masqué>", "--session", "<masqué>")), "attached and separate values: " + attached);
    }

    // ------------------------------------------------------------ update feed

    private static String manifestJson(int versionCode, String url, String sha, long size, Integer min, String notes) {
        JsonObject o = new JsonObject();
        o.addProperty("versionCode", versionCode);
        o.addProperty("versionName", "1.0." + versionCode);
        o.addProperty("url", url);
        o.addProperty("sha256", sha);
        o.addProperty("size", size);
        if (min != null) o.addProperty("minVersionCode", min);
        if (notes != null) o.addProperty("notes", notes);
        return o.toString();
    }

    private static void manifest() {
        String sha = randomHex(64);
        UpdateManifest m = UpdateManifest.parse(manifestJson(5, "https://cdn.elythera.com/elylauncher/android/Elymon-1.0.5.apk",
                sha.toUpperCase(), 81234567L, 3, "  Corrections.  "));
        check(m.versionCode == 5 && m.minVersionCode == 3 && m.size == 81234567L, "fields read");
        check(m.sha256.equals(sha), "sha256 lowercased");
        check("Corrections.".equals(m.notes), "notes trimmed");
        check(m.isNewerThan(4) && !m.isNewerThan(5) && !m.isNewerThan(6), "isNewerThan");
        check(m.isRequiredFor(2) && !m.isRequiredFor(3) && !m.isRequiredFor(5), "isRequiredFor");
        UpdateManifest noMin = UpdateManifest.parse(manifestJson(2, "https://x.test/a.apk", sha, 10, null, null));
        check(noMin.minVersionCode == 0 && noMin.notes.isEmpty() && !noMin.isRequiredFor(1), "optional fields");
        JsonObject extra = JsonParser.parseString(manifestJson(2, "https://x.test/a.apk", sha, 10, null, null)).getAsJsonObject();
        extra.addProperty("channel", "releases");
        check(UpdateManifest.parse(extra.toString()).versionCode == 2, "unknown fields ignored");

        String[] invalid = {
                "",
                "[]",
                "{",
                manifestJson(0, "https://x.test/a.apk", sha, 10, null, null),
                manifestJson(2, "http://x.test/a.apk", sha, 10, null, null),
                manifestJson(2, "https://x.test/a b.apk", sha, 10, null, null),
                manifestJson(2, "https://x.test/a.apk", sha.substring(1), 10, null, null),
                manifestJson(2, "https://x.test/a.apk", randomHex(63) + "g", 10, null, null),
                manifestJson(2, "https://x.test/a.apk", sha, 0, null, null),
                manifestJson(2, "https://x.test/a.apk", sha, 2L * 1024 * 1024 * 1024, null, null),
                manifestJson(2, "https://x.test/a.apk", sha, 10, 3, null),
                manifestJson(2, "https://x.test/a.apk", sha, 10, -1, null),
                "{\"versionCode\":\"2\",\"versionName\":\"1\",\"url\":\"https://x.test/a.apk\",\"sha256\":\"" + sha + "\",\"size\":10}",
                "{\"versionCode\":2.5,\"versionName\":\"1\",\"url\":\"https://x.test/a.apk\",\"sha256\":\"" + sha + "\",\"size\":10}",
                "{\"versionCode\":2,\"url\":\"https://x.test/a.apk\",\"sha256\":\"" + sha + "\",\"size\":10}",
        };
        for (String json : invalid) {
            boolean rejected;
            try {
                UpdateManifest.parse(json);
                rejected = false;
            } catch (IllegalArgumentException e) {
                rejected = true;
            }
            check(rejected, "invalid manifest rejected: " + abbreviate(json));
        }
    }

    private static void http() throws Exception {
        final byte[] apk = new byte[300_000];
        RANDOM.nextBytes(apk);
        final String apkSha = UpdateHttp.hex(MessageDigest.getInstance("SHA-256").digest(apk));
        final Map<String, byte[]> routes = new HashMap<>();
        final Map<String, Integer> codes = new HashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = routes.get(path);
            Integer code = codes.get(path);
            if (body == null) {
                body = "not found".getBytes(StandardCharsets.UTF_8);
                code = 404;
            }
            exchange.sendResponseHeaders(code == null ? 200 : code, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            routes.put("/latest.json", manifestJson(7, "https://cdn.elythera.com/Elymon.apk", apkSha, apk.length, 6, "Notes")
                    .getBytes(StandardCharsets.UTF_8));
            routes.put("/broken.json", "{\"versionCode\": 7".getBytes(StandardCharsets.UTF_8));
            routes.put("/error.json", "oops".getBytes(StandardCharsets.UTF_8));
            codes.put("/error.json", 503);
            routes.put("/app.apk", apk);
            byte[] tampered = apk.clone();
            tampered[1000] ^= 1;
            routes.put("/tampered.apk", tampered);
            routes.put("/short.apk", Arrays.copyOf(apk, apk.length - 10));

            UpdateHttp.FeedResult found = UpdateHttp.fetchFeed(base + "/latest.json", "ElymonTest", 5000);
            check(found.kind == UpdateHttp.FeedResult.Kind.FOUND && found.manifest.versionCode == 7
                    && found.manifest.isRequiredFor(5), "feed found");
            check(found.json != null && UpdateManifest.parse(found.json).versionCode == 7, "raw feed kept for the cache");
            check(UpdateHttp.fetchFeed(base + "/nothing.json", "ElymonTest", 5000).kind == UpdateHttp.FeedResult.Kind.NOT_FOUND, "feed 404");
            check(UpdateHttp.fetchFeed(base + "/broken.json", "ElymonTest", 5000).kind == UpdateHttp.FeedResult.Kind.ERROR, "feed invalid");
            check(UpdateHttp.fetchFeed(base + "/error.json", "ElymonTest", 5000).kind == UpdateHttp.FeedResult.Kind.ERROR, "feed 503");
            check(UpdateHttp.fetchFeed("http://127.0.0.1:1/latest.json", "ElymonTest", 2000).kind == UpdateHttp.FeedResult.Kind.ERROR,
                    "feed unreachable");

            File target = new File(sTmp, "update/Elymon-7.apk");
            if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) throw new IOException("mkdirs");
            final long[] last = {0};
            UpdateHttp.download(base + "/app.apk", target, apk.length, apkSha, "ElymonTest", 5000, new UpdateHttp.Progress() {
                @Override
                public void onProgress(long doneBytes, long totalBytes) {
                    last[0] = doneBytes;
                }

                @Override
                public boolean isCancelled() {
                    return false;
                }
            });
            check(target.isFile() && target.length() == apk.length && last[0] == apk.length, "APK downloaded");
            check(UpdateHttp.matches(target, apk.length, apkSha), "downloaded APK matches");
            check(!UpdateHttp.matches(target, apk.length, randomHex(64)), "matches rejects another hash");

            expectDownloadFailure(base + "/tampered.apk", target, apk.length, apkSha, UpdateHttp.DownloadException.Reason.HASH, null);
            check(!target.exists() && !new File(target.getPath() + ".part").exists(), "nothing left after a bad hash");
            expectDownloadFailure(base + "/short.apk", target, apk.length, apkSha, UpdateHttp.DownloadException.Reason.SIZE, null);
            expectDownloadFailure(base + "/nothing.apk", target, apk.length, apkSha, UpdateHttp.DownloadException.Reason.NETWORK, null);
            final AtomicBoolean cancel = new AtomicBoolean(true);
            expectDownloadFailure(base + "/app.apk", target, apk.length, apkSha, UpdateHttp.DownloadException.Reason.CANCELLED, cancel);
            check(!target.exists() && !new File(target.getPath() + ".part").exists(), "nothing left after a cancel");
        } finally {
            server.stop(0);
        }
    }

    private static void expectDownloadFailure(String url, File target, long size, String sha,
                                              UpdateHttp.DownloadException.Reason reason, final AtomicBoolean cancel) {
        try {
            UpdateHttp.download(url, target, size, sha, "ElymonTest", 5000, cancel == null ? null : new UpdateHttp.Progress() {
                @Override
                public void onProgress(long doneBytes, long totalBytes) {
                }

                @Override
                public boolean isCancelled() {
                    return cancel.get();
                }
            });
            check(false, "download of " + url + " should fail with " + reason);
        } catch (UpdateHttp.DownloadException e) {
            check(e.reason == reason, "download of " + url + ": " + e.reason + " (expected " + reason + ")");
        }
    }

    // ------------------------------------------------------------ eligibility

    private static void eligibility() {
        long gib = ElymonEligibilityRules.GIB;
        long plenty = 50L * gib;
        int gles32 = 0x00030002;
        // RAM as phones report it
        check(outcome((long) (10.8 * gib), gles32, plenty, true) == ElymonEligibilityRules.Outcome.OK, "12 GB phone ok");
        check(outcome((long) (7.3 * gib), gles32, plenty, true) == ElymonEligibilityRules.Outcome.OK, "8 GB phone ok");
        check(outcome((long) (5.5 * gib), gles32, plenty, true) == ElymonEligibilityRules.Outcome.WARN_LOW_RAM, "6 GB phone warned");
        check(outcome((long) (3.6 * gib), gles32, plenty, true) == ElymonEligibilityRules.Outcome.REFUSE_RAM, "4 GB phone refused");
        check(outcome(0, gles32, plenty, true) == ElymonEligibilityRules.Outcome.OK, "unknown RAM not refused");
        // GLES
        check(outcome(12L * gib, 0x00030001, plenty, true) == ElymonEligibilityRules.Outcome.REFUSE_GLES, "GLES 3.1 refused");
        check(outcome(12L * gib, 0x00030000, plenty, true) == ElymonEligibilityRules.Outcome.REFUSE_GLES, "GLES 3.0 refused");
        check(outcome(12L * gib, 0x00030002, plenty, true) == ElymonEligibilityRules.Outcome.OK, "GLES 3.2 ok");
        check(outcome(12L * gib, 0, plenty, true) == ElymonEligibilityRules.Outcome.OK, "unknown GLES not refused");
        check("3.2".equals(ElymonEligibilityRules.glesVersionName(gles32)), "GLES name");
        // Space
        long firstRequired = ElymonEligibilityRules.requiredFreeBytes(true);
        check(firstRequired == Math.max(3L * gib, gib + ElymonEligibilityRules.FIRST_INSTALL_ESTIMATE_BYTES), "first install rule");
        check(firstRequired >= 3L * gib, "first install needs at least 3 GiB");
        check(ElymonEligibilityRules.requiredFreeBytes(false) == gib, "later Play needs 1 GiB");
        check(outcome(12L * gib, gles32, firstRequired - 1, true) == ElymonEligibilityRules.Outcome.REFUSE_SPACE, "first install short of space");
        check(outcome(12L * gib, gles32, firstRequired, true) == ElymonEligibilityRules.Outcome.OK, "first install exactly enough");
        check(outcome(12L * gib, gles32, 2L * gib, false) == ElymonEligibilityRules.Outcome.OK, "update with 2 GiB ok");
        check(outcome(12L * gib, gles32, gib - 1, false) == ElymonEligibilityRules.Outcome.REFUSE_SPACE, "update short of space");
        check(outcome(12L * gib, gles32, -1, true) == ElymonEligibilityRules.Outcome.OK, "unknown space not refused");
        // Refusals win over the warning
        check(outcome((long) (5.5 * gib), gles32, 10, true) == ElymonEligibilityRules.Outcome.REFUSE_SPACE, "refusal before warning");
        check(ElymonEligibilityRules.evaluate((long) (3.6 * gib), gles32, plenty, true).refused(), "refused()");
    }

    private static ElymonEligibilityRules.Outcome outcome(long ram, int gles, long free, boolean first) {
        return ElymonEligibilityRules.evaluate(ram, gles, free, first).outcome;
    }

    // ------------------------------------------------------------ asset trim

    private static void assetTrim(String assetIndexPath) throws IOException {
        check(ElymonAssetTrim.isTrimmed("minecraft/sounds/music/game/calm1.ogg"), "music trimmed");
        check(ElymonAssetTrim.isTrimmed("minecraft/sounds/music/menu/menu1.ogg"), "menu music trimmed");
        check(!ElymonAssetTrim.isTrimmed("minecraft/sounds/records/cat.ogg"), "records kept");
        check(!ElymonAssetTrim.isTrimmed("minecraft/sounds/ambient/cave/cave1.ogg"), "ambient kept");
        check(!ElymonAssetTrim.isTrimmed("minecraft/lang/fr_fr.json"), "lang kept");
        check(!ElymonAssetTrim.isTrimmed("minecraft/sounds.json"), "sounds.json kept");
        check(!ElymonAssetTrim.isTrimmed(null), "null kept");

        File objects = new File(sTmp, "assets/objects");
        String musicOnly = "aa" + randomHex(38);
        String shared = "bb" + randomHex(38);
        String kept = "cc" + randomHex(38);
        for (String hash : new String[]{musicOnly, shared, kept}) {
            File file = new File(new File(objects, hash.substring(0, 2)), hash);
            if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) throw new IOException("mkdirs");
            write(file, "x");
        }
        Map<String, String> index = new LinkedHashMap<>();
        index.put("minecraft/sounds/music/game/a.ogg", musicOnly);
        index.put("minecraft/sounds/music/game/b.ogg", shared);
        index.put("minecraft/sounds/ambient/b.ogg", shared);
        index.put("minecraft/lang/fr_fr.json", kept);
        index.put("minecraft/sounds/music/game/bad.ogg", "not-a-hash/../../x");
        int deleted = ElymonAssetTrim.deleteTrimmedObjects(objects, index, info -> info);
        check(deleted == 1, "one music-only object deleted: " + deleted);
        check(!new File(objects, musicOnly.substring(0, 2) + "/" + musicOnly).exists(), "music-only object gone");
        check(new File(objects, shared.substring(0, 2) + "/" + shared).exists(), "shared object kept");
        check(new File(objects, kept.substring(0, 2) + "/" + kept).exists(), "other object kept");
        check(ElymonAssetTrim.deleteTrimmedObjects(new File(sTmp, "nope"), index, info -> info) == 0, "missing objects dir");

        if (assetIndexPath == null || !new File(assetIndexPath).isFile()) {
            System.out.println("(asset index 17 not found: real-index measurement skipped)");
            return;
        }
        JsonObject objectsJson;
        try (Reader reader = new InputStreamReader(new java.io.FileInputStream(assetIndexPath), StandardCharsets.UTF_8)) {
            objectsJson = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("objects");
        }
        long total = 0;
        long trimmed = 0;
        int trimmedCount = 0;
        for (Map.Entry<String, JsonElement> entry : objectsJson.entrySet()) {
            long size = entry.getValue().getAsJsonObject().get("size").getAsLong();
            total += size;
            if (ElymonAssetTrim.isTrimmed(entry.getKey())) {
                trimmed += size;
                trimmedCount++;
            }
        }
        System.out.printf("asset index 17: %d objects, %.1f MB; trimmed %d objects, %.1f MB; left %.1f MB%n",
                objectsJson.size(), total / 1e6, trimmedCount, trimmed / 1e6, (total - trimmed) / 1e6);
        check(trimmed > 500_000_000L && trimmed < 600_000_000L, "vanilla music is about 545 MB");
    }

    // ------------------------------------------------------------ helpers

    private static void write(File file, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String abbreviate(String s) {
        return s.length() <= 90 ? s : s.substring(0, 90) + "…";
    }

    private static void check(boolean condition, String what) {
        sChecks++;
        if (!condition) {
            sFailures++;
            System.out.println("FAIL: " + what);
        }
    }
}
