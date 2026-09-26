package net.kdt.pojavlaunch.authenticator.microsoft;

import static net.kdt.pojavlaunch.PojavApplication.sExecutorService;

import android.util.ArrayMap;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.elythera.elymon.ElymonConfig;
import com.elythera.elymon.auth.ElymonAccounts;
import com.elythera.elymon.auth.ElymonAuthException.Kind;
import com.elythera.elymon.auth.ElymonSession;
import com.elythera.elymon.auth.MicrosoftAuthFailure;
import com.elythera.elymon.auth.MicrosoftOAuth;
import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.authenticator.listener.DoneListener;
import net.kdt.pojavlaunch.authenticator.listener.ErrorListener;
import net.kdt.pojavlaunch.authenticator.listener.ProgressListener;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Allow to perform a background login on a given account */
// ELYMON: Microsoft sign-in through Elythera's Entra app, as the desktop launcher does
// (helios-core MicrosoftAuth.js): v2 /consumers token endpoint with PKCE, RpsTicket "d=",
// explicit refusal of accounts without Minecraft Java instead of a demo account, French
// errors (elymon_auth_strings.xml), and no secret in any log or exception message: only
// step names and HTTP statuses are logged.
public class MicrosoftBackgroundLogin {
    // ELYMON: one tag, and never a code, a token or a response body after it.
    private static final String TAG = "MicrosoftLogin";
    // ELYMON: v2 endpoint of the Elythera Entra app instead of login.live.com.
    private static final String authTokenUrl = MicrosoftOAuth.TOKEN_URL;
    private static final String xblAuthUrl = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String xstsAuthUrl = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String mcLoginUrl = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String mcProfileUrl = "https://api.minecraftservices.com/minecraft/profile";
    private static final String mcStoreUrl = "https://api.minecraftservices.com/entitlements/mcstore";

    // ELYMON: Xbox Game Pass players have a Minecraft profile but no entitlement at all
    // (wiki.vg "Checking Game Ownership"), and the desktop launcher accepts any account
    // with a profile. So a profile is enough; entitlements are read when there is no
    // profile, to tell "does not own the game" from "owns it but has no name yet".
    // true refuses every account without a Java or Game Pass entitlement.
    static final boolean REQUIRE_JAVA_ENTITLEMENT = false;

    // ELYMON: upstream had no timeout, which could block the Play button forever.
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;
    // ELYMON: the Minecraft token lifetime when login_with_xbox does not say (upstream hard-coded it).
    private static final long DEFAULT_MC_TOKEN_LIFETIME_S = 86400;

    private final boolean mIsRefresh;
    // ELYMON: not final, a refresh re-reads the latest refresh token from disk under the lock.
    private String mAuthCode;
    // ELYMON: PKCE verifier of the authorization code; null for a refresh.
    @Nullable private final String mCodeVerifier;
    // ELYMON: name of the account being refreshed, to re-read it under the lock; null otherwise.
    @Nullable private final String mRefreshedAccountName;
    private static final Map<Long, Integer> XSTS_ERRORS;
    static {
        // ELYMON: French messages mirroring the desktop (helios-core MicrosoftResponse.js,
        // Elythera fr_FR.toml), plus the other documented XErr codes.
        XSTS_ERRORS = new ArrayMap<>();
        XSTS_ERRORS.put(2148916227L, R.string.elymon_auth_xerr_banned);
        XSTS_ERRORS.put(2148916233L, R.string.elymon_auth_xerr_no_account);
        XSTS_ERRORS.put(2148916234L, R.string.elymon_auth_xerr_terms);
        XSTS_ERRORS.put(2148916235L, R.string.elymon_auth_xerr_not_available);
        XSTS_ERRORS.put(2148916236L, R.string.elymon_auth_xerr_adult_verification);
        XSTS_ERRORS.put(2148916237L, R.string.elymon_auth_xerr_adult_verification);
        XSTS_ERRORS.put(2148916238L, R.string.elymon_auth_xerr_child);
    }

    /* Fields used to fill the account  */
    public String msRefreshToken;
    public String mcName;
    public String mcToken;
    public String mcUuid;
    public boolean doesOwnGame, hasProfile = false;
    public long expiresAt;

    public MicrosoftBackgroundLogin(boolean isRefresh, String authCode){
        this(isRefresh, authCode, null, null);
    }

    // ELYMON: a new sign-in redeems the authorization code with its PKCE verifier.
    public MicrosoftBackgroundLogin(String authCode, String codeVerifier) {
        this(false, authCode, codeVerifier, null);
    }

    private MicrosoftBackgroundLogin(boolean isRefresh, String authCode,
                                     @Nullable String codeVerifier, @Nullable String refreshedAccountName) {
        mIsRefresh = isRefresh;
        mAuthCode = authCode;
        mCodeVerifier = codeVerifier;
        mRefreshedAccountName = refreshedAccountName;
    }

    // ELYMON: refresh of a stored account, which is re-read from disk under the lock.
    public static MicrosoftBackgroundLogin refreshing(@NonNull MinecraftAccount account) {
        return new MicrosoftBackgroundLogin(true, account.msaRefreshToken, null, account.username);
    }

    // ELYMON: whether a stored account holds a refresh token at all ("0" is upstream's placeholder).
    public static boolean hasRefreshToken(@NonNull MinecraftAccount account) {
        return account.msaRefreshToken != null && !account.msaRefreshToken.isEmpty()
                && !"0".equals(account.msaRefreshToken);
    }

    /** Performs a full login, calling back listeners appropriately  */
    public void performLogin(@Nullable final ProgressListener progressListener,
                             @Nullable final DoneListener doneListener,
                             @Nullable final ErrorListener errorListener){
        sExecutorService.execute(() -> {
            try {
                // ELYMON: the chain moved to loginBlocking(), shared with ElymonSession.ensureFresh.
                MinecraftAccount acc = loginBlocking(progressListener, true);
                if(doneListener != null) {
                    Tools.runOnUiThread(() -> doneListener.onLoginDone(acc));
                }

            }catch (Exception e){
                // ELYMON: safe to log, no exception thrown here carries a secret.
                Log.e(TAG, "Exception thrown during authentication", e);
                if(errorListener != null)
                    Tools.runOnUiThread(() -> errorListener.onLoginError(e));
            }
            ProgressLayout.clearProgress(ProgressLayout.AUTHENTICATE_MICROSOFT);
        });
    }

    /**
     * ELYMON: the whole chain, on the calling thread (never the UI thread): Microsoft token,
     * Xbox Live, XSTS, Minecraft token, profile (and entitlements when needed), then the
     * account is saved and returned. Every failure is a MicrosoftAuthFailure with a French
     * message. With showProgress, the steps also appear in the launcher's progress bar,
     * and the caller clears it.
     */
    public MinecraftAccount loginBlocking(@Nullable ProgressListener progressListener, boolean showProgress) {
        synchronized (ElymonSession.REFRESH_LOCK) {
            try {
                if (mIsRefresh && mRefreshedAccountName != null) {
                    MinecraftAccount stored = MinecraftAccount.load(mRefreshedAccountName);
                    if (stored != null && stored.isMicrosoft) {
                        // Another refresh may have finished while we waited for the lock.
                        if (!ElymonSession.needsRefresh(stored)) {
                            Log.i(TAG, "Session already refreshed");
                            return stored;
                        }
                        mAuthCode = stored.msaRefreshToken;
                    }
                }
                if (mAuthCode == null || mAuthCode.isEmpty() || (mIsRefresh && "0".equals(mAuthCode))) {
                    throw new MicrosoftAuthFailure(Kind.RECONNECT, R.string.elymon_auth_session_expired);
                }
                if (!mIsRefresh && !MicrosoftOAuth.isValidCodeVerifier(mCodeVerifier)) {
                    // Only the PKCE flow of MicrosoftLoginFragment can produce a code.
                    throw new MicrosoftAuthFailure(Kind.RECONNECT, R.string.elymon_auth_redirect_invalid);
                }
                return runChain(progressListener, showProgress);
            } catch (MicrosoftAuthFailure failure) {
                throw failure;
            } catch (JSONException e) {
                // Dropped on purpose: an org.json message can quote the body it failed on.
                Log.w(TAG, "Unexpected response shape");
                throw new MicrosoftAuthFailure(Kind.TRANSIENT, R.string.elymon_auth_bad_response);
            } catch (IOException e) {
                Log.w(TAG, "Network failure: " + e.getClass().getSimpleName());
                throw new MicrosoftAuthFailure(e, Kind.TRANSIENT, R.string.elymon_auth_network);
            } catch (RuntimeException e) {
                Log.w(TAG, "Unexpected failure: " + e.getClass().getSimpleName());
                throw new MicrosoftAuthFailure(Kind.TRANSIENT, R.string.elymon_auth_unexpected);
            }
        }
    }

    private MinecraftAccount runChain(@Nullable ProgressListener progressListener, boolean showProgress)
            throws IOException, JSONException {
        notifyProgress(progressListener, showProgress, 1, R.string.elymon_auth_progress_microsoft);
        String accessToken = acquireAccessToken(mIsRefresh, mAuthCode);
        notifyProgress(progressListener, showProgress, 2, R.string.elymon_auth_progress_xbox);
        String xboxLiveToken = acquireXBLToken(accessToken);
        notifyProgress(progressListener, showProgress, 3, R.string.elymon_auth_progress_xbox);
        String[] xsts = acquireXsts(xboxLiveToken);
        notifyProgress(progressListener, showProgress, 4, R.string.elymon_auth_progress_minecraft);
        String mcToken = acquireMinecraftToken(xsts[0], xsts[1]);
        notifyProgress(progressListener, showProgress, 5, R.string.elymon_auth_progress_profile);
        checkMcProfile(mcToken);
        // ELYMON: see REQUIRE_JAVA_ENTITLEMENT.
        if (REQUIRE_JAVA_ENTITLEMENT || !hasProfile) fetchOwnedItems(mcToken);

        // ELYMON: never a demo account. Upstream turned a non-owner into "Demo.Player"
        // with an all-zero UUID; Elymon refuses the account instead.
        if (!hasProfile) {
            Log.i(TAG, doesOwnGame ? "Game owned but no Minecraft profile" : "Minecraft Java not owned");
            throw new MicrosoftAuthFailure(Kind.REFUSED,
                    doesOwnGame ? R.string.elymon_auth_no_profile : R.string.elymon_auth_not_owned);
        }
        if (REQUIRE_JAVA_ENTITLEMENT && !doesOwnGame) {
            Log.i(TAG, "Minecraft profile without a Java entitlement");
            throw new MicrosoftAuthFailure(Kind.REFUSED, R.string.elymon_auth_not_owned);
        }
        if (mcName == null || mcUuid == null)
            throw new IllegalStateException("This should never happen, please report this as a bug");

        MinecraftAccount acc = MinecraftAccount.load(mcName);
        // ELYMON: a renamed player is found again by UUID, so they don't end up with two accounts.
        String previousName = null;
        if (acc == null) {
            acc = ElymonAccounts.findByProfileId(mcUuid);
            if (acc != null) previousName = acc.username;
        }
        if(acc == null) acc = new MinecraftAccount();
        acc.xuid = xsts[0];
        acc.clientToken = "0"; /* FIXME */
        acc.accessToken = mcToken;
        acc.username = mcName;
        acc.profileId = mcUuid;
        acc.isMicrosoft = true;
        acc.msaRefreshToken = msRefreshToken;
        acc.expiresAt = expiresAt;
        // ELYMON: the head comes from a third party (mc-heads.net, sent the UUID) and slows the
        // refresh before Play: fetch it on sign-in, after a rename or when missing, not every refresh.
        if (!mIsRefresh || previousName != null || !ElymonAccounts.hasHead(mcName)) acc.updateSkinFace();
        try {
            acc.save();
        } catch (IOException e) {
            // ELYMON: a storage failure, not a network one.
            Log.w(TAG, "Could not save the account: " + e.getClass().getSimpleName());
            throw new MicrosoftAuthFailure(e, Kind.TRANSIENT, R.string.elymon_auth_save_failed);
        }
        ElymonAccounts.forgetOldName(previousName, mcName); // ELYMON
        Log.i(TAG, mIsRefresh ? "Session refreshed" : "Account signed in");
        return acc;
    }

    public String acquireAccessToken(boolean isRefresh, String authcode) throws IOException, JSONException {
        URL url = new URL(authTokenUrl);
        // ELYMON: removed the log of the auth code / refresh token and of the form.
        Log.i(TAG, isRefresh ? "Refreshing the Microsoft token" : "Redeeming the authorization code");

        // ELYMON: the desktop's form (client id, scope, redirect_uri spelled right), plus the PKCE verifier.
        String formData = isRefresh
                ? MicrosoftOAuth.refreshTokenForm(ElymonConfig.AZURE_CLIENT_ID, ElymonConfig.AZURE_REDIRECT_URI, authcode)
                : MicrosoftOAuth.authorizationCodeForm(ElymonConfig.AZURE_CLIENT_ID, ElymonConfig.AZURE_REDIRECT_URI,
                        authcode, mCodeVerifier);

        //да пошла yf[eq1 она ваша джава 11
        HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("charset", "utf-8");
        conn.setRequestProperty("Content-Length", Integer.toString(formData.getBytes(StandardCharsets.UTF_8).length));
        conn.setRequestMethod("POST");
        conn.setUseCaches(false);
        conn.setDoInput(true);
        conn.setDoOutput(true);
        setTimeouts(conn);
        conn.connect();
        try(OutputStream wr = conn.getOutputStream()) {
            wr.write(formData.getBytes(StandardCharsets.UTF_8));
        }
        int status = conn.getResponseCode();
        if(status >= 200 && status < 300) {
            JSONObject jo = new JSONObject(Tools.read(conn.getInputStream()));
            conn.disconnect();
            // ELYMON: Microsoft rotates the refresh token; keep the old one if none came back.
            String refreshToken = jo.optString("refresh_token", "");
            if (refreshToken.isEmpty()) {
                if (!isRefresh) throw new MicrosoftAuthFailure(Kind.REFUSED, R.string.elymon_auth_no_refresh_token);
                refreshToken = authcode;
            }
            msRefreshToken = refreshToken;
            // ELYMON: removed the log of the access token.
            return jo.getString("access_token");
        }
        if (status >= 400 && status < 500 && status != 408 && status != 429) {
            // ELYMON: read the OAuth "error" code only; the body is never logged or shown.
            String error = MicrosoftOAuth.safeErrorCode(readJsonField(conn, "error"));
            conn.disconnect();
            Log.w(TAG, "Token request refused: HTTP " + status + ", " + error);
            if (MicrosoftOAuth.requiresNewSignIn(error)) {
                throw new MicrosoftAuthFailure(Kind.RECONNECT,
                        isRefresh ? R.string.elymon_auth_session_expired : R.string.elymon_auth_code_expired);
            }
            throw new MicrosoftAuthFailure(Kind.REFUSED, R.string.elymon_auth_microsoft_refused, error);
        }
        throw getResponseThrowable(conn, "Microsoft");
    }

    private String acquireXBLToken(String accessToken) throws IOException, JSONException {
        URL url = new URL(xblAuthUrl);

        JSONObject data = new JSONObject();
        JSONObject properties = new JSONObject();
        properties.put("AuthMethod", "RPS");
        properties.put("SiteName", "user.auth.xboxlive.com");
        // ELYMON: a v2 (Entra) token goes in as "d=<token>", as the desktop sends it
        // (helios-core MicrosoftAuth.getXBLToken). The raw token only suits live.com tickets.
        properties.put("RpsTicket", "d=" + accessToken);
        data.put("Properties",properties);
        data.put("RelyingParty", "http://auth.xboxlive.com");
        data.put("TokenType", "JWT");

        String req = data.toString();
        HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        setCommonProperties(conn, req);
        conn.connect();

        try(OutputStream wr = conn.getOutputStream()) {
            wr.write(req.getBytes(StandardCharsets.UTF_8));
        }
        if(conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
            JSONObject jo = new JSONObject(Tools.read(conn.getInputStream()));
            conn.disconnect();
            // ELYMON: removed the log of the Xbox Live token.
            return jo.getString("Token");
            //acquireXsts(jo.getString("Token"));
        }else{
            throw getResponseThrowable(conn, "Xbox Live");
        }
    }

    /** @return [uhs, token]*/
    private @NonNull String[] acquireXsts(String xblToken) throws IOException, JSONException {
        URL url = new URL(xstsAuthUrl);

        JSONObject data = new JSONObject();
        JSONObject properties = new JSONObject();
        properties.put("SandboxId", "RETAIL");
        properties.put("UserTokens", new JSONArray(Collections.singleton(xblToken)));
        data.put("Properties", properties);
        data.put("RelyingParty", "rp://api.minecraftservices.com/");
        data.put("TokenType", "JWT");

        String req = data.toString();
        // ELYMON: removed the log of this request, which holds the Xbox Live token.
        HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        setCommonProperties(conn, req);
        conn.connect();

        try(OutputStream wr = conn.getOutputStream()) {
            wr.write(req.getBytes(StandardCharsets.UTF_8));
        }

        if(conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
            JSONObject jo = new JSONObject(Tools.read(conn.getInputStream()));
            String uhs = jo.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs");
            String token = jo.getString("Token");
            conn.disconnect();
            // ELYMON: removed the log of the XSTS token and user hash.
            return new String[]{uhs, token};
            //acquireMinecraftToken(uhs,jo.getString("Token"));
        }else if(conn.getResponseCode() == 401) {
            // ELYMON: only XErr is read. The body is neither logged nor attached to the error.
            String xerrText = readJsonField(conn, "XErr");
            conn.disconnect();
            long xerr = -1;
            try {
                if (xerrText != null) xerr = Long.parseLong(xerrText);
            } catch (NumberFormatException ignored) {}
            Log.w(TAG, "XSTS refused the account: XErr " + xerr);
            Integer locale_id = XSTS_ERRORS.get(xerr);
            if(locale_id != null) {
                throw new MicrosoftAuthFailure(Kind.REFUSED, locale_id);
            }
            throw new MicrosoftAuthFailure(Kind.REFUSED, R.string.elymon_auth_xerr_unknown, xerr);
        }else{
            throw getResponseThrowable(conn, "Xbox Live (XSTS)");
        }
    }

    private String acquireMinecraftToken(String xblUhs, String xblXsts) throws IOException, JSONException {
        URL url = new URL(mcLoginUrl);

        JSONObject data = new JSONObject();
        data.put("identityToken", "XBL3.0 x=" + xblUhs + ";" + xblXsts);

        String req = data.toString();
        HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        setCommonProperties(conn, req);
        // ELYMON: time taken before the request, so the expiry errs on the early side.
        long requestedAt = System.currentTimeMillis();
        conn.connect();

        try(OutputStream wr = conn.getOutputStream()) {
            wr.write(req.getBytes(StandardCharsets.UTF_8));
        }

        if(conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
            JSONObject jo = new JSONObject(Tools.read(conn.getInputStream()));
            conn.disconnect();
            // ELYMON: removed the log of the Minecraft token. The expiry comes from expires_in
            // (seconds) minus 10 s like the desktop (authmanager.js calculateExpiryDate), and is
            // stored in epoch milliseconds, the unit MinecraftAccount.expiresAt is compared in.
            long expiresIn = jo.optLong("expires_in", DEFAULT_MC_TOKEN_LIFETIME_S);
            if (expiresIn <= 10) expiresIn = DEFAULT_MC_TOKEN_LIFETIME_S;
            expiresAt = requestedAt + (expiresIn - 10) * 1000L;
            mcToken = jo.getString("access_token");
            //checkMcProfile(jo.getString("access_token"));
            return mcToken;
        }else if(conn.getResponseCode() == 403) {
            // ELYMON: what api.minecraftservices.com answers to an app registration it has not approved.
            conn.disconnect();
            Log.w(TAG, "login_with_xbox refused the app registration: HTTP 403");
            throw new MicrosoftAuthFailure(Kind.REFUSED, R.string.elymon_auth_app_not_allowed);
        }else{
            throw getResponseThrowable(conn, "Minecraft");
        }
    }

    private void fetchOwnedItems(String mcAccessToken) throws IOException {
        URL url = new URL(mcStoreUrl);
        String s = "";

        // For some reason, minecraftservices APIs are significantly more unreliable
        // Automatically retry because the user gets annoyed when they have to log in again
        for (int retryCount = 0; retryCount < 5; ++retryCount) {
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + mcAccessToken);
            conn.setRequestProperty("Accept", "application/json");
            conn.setUseCaches(false);
            setTimeouts(conn);
            conn.connect();
            if (conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
                s = Tools.read(conn.getInputStream());
                conn.disconnect();
                break;
            } else if (retryCount == 4 || !isRetryable(conn.getResponseCode())) {
                throw getResponseThrowable(conn, "Minecraft");
            }
            conn.disconnect();
            try { Thread.sleep(500L * (1L << retryCount)); // 0.5s, 1s, 2s, 4s, 8s
            } catch (InterruptedException ignored) {}
        }
        // ELYMON: read the plain "items" names and the names inside the signed JWT. Upstream
        // read the JWT with the standard base64 alphabet and compared whole entries as strings,
        // so it could miss them. Only Java or Game Pass counts: Bedrock alone is not Elymon.
        Set<String> names = new HashSet<>();
        try {
            JSONObject root = new JSONObject(s);
            collectEntitlementNames(root.optJSONArray("items"), names);
            String jwt = root.optString("signature", "");
            String[] jwtParts = jwt.split("\\.");
            if (jwtParts.length >= 2) {
                String payload = new String(Base64.decode(jwtParts[1], Base64.URL_SAFE), StandardCharsets.UTF_8);
                collectEntitlementNames(new JSONObject(payload).optJSONArray("entitlements"), names);
            }
        } catch (JSONException | IllegalArgumentException e) {
            Log.w(TAG, "Unreadable entitlements response");
        }
        doesOwnGame = names.contains("product_minecraft") || names.contains("game_minecraft")
                || names.contains("product_game_pass_pc") || names.contains("product_game_pass_ultimate");
        Log.i(TAG, "Entitlements read: " + names.size() + ", Minecraft Java " + (doesOwnGame ? "owned" : "not owned"));
    }

    // ELYMON: an entitlement entry is either a name or an object with a "name".
    private static void collectEntitlementNames(@Nullable JSONArray array, Set<String> names) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            String name = item != null ? item.optString("name", "") : array.optString(i, "");
            if (!name.isEmpty()) names.add(name);
        }
    }

    private void checkMcProfile(String mcAccessToken) throws IOException, JSONException {
        URL url = new URL(mcProfileUrl);

        // For some reason, minecraftservices APIs are significantly more unreliable
        // Automatically retry because the user gets annoyed when they have to log in again
        for (int retryCount = 0; retryCount < 5; ++retryCount) {
            HttpURLConnection conn = (HttpURLConnection)url.openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + mcAccessToken);
            conn.setRequestProperty("Accept", "application/json");
            conn.setUseCaches(false);
            setTimeouts(conn);
            conn.connect();

            if(conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
                String s= Tools.read(conn.getInputStream());
                conn.disconnect();
                // ELYMON: removed the logs of the profile body, the name and the UUID.
                JSONObject jsonObject = new JSONObject(s);
                String name = jsonObject.getString("name");
                String uuid = jsonObject.getString("id");
                String uuidDashes = uuid.replaceFirst(
                        "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5"
                );
                // ELYMON: ownership is no longer inferred here, see REQUIRE_JAVA_ENTITLEMENT.
                hasProfile = true;
                mcName = name;
                mcUuid = uuidDashes;
                break;
            } else if (conn.getResponseCode() == 404){
                conn.disconnect();
                Log.i(TAG,"It seems that this Microsoft Account does not have a Minecraft profile, checking for ownership.");
                hasProfile = false;
                break;
            } else if (conn.getResponseCode() == 401){
                Log.e(TAG, "The Minecraft token was refused by the profile API");
                throw getResponseThrowable(conn, "Minecraft");
            } else if (retryCount == 4 || !isRetryable(conn.getResponseCode())) {
                throw getResponseThrowable(conn, "Minecraft");
            }
            conn.disconnect();
            try { Thread.sleep(500L * (1L << retryCount)); // 0.5s, 1s, 2s, 4s, 8s
            } catch (InterruptedException ignored) {}
        }
    }

    /** Wrapper to ease notifying the listener */
    // ELYMON: with a French step label, and the progress bar only when the caller asked for it.
    private void notifyProgress(@Nullable ProgressListener listener, boolean showProgress, int step, @StringRes int label){
        if(listener != null){
            Tools.runOnUiThread(() -> listener.onLoginProgress(step));
        }
        if (showProgress) ProgressLayout.setProgress(ProgressLayout.AUTHENTICATE_MICROSOFT, step*20, label);
    }


    /** Set common properties for the connection. Given that all requests are POST, interactivity is always enabled */
    private static void setCommonProperties(HttpURLConnection conn, String formData) {
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("charset", "utf-8");
        try {
            conn.setRequestProperty("Content-Length", Integer.toString(formData.getBytes(StandardCharsets.UTF_8).length));
            conn.setRequestMethod("POST");
        }catch (ProtocolException e) {
            Log.e(TAG, e.toString());
        }
        conn.setUseCaches(false);
        conn.setDoInput(true);
        conn.setDoOutput(true);
        setTimeouts(conn);
    }

    // ELYMON: bounded waits, see CONNECT_TIMEOUT_MS.
    private static void setTimeouts(HttpURLConnection conn) {
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
    }

    // ELYMON: minecraftservices answers worth another try.
    private static boolean isRetryable(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    // ELYMON: one top-level field of a JSON error body, or null. The body itself goes nowhere.
    @Nullable
    private static String readJsonField(HttpURLConnection conn, String field) {
        try {
            InputStream errorStream = conn.getErrorStream();
            if (errorStream == null) return null;
            JSONObject body = new JSONObject(Tools.read(errorStream));
            return body.has(field) ? body.get(field).toString() : null;
        } catch (IOException | JSONException | RuntimeException e) {
            return null;
        }
    }

    // ELYMON: French, typed failures; a transient one (429, 408, 5xx) is told apart from a refusal.
    private RuntimeException getResponseThrowable(HttpURLConnection conn, String service) throws IOException {
        int status = conn.getResponseCode();
        conn.disconnect();
        Log.w(TAG, service + " answered HTTP " + status);
        if(status == 429) {
            return new MicrosoftAuthFailure(Kind.TRANSIENT, R.string.elymon_auth_rate_limited);
        }
        if (status == 408 || status >= 500) {
            return new MicrosoftAuthFailure(Kind.TRANSIENT, R.string.elymon_auth_service_unavailable, service);
        }
        return new MicrosoftAuthFailure(Kind.REFUSED, R.string.elymon_auth_http_refused, service, status);
    }
}
