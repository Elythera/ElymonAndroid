package com.elythera.elymon.support;

import java.util.ArrayList;
import java.util.List;

/**
 * What JREUtils writes to latestlog.txt about a launch, for support:
 * <ul>
 * <li>one line with what ElytheraMod will read (brand, version, profile) and whether
 * ELYTHERA_KEY reached the game's environment, never its value;</li>
 * <li>the whole JVM argument list, redacted, cut in lines under 3 KB. Upstream printed
 * it with System.out in one line, which logcat truncates at about 4 KB, so the
 * -Delythera.* properties at the end never showed anywhere.</li>
 * </ul>
 *
 * Pure Java (no Android imports) for host-side tests.
 */
public final class LaunchDiagnostics {
    /** Line budget in UTF-8 bytes (the native Logger copies each line on the stack). */
    public static final int MAX_LINE_BYTES = 3000;

    static final String PROP_BRAND = "-Delythera.launcher=";
    static final String PROP_VERSION = "-Delythera.launcher.version=";
    static final String PROP_PROFILE = "-Delythera.launcher.profile=";
    private static final String ABSENT = "absent";
    private static final String[] SECRET_ARGS = {"--accessToken", "--session", "--uuid", "--xuid", "--clientId"};

    private LaunchDiagnostics() {}

    /** Where the lines go: Logger::appendToLog on the device, a list in the tests. */
    public interface Sink {
        void append(String line);
    }

    /** Writes the summary line, then the argument lines. */
    public static void write(List<String> jvmArgs, boolean keyInEnvironment, Sink sink) {
        sink.append(summaryLine(jvmArgs, keyInEnvironment));
        for (String line : argumentLines(jvmArgs, MAX_LINE_BYTES)) {
            sink.append(line);
        }
    }

    /**
     * "Elythera : launcher=elythera version=1.4.0-android.3 profil=elymon-1.21.1 clé=présente",
     * read from the arguments themselves, so it shows what the game really receives.
     */
    public static String summaryLine(List<String> jvmArgs, boolean keyInEnvironment) {
        return "Elythera : launcher=" + property(jvmArgs, PROP_BRAND)
                + " version=" + property(jvmArgs, PROP_VERSION)
                + " profil=" + property(jvmArgs, PROP_PROFILE)
                + " clé=" + (keyInEnvironment ? "présente" : "absente");
    }

    /**
     * The arguments, redacted, joined with spaces into lines of at most maxBytes UTF-8
     * bytes, each prefixed with "Arguments JVM (i/n) : ". An argument longer than a line
     * (the class path) continues on the next lines.
     */
    public static List<String> argumentLines(List<String> jvmArgs, int maxBytes) {
        List<String> redacted = redactArgs(jvmArgs);
        // Room for the prefix, whose numbers are not known yet.
        int budget = Math.max(64, maxBytes - 40);
        List<String> bodies = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentBytes = 0;
        for (String arg : redacted) {
            String piece = currentBytes == 0 ? arg : " " + arg;
            int pieceBytes = utf8Length(piece);
            if (currentBytes + pieceBytes <= budget) {
                current.append(piece);
                currentBytes += pieceBytes;
                continue;
            }
            if (currentBytes > 0) {
                bodies.add(current.toString());
                current.setLength(0);
                currentBytes = 0;
            }
            // The argument alone: split it if it is still too long.
            int start = 0;
            while (start < arg.length()) {
                int end = fit(arg, start, budget);
                String part = arg.substring(start, end);
                start = end;
                if (start < arg.length()) {
                    bodies.add(part);
                } else {
                    current.append(part);
                    currentBytes = utf8Length(part);
                }
            }
        }
        if (currentBytes > 0) {
            bodies.add(current.toString());
        }
        List<String> lines = new ArrayList<>(bodies.size());
        for (int i = 0; i < bodies.size(); i++) {
            lines.add("Arguments JVM (" + (i + 1) + "/" + bodies.size() + ") : " + bodies.get(i));
        }
        return lines;
    }

    /**
     * Copy of the arguments with the value after --accessToken, --session, --uuid, --xuid
     * and --clientId masked, then every argument through {@link LogRedactor}.
     */
    public static List<String> redactArgs(List<String> args) {
        List<String> out = new ArrayList<>(args == null ? 0 : args.size());
        if (args == null) {
            return out;
        }
        boolean maskNext = false;
        for (String arg : args) {
            if (arg == null) {
                out.add("null");
                maskNext = false;
                continue;
            }
            if (maskNext) {
                out.add(LogRedactor.MASK);
                maskNext = false;
                continue;
            }
            String secret = secretArg(arg);
            if (secret != null && arg.equals(secret)) {
                out.add(arg);
                maskNext = true;
            } else if (secret != null) {
                out.add(secret + "=" + LogRedactor.MASK);
            } else {
                out.add(LogRedactor.redactLine(arg));
            }
        }
        return out;
    }

    private static String secretArg(String arg) {
        for (String name : SECRET_ARGS) {
            if (arg.equals(name) || arg.startsWith(name + "=")) {
                return name;
            }
        }
        return null;
    }

    /** Value of the last -Dname=value in the list (the JVM keeps the last one), or "absent". */
    private static String property(List<String> args, String prefix) {
        String value = null;
        if (args != null) {
            for (String arg : args) {
                if (arg != null && arg.startsWith(prefix)) {
                    value = arg.substring(prefix.length());
                }
            }
        }
        if (value == null) {
            return ABSENT;
        }
        value = LogRedactor.redactLine(value.trim());
        return value.isEmpty() ? "(vide)" : value.replace(' ', '_');
    }

    /** End index of the longest piece of s starting at start that fits in maxBytes. */
    private static int fit(String s, int start, int maxBytes) {
        int bytes = 0;
        int i = start;
        while (i < s.length()) {
            int b = charBytes(s.charAt(i));
            if (bytes + b > maxBytes) {
                break;
            }
            bytes += b;
            i++;
        }
        if (i == start) {
            i++;
        }
        // Do not cut a surrogate pair.
        if (i < s.length() && Character.isLowSurrogate(s.charAt(i)) && i - 1 > start) {
            i--;
        }
        return i;
    }

    static int utf8Length(String s) {
        int bytes = 0;
        for (int i = 0; i < s.length(); i++) {
            bytes += charBytes(s.charAt(i));
        }
        return bytes;
    }

    /** Bytes of one char in modified UTF-8 (what JNI's GetStringUTFRegion produces). */
    private static int charBytes(char c) {
        if (c != 0 && c < 0x80) {
            return 1;
        }
        return c < 0x800 ? 2 : 3;
    }
}
