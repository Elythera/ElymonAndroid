package com.elythera.elymon.auth;

import androidx.annotation.StringRes;

import net.kdt.pojavlaunch.authenticator.microsoft.PresentedException;

/**
 * A sign-in or refresh failure with a French message from
 * elymon_auth_strings.xml.
 *
 * It is a PresentedException, so the account spinner shows it the way
 * upstream shows its own errors. It is also typed, so the spinner can offer
 * to reconnect, and ElymonSession can turn it into an ElymonAuthException.
 *
 * The cause, when there is one, is a network exception. Its message names
 * a host, never a secret. A response body is never attached, because it can
 * hold tokens.
 */
public class MicrosoftAuthFailure extends PresentedException {
    private final ElymonAuthException.Kind mKind;

    public MicrosoftAuthFailure(ElymonAuthException.Kind kind, @StringRes int messageId, Object... args) {
        super(messageId, args);
        mKind = kind;
    }

    public MicrosoftAuthFailure(Throwable cause, ElymonAuthException.Kind kind, @StringRes int messageId, Object... args) {
        super(cause, messageId, args);
        mKind = kind;
    }

    public ElymonAuthException.Kind getKind() {
        return mKind;
    }
}
