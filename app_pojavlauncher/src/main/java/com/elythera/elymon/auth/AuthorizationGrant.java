package com.elythera.elymon.auth;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What the Microsoft sign-in page hands to the background login: the
 * authorization code and the PKCE verifier that goes with it.
 *
 * It travels on the ExtraCore bus (key MICROSOFT_LOGIN_TODO) from
 * MicrosoftLoginFragment to mcAccountSpinner. The spinner removes it from
 * the bus as soon as it reads it. {@link #toString()} never shows either value.
 */
public final class AuthorizationGrant {
    public final String code;
    public final String codeVerifier;
    private final AtomicBoolean mClaimed = new AtomicBoolean(false);

    public AuthorizationGrant(String code, String codeVerifier) {
        this.code = code;
        this.codeVerifier = codeVerifier;
    }

    /** True for the first caller only: a code can be redeemed once. */
    public boolean claim() {
        return mClaimed.compareAndSet(false, true);
    }

    @Override
    public String toString() {
        return "AuthorizationGrant[<redacted>]";
    }
}
