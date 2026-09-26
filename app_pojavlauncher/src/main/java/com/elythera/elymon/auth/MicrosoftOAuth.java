package com.elythera.elymon.auth;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * OAuth 2.0 pieces of the Microsoft sign-in through Elythera's Entra app:
 * PKCE (RFC 7636), the authorize URL, the redirect parsing and the token
 * request bodies.
 *
 * It follows the desktop launcher, which is known to work with this client id:
 * ElytheraLauncher index.js builds the same authorize URL, and helios-core
 * MicrosoftAuth.js sends the same token form. On top of that, this class adds
 * PKCE and a state value, which the desktop does not send. Entra accepts both
 * from a public client without any portal change.
 *
 * Pure Java on purpose (no android.* import, no java.util.Base64, which needs
 * API 26), so MicrosoftOAuthCheck can run it on the host.
 *
 * Nothing here logs, and the toString() of every value that holds a code
 * says only that it is redacted.
 */
public final class MicrosoftOAuth {
    private MicrosoftOAuth() {}

    public static final String AUTHORIZE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
    public static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    /** Same scope as the desktop (helios-core MicrosoftAuth.js): Xbox sign-in plus a refresh token. */
    public static final String SCOPE = "XboxLive.signin offline_access";

    /** 32 random bytes give a 43-character verifier, the shortest RFC 7636 allows (section 4.1). */
    private static final int VERIFIER_BYTES = 32;
    private static final int STATE_BYTES = 16;

    private static final char[] BASE64_URL =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    /** A new PKCE code verifier: base64url of 32 bytes from {@code random}, 43 characters. */
    public static String newCodeVerifier(SecureRandom random) {
        byte[] bytes = new byte[VERIFIER_BYTES];
        random.nextBytes(bytes);
        return base64Url(bytes);
    }

    /** The S256 code challenge of a verifier: base64url(SHA-256(ASCII(verifier))), RFC 7636 section 4.2. */
    public static String codeChallengeS256(String codeVerifier) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return base64Url(sha256.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            // Every Java and Android runtime must provide SHA-256.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** A new value for the {@code state} parameter, checked when the redirect comes back. */
    public static String newState(SecureRandom random) {
        byte[] bytes = new byte[STATE_BYTES];
        random.nextBytes(bytes);
        return base64Url(bytes);
    }

    /** Whether {@code verifier} is a valid RFC 7636 code verifier: 43 to 128 unreserved characters. */
    public static boolean isValidCodeVerifier(String verifier) {
        if (verifier == null || verifier.length() < 43 || verifier.length() > 128) return false;
        for (int i = 0; i < verifier.length(); i++) {
            char c = verifier.charAt(i);
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~';
            if (!unreserved) return false;
        }
        return true;
    }

    /**
     * The authorize URL the WebView opens. Same parameters as the desktop
     * (index.js), plus PKCE and state. The scope is encoded with %20, like the desktop.
     */
    public static String authorizeUrl(String clientId, String redirectUri, String codeChallenge, String state) {
        return AUTHORIZE_URL + "?" + query(
                "client_id", clientId,
                "response_type", "code",
                "redirect_uri", redirectUri,
                "scope", SCOPE,
                "prompt", "select_account",
                "code_challenge", codeChallenge,
                "code_challenge_method", "S256",
                "state", state);
    }

    /**
     * Whether a URL is the redirect URI with its answer, for example
     * {@code https://login.microsoftonline.com/common/oauth2/nativeclient?code=…}.
     * The character after the prefix must end the path, so that a longer path
     * starting with the same letters does not match.
     */
    public static boolean isRedirect(String url, String redirectUri) {
        if (url == null || redirectUri == null) return false;
        if (!url.regionMatches(true, 0, redirectUri, 0, redirectUri.length())) return false;
        if (url.length() == redirectUri.length()) return true;
        char next = url.charAt(redirectUri.length());
        return next == '?' || next == '#' || next == '/';
    }

    /** What came back on the redirect URI. Read the query first, then the fragment. */
    public static Redirect parseRedirect(String url) {
        String code = null, state = null, error = null, errorDescription = null;
        int queryStart = url.indexOf('?');
        int fragmentStart = url.indexOf('#');
        String[] parts = new String[2];
        if (queryStart >= 0) {
            int queryEnd = fragmentStart > queryStart ? fragmentStart : url.length();
            parts[0] = url.substring(queryStart + 1, queryEnd);
        }
        if (fragmentStart >= 0) parts[1] = url.substring(fragmentStart + 1);

        for (String part : parts) {
            if (part == null || part.isEmpty()) continue;
            for (String pair : part.split("&")) {
                int eq = pair.indexOf('=');
                String key = decode(eq >= 0 ? pair.substring(0, eq) : pair);
                String value = eq >= 0 ? decode(pair.substring(eq + 1)) : "";
                switch (key) {
                    case "code": if (code == null) code = value; break;
                    case "state": if (state == null) state = value; break;
                    case "error": if (error == null) error = value; break;
                    case "error_description": if (errorDescription == null) errorDescription = value; break;
                    default: break;
                }
            }
        }
        if (code != null && code.isEmpty()) code = null;
        if (error != null && error.isEmpty()) error = null;
        return new Redirect(code, state, error, errorDescription);
    }

    /** Form body that redeems an authorization code, as helios-core MicrosoftAuth.getAccessToken does, plus the PKCE verifier. */
    public static String authorizationCodeForm(String clientId, String redirectUri, String code, String codeVerifier) {
        return form(
                "client_id", clientId,
                "scope", SCOPE,
                "redirect_uri", redirectUri,
                "code", code,
                "grant_type", "authorization_code",
                "code_verifier", codeVerifier);
    }

    /** Form body that exchanges a refresh token, the same as helios-core MicrosoftAuth.getAccessToken(refresh = true). */
    public static String refreshTokenForm(String clientId, String redirectUri, String refreshToken) {
        return form(
                "client_id", clientId,
                "scope", SCOPE,
                "redirect_uri", redirectUri,
                "refresh_token", refreshToken,
                "grant_type", "refresh_token");
    }

    /**
     * Whether an OAuth error from the token endpoint means that the stored
     * session is gone and the player has to sign in again. invalid_grant covers
     * an expired, revoked or already used code or refresh token.
     */
    public static boolean requiresNewSignIn(String oauthError) {
        return "invalid_grant".equals(oauthError)
                || "interaction_required".equals(oauthError)
                || "consent_required".equals(oauthError)
                || "login_required".equals(oauthError);
    }

    /**
     * Returns an OAuth error code only if it is safe to log or show:
     * lower-case letters and underscores, at most 64 characters.
     * Anything else becomes "unknown".
     */
    public static String safeErrorCode(String oauthError) {
        if (oauthError == null || oauthError.isEmpty() || oauthError.length() > 64) return "unknown";
        for (int i = 0; i < oauthError.length(); i++) {
            char c = oauthError.charAt(i);
            if (!((c >= 'a' && c <= 'z') || c == '_')) return "unknown";
        }
        return oauthError;
    }

    /** base64url without padding (RFC 4648 section 5), as PKCE requires. */
    public static String base64Url(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 4 + 2) / 3);
        int i = 0;
        for (; i + 2 < data.length; i += 3) {
            int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8) | (data[i + 2] & 0xff);
            out.append(BASE64_URL[(n >>> 18) & 63]).append(BASE64_URL[(n >>> 12) & 63])
                    .append(BASE64_URL[(n >>> 6) & 63]).append(BASE64_URL[n & 63]);
        }
        int rest = data.length - i;
        if (rest == 1) {
            int n = (data[i] & 0xff) << 16;
            out.append(BASE64_URL[(n >>> 18) & 63]).append(BASE64_URL[(n >>> 12) & 63]);
        } else if (rest == 2) {
            int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8);
            out.append(BASE64_URL[(n >>> 18) & 63]).append(BASE64_URL[(n >>> 12) & 63])
                    .append(BASE64_URL[(n >>> 6) & 63]);
        }
        return out.toString();
    }

    /** application/x-www-form-urlencoded body from key, value pairs. */
    static String form(String... keyValues) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (builder.length() > 0) builder.append('&');
            builder.append(encode(keyValues[i])).append('=').append(encode(keyValues[i + 1]));
        }
        return builder.toString();
    }

    /** Query string from key, value pairs, with spaces as %20 like the desktop URL. */
    static String query(String... keyValues) {
        // URLEncoder writes a space as '+' and a literal '+' as %2B, so this replacement is exact.
        return form(keyValues).replace("+", "%20");
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e); // UTF-8 always exists
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e); // UTF-8 always exists
        } catch (IllegalArgumentException e) {
            return value; // malformed escape: keep it raw rather than fail the whole redirect
        }
    }

    /** The answer carried by the redirect URI. {@link #toString()} never shows the code. */
    public static final class Redirect {
        public final String code;
        public final String state;
        public final String error;
        public final String errorDescription;

        Redirect(String code, String state, String error, String errorDescription) {
            this.code = code;
            this.state = state;
            this.error = error;
            this.errorDescription = errorDescription;
        }

        @Override
        public String toString() {
            return error != null
                    ? "Redirect[error=" + safeErrorCode(error) + "]"
                    : "Redirect[" + (code != null ? "code=<redacted>" : "no code") + "]";
        }
    }
}
