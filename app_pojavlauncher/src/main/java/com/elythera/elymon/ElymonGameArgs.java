package com.elythera.elymon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the game process receives from the launcher for ElytheraMod, and how the
 * launcher keeps secrets out of the logs players share.
 *
 * Contract (desktop app/assets/js/processbuilder.js _resolveElytheraArgs and
 * _resolveElytheraEnv, ElytheraMod ClientHandshake):
 * - three public labels as system properties: elythera.launcher,
 *   elythera.launcher.version, elythera.launcher.profile;
 * - the signing key and the account UUID as environment variables ELYTHERA_KEY and
 *   ELYTHERA_UUID, only when a key was compiled in. The environment never reaches
 *   the process list, the JVM crash file or the launcher log; a -D would reach all three.
 *
 * Pure Java (no Android imports) for host-side tests: callers pass BuildConfig values.
 */
public final class ElymonGameArgs {
    public static final String ENV_KEY = "ELYTHERA_KEY";
    public static final String ENV_UUID = "ELYTHERA_UUID";

    /** Replaces secret values in anything written to latestlog.txt or logcat. */
    public static final String MASK = "<masqué>";

    /** Game arguments whose value identifies or authenticates the player. */
    private static final String[] SECRET_GAME_ARGS = {"--accessToken", "--session", "--uuid", "--xuid"};

    private ElymonGameArgs() {}

    /** The -D properties ElytheraMod reads, to add after the user's JVM arguments. */
    public static List<String> jvmProperties(String launcherVersion) {
        List<String> args = new ArrayList<>(3);
        args.add("-Delythera.launcher=" + sanitize(ElymonConfig.LAUNCHER_BRAND, 32));
        args.add("-Delythera.launcher.version=" + sanitize(launcherVersion, 32));
        args.add("-Delythera.launcher.profile=" + sanitize(ElymonConfig.SERVER_ID, 64));
        return args;
    }

    /**
     * ELYTHERA_KEY and ELYTHERA_UUID for the account being launched. Empty when no key
     * was compiled in (the mod then answers unsigned, as with an unsigned desktop build)
     * or when the account is unknown (e.g. the mod installer's JVM).
     */
    public static Map<String, String> environment(String elytheraKey, String accountUuid) {
        if (elytheraKey == null || elytheraKey.isEmpty() || accountUuid == null) {
            return Collections.emptyMap();
        }
        Map<String, String> env = new LinkedHashMap<>();
        env.put(ENV_KEY, elytheraKey);
        env.put(ENV_UUID, canonicalUuid(accountUuid));
        return env;
    }

    /**
     * Dashed lowercase form of an account UUID, like the desktop's
     * canonicalizeAccountUuid: the mod compares it with ServerPlayer#getUUID().toString().
     * Anything that is not 32 hex digits (with or without dashes) is only sanitised.
     */
    public static String canonicalUuid(String uuid) {
        String clean = sanitize(uuid, 36);
        String hex = clean.replace("-", "");
        if (hex.length() != 32 || !isHex(hex) || (clean.length() != 32 && clean.length() != 36)) {
            return clean;
        }
        hex = hex.toLowerCase(Locale.ROOT);
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
    }

    /**
     * Whether an environment variable must never be written to a log: every ELYTHERA_*
     * variable, and any name that looks like a credential.
     */
    public static boolean isSensitiveEnvName(String name) {
        if (name == null) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("ELYTHERA_") || upper.contains("KEY") || upper.contains("TOKEN")
                || upper.contains("SECRET") || upper.contains("PASS");
    }

    /** The value to log for an environment variable. */
    public static String loggableEnvValue(String name, String value) {
        return isSensitiveEnvName(name) ? MASK : value;
    }

    /**
     * The argument list as List#toString() prints it, with the value that follows
     * --accessToken, --session, --uuid and --xuid (or is attached with '=') masked.
     */
    public static String redactArgs(List<String> args) {
        if (args == null) {
            return "null";
        }
        List<String> copy = new ArrayList<>(args.size());
        boolean maskNext = false;
        for (String arg : args) {
            if (maskNext) {
                copy.add(MASK);
                maskNext = false;
                continue;
            }
            String secretName = secretArgName(arg);
            if (secretName == null) {
                copy.add(arg);
            } else if (arg.length() > secretName.length()) {
                copy.add(secretName + "=" + MASK);
            } else {
                copy.add(arg);
                maskNext = true;
            }
        }
        return copy.toString();
    }

    private static String secretArgName(String arg) {
        if (arg == null) {
            return null;
        }
        for (String name : SECRET_GAME_ARGS) {
            if (arg.equals(name) || arg.startsWith(name + "=")) {
                return name;
            }
        }
        return null;
    }

    /** Same as the desktop's sanitizeSystemPropertyValue: no control characters, trimmed, capped. */
    static String sanitize(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x20 && c != 0x7F) {
                out.append(c);
            }
        }
        String trimmed = out.toString().trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    private static boolean isHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
