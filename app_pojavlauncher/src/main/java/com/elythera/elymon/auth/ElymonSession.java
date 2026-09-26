package com.elythera.elymon.auth;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.authenticator.microsoft.MicrosoftBackgroundLogin;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import java.io.IOException;

/** Session upkeep for the account about to play. */
public final class ElymonSession {
    private ElymonSession() {}

    private static final String TAG = "ElymonSession";

    /**
     * Refresh when the Minecraft token has less than this much time left, or
     * when its expiry is unknown. The token lasts 24 hours (login_with_xbox
     * expires_in). The pack then takes about a minute to boot before it joins.
     */
    public static final long REFRESH_MARGIN_MS = 10L * 60L * 1000L;

    /**
     * When a refresh fails only because Microsoft did not answer, keep the
     * current token if it still has at least this much time left.
     */
    private static final long STILL_USABLE_MS = 2L * 60L * 1000L;

    /**
     * Held for every Microsoft token exchange (a new sign-in or a refresh).
     * The account spinner and the Play button can refresh the same account at
     * the same moment; the second one then finds the account already fresh on
     * disk. Java monitors are reentrant, so MicrosoftBackgroundLogin takes it too.
     */
    public static final Object REFRESH_LOCK = new Object();

    private static volatile Context sAppContext;

    /**
     * Remember the application context, used for the French messages and the
     * account selection. The account spinner calls this as soon as it is
     * created, which is before any Play is possible.
     */
    public static void attach(@Nullable Context context) {
        if (context != null) sAppContext = context.getApplicationContext();
    }

    @Nullable
    static Context appContext() {
        return sAppContext;
    }

    /** Whether the Minecraft token of this account should be refreshed before playing. */
    public static boolean needsRefresh(MinecraftAccount account) {
        return needsRefresh(account, System.currentTimeMillis());
    }

    /** {@link #needsRefresh(MinecraftAccount)} at the given time, in epoch milliseconds like expiresAt. */
    public static boolean needsRefresh(MinecraftAccount account, long nowMs) {
        return account.expiresAt <= 0 || account.expiresAt - nowMs < REFRESH_MARGIN_MS;
    }

    /**
     * Makes sure the Minecraft access token of a Microsoft account is valid for
     * the coming session, refreshing and saving the account if needed. Blocking:
     * call it off the UI thread. Returns the account to launch with.
     *
     * Uses the context given to {@link #attach(Context)}; prefer
     * {@link #ensureFresh(Context, MinecraftAccount)} when a context is at hand.
     */
    public static MinecraftAccount ensureFresh(MinecraftAccount account) throws IOException {
        Context context = sAppContext;
        if (context == null) {
            // Programming error: the launcher activity always creates the account spinner first.
            throw new IOException("ElymonSession.ensureFresh called before ElymonSession.attach");
        }
        return ensureFresh(context, account);
    }

    /**
     * Same as {@link #ensureFresh(MinecraftAccount)}, with the context used for
     * the French messages.
     *
     * The account is re-read from disk under the refresh lock, because the
     * copy in memory can be older than a refresh made by the spinner. Throws
     * an {@link ElymonAuthException}, whose message is French and meant for
     * the player:
     * <ul>
     * <li>{@code RECONNECT}: the refresh token is gone (invalid_grant). The
     * player must add the account again.</li>
     * <li>{@code TRANSIENT}: the services did not answer, and the current token
     * expires within two minutes.</li>
     * <li>{@code REFUSED}: the account can no longer play.</li>
     * </ul>
     */
    public static MinecraftAccount ensureFresh(Context context, MinecraftAccount account) throws IOException {
        attach(context);
        if (account == null) {
            throw new ElymonAuthException(ElymonAuthException.Kind.RECONNECT,
                    context.getString(R.string.elymon_auth_no_account));
        }
        if (!account.isMicrosoft || account.isDemo()) {
            throw new ElymonAuthException(ElymonAuthException.Kind.RECONNECT,
                    context.getString(R.string.elymon_auth_not_microsoft));
        }

        synchronized (REFRESH_LOCK) {
            MinecraftAccount current = MinecraftAccount.load(account.username);
            // A refresh made meanwhile may have moved the account to a new Minecraft name.
            if (current == null) current = ElymonAccounts.findByProfileId(account.profileId);
            if (current == null || !current.isMicrosoft) current = account;

            long now = System.currentTimeMillis();
            if (!needsRefresh(current, now)) return current;

            if (!MicrosoftBackgroundLogin.hasRefreshToken(current)) {
                throw new ElymonAuthException(ElymonAuthException.Kind.RECONNECT,
                        context.getString(R.string.elymon_auth_session_expired));
            }

            Log.i(TAG, "Refreshing the Microsoft session before launch");
            try {
                return MicrosoftBackgroundLogin.refreshing(current).loginBlocking(null, true);
            } catch (MicrosoftAuthFailure failure) {
                if (failure.getKind() == ElymonAuthException.Kind.TRANSIENT
                        && current.expiresAt - now > STILL_USABLE_MS) {
                    Log.w(TAG, "Session refresh unanswered, the current token is still valid for a while");
                    return current;
                }
                Log.w(TAG, "Session refresh failed: " + failure.getKind());
                throw new ElymonAuthException(failure.getKind(), failure.toString(context), failure.getCause());
            }
        }
    }
}
