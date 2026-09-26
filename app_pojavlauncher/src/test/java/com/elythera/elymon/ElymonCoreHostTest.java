package com.elythera.elymon;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Host-side checks of the pure-Java helpers of the core package. No JUnit on purpose
 * (the app module has no test dependency); run from the repo root with a JDK:
 *
 *   S=app_pojavlauncher/src; O=$(mktemp -d)
 *   javac --release 8 -d $O $S/main/java/com/elythera/elymon/{ElymonConfig,ElymonVersions,ElymonMemory,ElymonGameArgs,ElymonJavaRuntime}.java \
 *       $S/test/java/com/elythera/elymon/ElymonCoreHostTest.java && java -cp $O com.elythera.elymon.ElymonCoreHostTest
 */
public final class ElymonCoreHostTest {
    private static int sChecks;
    private static int sFailures;

    public static void main(String[] args) throws IOException {
        versions();
        memory();
        gameArgs();
        javaRuntime();
        System.out.println(sChecks + " checks, " + sFailures + " failure(s)");
        if (sFailures > 0) {
            System.exit(1);
        }
    }

    private static void versions() {
        check(ElymonVersions.compare("1.2.0-beta", "1.2.0") == 0, "qualifier ignored");
        check(ElymonVersions.compare("1.4.0-android.12", "1.4.0") == 0, "android qualifier ignored");
        check(ElymonVersions.compare("1.2", "1.2.0") == 0, "missing groups are zero");
        check(ElymonVersions.compare("1.2.0-beta.3", "1.2.0") == 0, "beta.3 does not outrank the release");
        check(ElymonVersions.compare("1.10.0", "1.9.9") > 0, "numeric, not lexical");
        check(ElymonVersions.compare("1.3.0", "1.3.1") < 0, "older patch");
        check(ElymonVersions.atLeast("1.4.0", "1.3.1"), "ELP 1.4.0 passes requires 1.3.1");
        check(!ElymonVersions.atLeast("1.4.0", "1.5"), "ELP 1.4.0 fails requires 1.5");
        check(ElymonVersions.atLeast("1.0.0-alpha.1", "1.0.0"), "alpha counts as its release");
        check(ElymonVersions.atLeast("1.0.0", null), "no minimum");
        check(ElymonVersions.atLeast("1.0.0", "   "), "blank minimum");
        check(!ElymonVersions.atLeast("", "1.0.0"), "blank candidate");
        check(!ElymonVersions.atLeast("v1.3.0", "1.2.0"), "leading v is unreadable, like VersionOrder");
        check(ElymonVersions.atLeast("1.0.0", "banana"), "unreadable minimum never blocks");
        check(!ElymonVersions.isComparable("banana") && ElymonVersions.isComparable("2"), "isComparable");
        check(ElymonVersions.compare("99999999999999999999.0", "1.0") > 0, "overflow reads as very new");
        check(ElymonVersions.compare("1.2.3.4.5.6.7.8.9", "1.2.3.4.5.6.7.8") == 0, "segment cap");
    }

    private static void memory() {
        check(ElymonMemory.defaultHeapMb(11 * 1024 + 300) == 4096, "12 GB phone");
        check(ElymonMemory.defaultHeapMb(11 * 1024) == 4096, "11 GiB boundary");
        check(ElymonMemory.defaultHeapMb(7 * 1024 + 300) == 3072, "8 GB phone");
        check(ElymonMemory.defaultHeapMb(5 * 1024 + 500) == 2560, "6 GB phone");
        check(ElymonMemory.defaultHeapMb(3700) == 2048, "4 GB phone");
        check(ElymonMemory.initialHeapMb(4096) == 1024, "Xms capped at 1 GB");
        check(ElymonMemory.initialHeapMb(768) == 768, "Xms never above Xmx");
    }

    private static void gameArgs() {
        List<String> props = ElymonGameArgs.jvmProperties(ElymonConfig.launcherVersion(7));
        check(props.equals(Arrays.asList("-Delythera.launcher=elythera",
                "-Delythera.launcher.version=1.4.0-android.7",
                "-Delythera.launcher.profile=elymon-1.21.1")), "jvm properties: " + props);

        String key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        Map<String, String> env = ElymonGameArgs.environment(key, "069A79F444E94726A5BEFCA90E38AAF5");
        check(key.equals(env.get("ELYTHERA_KEY")), "key passed through");
        check("069a79f4-44e9-4726-a5be-fca90e38aaf5".equals(env.get("ELYTHERA_UUID")), "undashed uuid canonicalised");
        check("069a79f4-44e9-4726-a5be-fca90e38aaf5".equals(
                ElymonGameArgs.canonicalUuid("069A79F4-44E9-4726-A5BE-FCA90E38AAF5")), "dashed uuid lowercased");
        check("not-a-uuid".equals(ElymonGameArgs.canonicalUuid(" not-a-uuid\n")), "garbage only sanitised");
        check(ElymonGameArgs.environment("", "069a79f4-44e9-4726-a5be-fca90e38aaf5").isEmpty(), "no key, no variables");
        check(ElymonGameArgs.environment(key, null).isEmpty(), "no account, no variables");

        check(ElymonGameArgs.isSensitiveEnvName("ELYTHERA_UUID"), "ELYTHERA_* masked");
        check(ElymonGameArgs.isSensitiveEnvName("my_api_key"), "*KEY* masked");
        check(ElymonGameArgs.isSensitiveEnvName("GITHUB_TOKEN"), "*TOKEN* masked");
        check(ElymonGameArgs.isSensitiveEnvName("DB_PASSWORD"), "*PASS* masked");
        check(ElymonGameArgs.isSensitiveEnvName("CLIENT_SECRET"), "*SECRET* masked");
        check(!ElymonGameArgs.isSensitiveEnvName("LIBGL_ES"), "ordinary variable kept");
        check("<masqué>".equals(ElymonGameArgs.loggableEnvValue("ELYTHERA_KEY", key)), "key never logged");
        check("3".equals(ElymonGameArgs.loggableEnvValue("LIBGL_ES", "3")), "value logged");

        String secretToken = "eyJhbGciOiJIUzI1NiJ9.secret.token";
        String redacted = ElymonGameArgs.redactArgs(Arrays.asList("-Xss1M", "cpw.mods.bootstraplauncher.BootstrapLauncher",
                "--username", "Steve", "--accessToken", secretToken, "--uuid", "069a79f444e94726a5befca90e38aaf5",
                "--xuid", "2535400000000000", "--session=" + secretToken, "--version", "neoforge-21.1.249"));
        check(!redacted.contains(secretToken), "access token masked: " + redacted);
        check(!redacted.contains("069a79f4") && !redacted.contains("2535400000000000"), "uuid and xuid masked");
        check(redacted.contains("Steve") && redacted.contains("neoforge-21.1.249"), "other arguments kept");
        check(redacted.contains("--accessToken, <masqué>") && redacted.contains("--session=<masqué>"), "mask format: " + redacted);
        check("[--accessToken]".equals(ElymonGameArgs.redactArgs(Arrays.asList("--accessToken"))), "trailing flag");
        check("[--usernameX, --uuidfoo]".equals(ElymonGameArgs.redactArgs(Arrays.asList("--usernameX", "--uuidfoo"))),
                "only exact flag names are masked");
    }

    private static void javaRuntime() throws IOException {
        check(ElymonConfig.JRE21_ARM64_URL.equals(ElymonJavaRuntime.sourceUrl(21, "arm64")), "java 21 arm64 url");
        check(ElymonJavaRuntime.sourceUrl(17, "arm64") == null && ElymonJavaRuntime.sourceUrl(21, "x86_64") == null,
                "other runtimes keep upstream's url");
        check(ElymonConfig.JRE21_ARM64_SHA256.matches("[0-9a-f]{64}"), "pin is a lowercase sha-256");

        File file = File.createTempFile("elymon-jre", ".tar.xz");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("abc".getBytes(StandardCharsets.US_ASCII));
        }
        check("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".equals(ElymonJavaRuntime.sha256Hex(file)),
                "sha-256 of abc");
        check(ElymonJavaRuntime.checkPinned(file, 17, "arm64") && file.exists(), "unpinned runtime accepted");
        check(!ElymonJavaRuntime.checkPinned(file, 21, "arm64"), "mismatch refused");
        check(!file.exists(), "mismatching archive deleted");
    }

    private static void check(boolean condition, String what) {
        sChecks++;
        if (!condition) {
            sFailures++;
            System.out.println("FAIL: " + what);
        }
    }
}
