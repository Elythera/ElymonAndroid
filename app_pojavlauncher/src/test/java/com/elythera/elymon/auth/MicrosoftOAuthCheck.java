package com.elythera.elymon.auth;

import com.elythera.elymon.ElymonConfig;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Host-side check of the pure-Java part of the Microsoft sign-in (PKCE, URLs,
 * redirect parsing, token forms). It needs no Android and no JUnit. Run from
 * the repository root:
 *
 * <pre>
 * S=app_pojavlauncher/src/main/java/com/elythera/elymon; T=$(mktemp -d)
 * javac -source 8 -target 8 -d "$T" $S/ElymonConfig.java $S/auth/MicrosoftOAuth.java \
 *     $S/auth/AuthorizationGrant.java app_pojavlauncher/src/test/java/com/elythera/elymon/auth/MicrosoftOAuthCheck.java
 * java -cp "$T" com.elythera.elymon.auth.MicrosoftOAuthCheck
 * </pre>
 *
 * Exits with status 1 on the first failed check.
 */
public final class MicrosoftOAuthCheck {
    private static int sChecks;

    public static void main(String[] args) {
        rfc7636AppendixB();
        base64UrlMatchesJdk();
        newVerifiersAreValidAndDistinct();
        verifierValidation();
        authorizeUrl();
        redirectDetection();
        redirectParsing();
        tokenForms();
        oauthErrors();
        noSecretInToString();
        System.out.println("MicrosoftOAuthCheck: " + sChecks + " checks passed");
    }

    /** RFC 7636 Appendix B: the example verifier, its octets and its S256 challenge. */
    private static void rfc7636AppendixB() {
        int[] octets = {116, 24, 223, 180, 151, 153, 224, 37, 79, 250, 96, 125, 216, 173, 187, 186,
                22, 212, 37, 77, 105, 214, 191, 240, 91, 88, 5, 88, 83, 132, 141, 121};
        byte[] bytes = new byte[octets.length];
        for (int i = 0; i < octets.length; i++) bytes[i] = (byte) octets[i];
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        equal("RFC 7636 verifier encoding", verifier, MicrosoftOAuth.base64Url(bytes));
        equal("RFC 7636 S256 challenge", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                MicrosoftOAuth.codeChallengeS256(verifier));
    }

    private static void base64UrlMatchesJdk() {
        Random random = new Random(42);
        Base64.Encoder jdk = Base64.getUrlEncoder().withoutPadding();
        for (int length = 0; length <= 100; length++) {
            byte[] data = new byte[length];
            random.nextBytes(data);
            equal("base64url of " + length + " bytes", jdk.encodeToString(data), MicrosoftOAuth.base64Url(data));
        }
    }

    private static void newVerifiersAreValidAndDistinct() {
        SecureRandom random = new SecureRandom();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String verifier = MicrosoftOAuth.newCodeVerifier(random);
            check("verifier is 43 characters", verifier.length() == 43);
            check("verifier is RFC 7636 valid", MicrosoftOAuth.isValidCodeVerifier(verifier));
            check("verifier is new", seen.add(verifier));
            String challenge = MicrosoftOAuth.codeChallengeS256(verifier);
            check("challenge is 43 base64url characters", challenge.length() == 43 && challenge.matches("[A-Za-z0-9_-]+"));
            check("challenge differs from verifier", !challenge.equals(verifier));
            String state = MicrosoftOAuth.newState(random);
            check("state is 22 base64url characters", state.length() == 22 && state.matches("[A-Za-z0-9_-]+"));
        }
    }

    private static void verifierValidation() {
        check("null verifier", !MicrosoftOAuth.isValidCodeVerifier(null));
        check("42 characters", !MicrosoftOAuth.isValidCodeVerifier(repeat('a', 42)));
        check("43 characters", MicrosoftOAuth.isValidCodeVerifier(repeat('a', 43)));
        check("128 characters", MicrosoftOAuth.isValidCodeVerifier(repeat('a', 128)));
        check("129 characters", !MicrosoftOAuth.isValidCodeVerifier(repeat('a', 129)));
        check("unreserved punctuation", MicrosoftOAuth.isValidCodeVerifier(repeat('a', 39) + "-._~"));
        check("reserved character", !MicrosoftOAuth.isValidCodeVerifier(repeat('a', 42) + "+"));
    }

    private static void authorizeUrl() {
        String url = MicrosoftOAuth.authorizeUrl(ElymonConfig.AZURE_CLIENT_ID, ElymonConfig.AZURE_REDIRECT_URI,
                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", "st_ate-1");
        equal("authorize URL",
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"
                        + "?client_id=6aff0b13-f7fd-4cf3-8c5a-add5a6fe7bfe"
                        + "&response_type=code"
                        + "&redirect_uri=https%3A%2F%2Flogin.microsoftonline.com%2Fcommon%2Foauth2%2Fnativeclient"
                        + "&scope=XboxLive.signin%20offline_access"
                        + "&prompt=select_account"
                        + "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
                        + "&code_challenge_method=S256"
                        + "&state=st_ate-1",
                url);
        // Same values as the desktop launcher (ElytheraLauncher app/assets/js/ipcconstants.js, index.js).
        equal("client id", "6aff0b13-f7fd-4cf3-8c5a-add5a6fe7bfe", ElymonConfig.AZURE_CLIENT_ID);
        equal("redirect URI", "https://login.microsoftonline.com/common/oauth2/nativeclient", ElymonConfig.AZURE_REDIRECT_URI);
    }

    private static void redirectDetection() {
        String r = ElymonConfig.AZURE_REDIRECT_URI;
        check("redirect with query", MicrosoftOAuth.isRedirect(r + "?code=M.C1_abc&state=x", r));
        check("redirect with fragment", MicrosoftOAuth.isRedirect(r + "#code=abc", r));
        check("bare redirect", MicrosoftOAuth.isRedirect(r, r));
        check("host case", MicrosoftOAuth.isRedirect("https://LOGIN.microsoftonline.com/common/oauth2/nativeclient?code=a", r));
        check("longer path", !MicrosoftOAuth.isRedirect(r + "x?code=a", r));
        check("authorize page", !MicrosoftOAuth.isRedirect(MicrosoftOAuth.AUTHORIZE_URL + "?client_id=x", r));
        check("redirect inside a query", !MicrosoftOAuth.isRedirect("https://example.com/?next=" + r + "?code=a", r));
        check("http scheme", !MicrosoftOAuth.isRedirect("http://login.microsoftonline.com/common/oauth2/nativeclient?code=a", r));
        check("null URL", !MicrosoftOAuth.isRedirect(null, r));
    }

    private static void redirectParsing() {
        String r = ElymonConfig.AZURE_REDIRECT_URI;
        MicrosoftOAuth.Redirect ok = MicrosoftOAuth.parseRedirect(r + "?code=M.C507_BAY.2.U.a%2Bb%3D&state=st_ate-1");
        equal("code", "M.C507_BAY.2.U.a+b=", ok.code);
        equal("state", "st_ate-1", ok.state);
        check("no error", ok.error == null);

        MicrosoftOAuth.Redirect denied = MicrosoftOAuth.parseRedirect(
                r + "?error=access_denied&error_description=The+user+has+denied%20access&state=s");
        equal("error", "access_denied", denied.error);
        equal("error description", "The user has denied access", denied.errorDescription);
        check("no code on error", denied.code == null);

        MicrosoftOAuth.Redirect fragment = MicrosoftOAuth.parseRedirect(r + "#code=abc&state=s");
        equal("code from fragment", "abc", fragment.code);

        MicrosoftOAuth.Redirect both = MicrosoftOAuth.parseRedirect(r + "?code=fromquery&state=s#code=fromfragment");
        equal("query wins over fragment", "fromquery", both.code);

        MicrosoftOAuth.Redirect empty = MicrosoftOAuth.parseRedirect(r + "?code=&state=s");
        check("empty code is no code", empty.code == null);

        MicrosoftOAuth.Redirect none = MicrosoftOAuth.parseRedirect(r);
        check("no parameters", none.code == null && none.state == null && none.error == null);

        MicrosoftOAuth.Redirect malformed = MicrosoftOAuth.parseRedirect(r + "?code=a%ZZ&state=s");
        equal("malformed escape kept raw", "a%ZZ", malformed.code);
    }

    private static void tokenForms() {
        equal("authorization code form",
                "client_id=6aff0b13-f7fd-4cf3-8c5a-add5a6fe7bfe"
                        + "&scope=XboxLive.signin+offline_access"
                        + "&redirect_uri=https%3A%2F%2Flogin.microsoftonline.com%2Fcommon%2Foauth2%2Fnativeclient"
                        + "&code=M.C1%2Bx%3D"
                        + "&grant_type=authorization_code"
                        + "&code_verifier=dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",
                MicrosoftOAuth.authorizationCodeForm(ElymonConfig.AZURE_CLIENT_ID, ElymonConfig.AZURE_REDIRECT_URI,
                        "M.C1+x=", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        equal("refresh form",
                "client_id=6aff0b13-f7fd-4cf3-8c5a-add5a6fe7bfe"
                        + "&scope=XboxLive.signin+offline_access"
                        + "&redirect_uri=https%3A%2F%2Flogin.microsoftonline.com%2Fcommon%2Foauth2%2Fnativeclient"
                        + "&refresh_token=M.R3_BAY.a%21b*"
                        + "&grant_type=refresh_token",
                MicrosoftOAuth.refreshTokenForm(ElymonConfig.AZURE_CLIENT_ID, ElymonConfig.AZURE_REDIRECT_URI,
                        "M.R3_BAY.a!b*"));
    }

    private static void oauthErrors() {
        check("invalid_grant needs a new sign-in", MicrosoftOAuth.requiresNewSignIn("invalid_grant"));
        check("interaction_required needs a new sign-in", MicrosoftOAuth.requiresNewSignIn("interaction_required"));
        check("invalid_client does not", !MicrosoftOAuth.requiresNewSignIn("invalid_client"));
        check("null does not", !MicrosoftOAuth.requiresNewSignIn(null));
        equal("plain code kept", "invalid_grant", MicrosoftOAuth.safeErrorCode("invalid_grant"));
        equal("free text replaced", "unknown", MicrosoftOAuth.safeErrorCode("AADSTS70000: token=abc"));
        equal("null replaced", "unknown", MicrosoftOAuth.safeErrorCode(null));
    }

    private static void noSecretInToString() {
        String r = ElymonConfig.AZURE_REDIRECT_URI;
        String redirect = MicrosoftOAuth.parseRedirect(r + "?code=SECRETCODE&state=s").toString();
        check("redirect toString hides the code", !redirect.contains("SECRETCODE"));
        String grant = new AuthorizationGrant("SECRETCODE", "SECRETVERIFIER").toString();
        check("grant toString hides the code", !grant.contains("SECRETCODE") && !grant.contains("SECRETVERIFIER"));
        AuthorizationGrant once = new AuthorizationGrant("c", "v");
        check("grant claimed once", once.claim() && !once.claim());
        check("ASCII only verifier bytes", new String(MicrosoftOAuth.newCodeVerifier(new SecureRandom())
                .getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII).length() == 43);
    }

    private static String repeat(char c, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) builder.append(c);
        return builder.toString();
    }

    private static void equal(String what, String expected, String actual) {
        sChecks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            System.err.println("FAILED " + what + "\n  expected: " + expected + "\n  actual:   " + actual);
            System.exit(1);
        }
    }

    private static void check(String what, boolean condition) {
        sChecks++;
        if (!condition) {
            System.err.println("FAILED " + what);
            System.exit(1);
        }
    }
}
