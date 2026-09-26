package com.elythera.elymon.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes secrets from a log line before it leaves the phone (support zip, launch
 * diagnostics). Defence in depth: the launcher already keeps the Minecraft token and
 * ELYTHERA_KEY out of its own logs, but a mod, a crash report or an old log file may not.
 *
 * What is masked, in this order:
 * <ul>
 * <li>JSON fields that hold a token ("accessToken", "access_token", "refresh_token",
 * "Token", "ELYTHERA_KEY"...);</li>
 * <li>form and query parameters (access_token=, refresh_token=, a long code=...);</li>
 * <li>the value after --accessToken, --session, --uuid, --xuid and --clientId, whether
 * written "--accessToken X", "--accessToken, X" (List#toString) or "--accessToken=X";</li>
 * <li>ELYTHERA_KEY=value and ELYTHERA_KEY: value;</li>
 * <li>"Bearer X" and "XBL3.0 x=X" authorisation headers;</li>
 * <li>JWTs (eyJ...: Minecraft access tokens, Xbox tokens), Microsoft compact tokens
 * (EwA...) and Microsoft codes and refresh tokens (M.C..., M.R...);</li>
 * <li>any 64-hex-digit string (ELYTHERA_KEY, but also SHA-256 digests: acceptable);</li>
 * <li>long opaque base64 runs mixing upper case, lower case and digits, which is what the
 * rest of a token looks like when a log writer cut it across two lines.</li>
 * </ul>
 *
 * Pure Java (no Android imports) for host-side tests.
 */
public final class LogRedactor {
    /** Same mask as com.elythera.elymon.ElymonGameArgs.MASK. */
    public static final String MASK = "<masqué>";

    private static final Pattern JSON_FIELD = Pattern.compile(
            "(?i)(\"(?:access_?token|refresh_?token|msa_?refresh_?token|id_?token|token|client_?secret|code_?verifier|elythera_key)\"\\s*:\\s*)\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final Pattern FORM_FIELD = Pattern.compile(
            "(?i)(\\b(?:access_token|refresh_token|id_token|client_secret|code_verifier)=)[^&\\s\"',;]+");
    private static final Pattern FORM_CODE = Pattern.compile(
            "(?i)(\\bcode=)[A-Za-z0-9._!*$~\\-]{10,}");
    private static final Pattern GAME_ARG = Pattern.compile(
            "(--(?:accessToken|session|uuid|xuid|clientId))(=|\\s*,\\s*|\\s+)(\"?)(?!" + Pattern.quote(MASK) + ")[^\\s,\\[\\]\"]+");
    private static final Pattern ELYTHERA_KEY = Pattern.compile(
            "(ELYTHERA_KEY\\s*[=:]\\s*)(\"?)(?!" + Pattern.quote(MASK) + ")[^\\s\"',;]+");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)(\\bBearer\\s+)[A-Za-z0-9._~+/=\\-]{8,}");
    private static final Pattern XBL = Pattern.compile(
            "(XBL3\\.0\\s+x=)[^\\s\"',]+");
    private static final Pattern JWT = Pattern.compile(
            "eyJ[A-Za-z0-9_\\-]{8,}(?:\\.[A-Za-z0-9_\\-]*){0,2}");
    private static final Pattern MS_COMPACT = Pattern.compile(
            "(?<![A-Za-z0-9+/])Ew[A-Z][A-Za-z0-9+/=_\\-%!*]{16,}");
    private static final Pattern MS_CODE = Pattern.compile(
            "(?<![A-Za-z0-9])M\\.[CR][0-9A-Za-z]*_[A-Za-z0-9]+\\.[^\\s\"',;&\\[\\]{}()<>]+");
    private static final Pattern HEX64 = Pattern.compile(
            "(?<![0-9A-Fa-f])[0-9A-Fa-f]{64}(?![0-9A-Fa-f])");
    private static final Pattern OPAQUE_URLSAFE = Pattern.compile("[A-Za-z0-9_\\-]{120,}");
    private static final Pattern OPAQUE_BASE64 = Pattern.compile("[A-Za-z0-9+/=]{200,}");

    private LogRedactor() {}

    /** The line with every secret replaced by {@link #MASK}. Null stays null. */
    public static String redactLine(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        String out = line;
        out = JSON_FIELD.matcher(out).replaceAll("$1\"" + Matcher.quoteReplacement(MASK) + "\"");
        out = FORM_FIELD.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(MASK));
        out = FORM_CODE.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(MASK));
        out = GAME_ARG.matcher(out).replaceAll("$1$2$3" + Matcher.quoteReplacement(MASK));
        out = ELYTHERA_KEY.matcher(out).replaceAll("$1$2" + Matcher.quoteReplacement(MASK));
        out = BEARER.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(MASK));
        out = XBL.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(MASK));
        out = JWT.matcher(out).replaceAll(Matcher.quoteReplacement(MASK));
        out = MS_COMPACT.matcher(out).replaceAll(Matcher.quoteReplacement(MASK));
        out = MS_CODE.matcher(out).replaceAll(Matcher.quoteReplacement(MASK));
        out = HEX64.matcher(out).replaceAll(Matcher.quoteReplacement(MASK));
        out = maskMixedRuns(OPAQUE_URLSAFE, out);
        out = maskMixedRuns(OPAQUE_BASE64, out);
        return out;
    }

    /** Whether redactLine would change the line. */
    public static boolean containsSecret(String line) {
        return line != null && !line.equals(redactLine(line));
    }

    /**
     * Masks the runs that mix upper case, lower case and digits: a separator line of
     * dashes or a long word is not a token.
     */
    private static String maskMixedRuns(Pattern pattern, String line) {
        Matcher matcher = pattern.matcher(line);
        StringBuffer out = null;
        while (matcher.find()) {
            if (!isMixed(matcher.group())) {
                continue;
            }
            if (out == null) {
                out = new StringBuffer(line.length());
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(MASK));
        }
        if (out == null) {
            return line;
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean isMixed(String run) {
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        for (int i = 0; i < run.length(); i++) {
            char c = run.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                upper = true;
            } else if (c >= 'a' && c <= 'z') {
                lower = true;
            } else if (c >= '0' && c <= '9') {
                digit = true;
            }
            if (upper && lower && digit) {
                return true;
            }
        }
        return false;
    }
}
