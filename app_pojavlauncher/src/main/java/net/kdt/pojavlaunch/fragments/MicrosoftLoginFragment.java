package net.kdt.pojavlaunch.fragments;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.elythera.elymon.ElymonConfig;
import com.elythera.elymon.auth.AuthorizationGrant;
import com.elythera.elymon.auth.ElymonSession;
import com.elythera.elymon.auth.MicrosoftOAuth;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;

import java.security.SecureRandom;

// ELYMON: signs in through Elythera's Entra app (v2 /consumers authorize, PKCE S256, state)
// instead of login.live.com with Microsoft's own launcher client id. The WebView catches the
// nativeclient redirect, as the desktop launcher does (ElytheraLauncher index.js), and never
// logs the URL that carries the code.
public class MicrosoftLoginFragment extends Fragment {
    public static final String TAG = "MICROSOFT_LOGIN_FRAGMENT";
    // ELYMON: PKCE verifier and state of the running sign-in, saved with the WebView state
    // so that a sign-in survives the process being killed in the background.
    private static final String STATE_CODE_VERIFIER = "elymon_code_verifier";
    private static final String STATE_OAUTH_STATE = "elymon_oauth_state";
    private WebView mWebview;
    // Technically the client is blank (or there is none) when the fragment is initialized
    private boolean mBlankClient = true;
    // ELYMON: the running sign-in; mRedirectHandled stops a second callback from handling the same redirect.
    private String mCodeVerifier;
    private String mOAuthState;
    private boolean mRedirectHandled;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ElymonSession.attach(inflater.getContext()); // ELYMON
        mWebview = (WebView) inflater.inflate(R.layout.fragment_microsoft_login, container, false);
        setWebViewSettings();
        if(savedInstanceState == null) startNewSession();
        else restoreWebViewState(savedInstanceState);
        return mWebview;
    }

    // WebView.restoreState() does not restore the WebSettings or the client, so set them there
    // separately. Note that general state should not be altered here (aka no loading pages, no manipulating back/front lists),
    // to avoid "undesirable side-effects"
    @SuppressLint("SetJavaScriptEnabled")
    private void setWebViewSettings() {
        WebSettings settings = mWebview.getSettings();
        settings.setJavaScriptEnabled(true);
        // ELYMON: the Entra sign-in pages keep their state in web storage (MSAL's own WebView
        // enables it too); it is wiped at the start of every sign-in. No local file is ever needed.
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        mWebview.setWebViewClient(new WebViewTrackClient());
        // ELYMON: keep the sign-in pages' console output out of logcat.
        mWebview.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                return true;
            }
        });
        mBlankClient = false;
    }

    private void startNewSession() {
        // ELYMON: a fresh PKCE pair and state for every sign-in.
        SecureRandom random = new SecureRandom();
        mCodeVerifier = MicrosoftOAuth.newCodeVerifier(random);
        mOAuthState = MicrosoftOAuth.newState(random);
        mRedirectHandled = false;
        final String authorizeUrl = MicrosoftOAuth.authorizeUrl(ElymonConfig.AZURE_CLIENT_ID,
                ElymonConfig.AZURE_REDIRECT_URI, MicrosoftOAuth.codeChallengeS256(mCodeVerifier), mOAuthState);
        // ELYMON: web storage too, so the account picker never remembers the previous account.
        WebStorage.getInstance().deleteAllData();
        CookieManager.getInstance().removeAllCookies((b)->{
            mWebview.clearHistory();
            mWebview.clearCache(true);
            mWebview.clearFormData();
            mWebview.clearHistory();
            mWebview.loadUrl(authorizeUrl);
        });
    }

    private void restoreWebViewState(Bundle savedInstanceState) {
        Log.i("MSAuthFragment","Restoring state...");
        // ELYMON: the sign-in can only finish with the verifier and state it started with.
        mCodeVerifier = savedInstanceState.getString(STATE_CODE_VERIFIER);
        mOAuthState = savedInstanceState.getString(STATE_OAUTH_STATE);
        mRedirectHandled = false;
        if(mCodeVerifier == null || mOAuthState == null || mWebview.restoreState(savedInstanceState) == null) {
            Log.w("MSAuthFragment", "Failed to restore state, starting afresh");
            // if, for some reason, we failed to restore our session,
            // just start afresh
            startNewSession();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        // If we have switched to a blank client and haven't fully gone though the lifecycle callbacks to restore it,
        // restore it here.
        if(mBlankClient) mWebview.setWebViewClient(new WebViewTrackClient());
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        // Since the value cannot be null, just create a "blank" client. This is done to not let Android
        // kill us if something happens after the state gets saved, when we can't do fragment transitions
        mWebview.setWebViewClient(new WebViewClient());
        // For some dumb reason state is saved even when Android won't actually destroy the activity.
        // Let the fragment know that the client is blank so that we can restore it in onStart()
        // (it was the earliest lifecycle call actually invoked in this case)
        mBlankClient = true;
        super.onSaveInstanceState(outState);
        mWebview.saveState(outState);
        // ELYMON: see STATE_CODE_VERIFIER.
        outState.putString(STATE_CODE_VERIFIER, mCodeVerifier);
        outState.putString(STATE_OAUTH_STATE, mOAuthState);
    }

    /* Expose webview actions to others */
    public boolean canGoBack(){ return mWebview.canGoBack();}
    public void goBack(){ mWebview.goBack();}

    /**
     * ELYMON: handles the nativeclient redirect, whichever callback sees it first. It stops
     * the load, so the redirect page is never shown, then passes the code and its verifier
     * to the background login, or shows why Microsoft stopped. The URL itself is never
     * logged. Returns false for any other URL.
     */
    private boolean captureRedirect(WebView view, String url) {
        if(!MicrosoftOAuth.isRedirect(url, ElymonConfig.AZURE_REDIRECT_URI)) return false;
        view.stopLoading();
        if(mRedirectHandled) return true;
        mRedirectHandled = true;

        FragmentActivity activity = getActivity();
        if(activity == null) return true;
        // The session cookies are no longer needed; the next sign-in starts clean anyway.
        CookieManager.getInstance().removeAllCookies(null);

        MicrosoftOAuth.Redirect redirect = MicrosoftOAuth.parseRedirect(url);
        if(redirect.error != null) {
            String error = MicrosoftOAuth.safeErrorCode(redirect.error);
            Log.w("MSAuthFragment", "Sign-in ended by Microsoft: " + error);
            if("access_denied".equals(redirect.error)) {
                // The player cancelled or declined the permissions.
                Toast.makeText(activity, R.string.elymon_auth_login_cancelled, Toast.LENGTH_SHORT).show();
            } else {
                String description = redirect.errorDescription != null ? redirect.errorDescription : "";
                Tools.dialog(activity, getString(R.string.elymon_auth_error_title),
                        getString(R.string.elymon_auth_redirect_error, error, description));
            }
            Tools.backToMainMenu(activity);
            return true;
        }
        if(redirect.code == null || mOAuthState == null || !mOAuthState.equals(redirect.state)) {
            Log.w("MSAuthFragment", "Sign-in redirect rejected: " + (redirect.code == null ? "no code" : "state mismatch"));
            Tools.dialog(activity, getString(R.string.elymon_auth_error_title),
                    getString(R.string.elymon_auth_redirect_invalid));
            Tools.backToMainMenu(activity);
            return true;
        }

        Log.i("MSAuthFragment", "Authorization code received");
        // Should be captured by the account spinner, which starts the background login
        ExtraCore.setValue(ExtraConstants.MICROSOFT_LOGIN_TODO, new AuthorizationGrant(redirect.code, mCodeVerifier));
        // Listeners ran inside setValue; the code must not stay on the bus.
        ExtraCore.removeValue(ExtraConstants.MICROSOFT_LOGIN_TODO);
        Toast.makeText(activity, R.string.elymon_auth_login_started, Toast.LENGTH_SHORT).show();
        Tools.backToMainMenu(activity);
        return true;
    }

    /** Client to track when to sent the data to the launcher */
    class WebViewTrackClient extends WebViewClient {

        // ELYMON: the API 24+ callback, which also sees server-side redirects.
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if(captureRedirect(view, request.getUrl().toString())) return true;
            return super.shouldOverrideUrlLoading(view, request);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            // ELYMON: the nativeclient redirect replaces the ms-xal scheme and the res=cancel test
            // of login.live.com; a cancel now comes back as error=access_denied.
            if(captureRedirect(view, url)) return true;
            return super.shouldOverrideUrlLoading(view, url);
        }

        // ELYMON: fallback for a redirect the WebView does not offer to override
        // (a navigation that follows a POST).
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            captureRedirect(view, url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {}
    }


}
