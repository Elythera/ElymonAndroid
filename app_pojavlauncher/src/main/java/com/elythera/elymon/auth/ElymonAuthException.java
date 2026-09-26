package com.elythera.elymon.auth;

import java.io.IOException;

/**
 * Why the Microsoft session of an account could not be made ready.
 * {@link #getMessage()} is a French sentence meant for the player, and it
 * never contains a code or a token.
 */
public class ElymonAuthException extends IOException {
    /** What the player can do about it. */
    public enum Kind {
        /** The stored session is gone (refresh token expired or revoked): sign in again. */
        RECONNECT,
        /** Microsoft, Xbox Live or Minecraft did not answer, or answered 429 or 5xx: try again later. */
        TRANSIENT,
        /** The account itself is refused (no Minecraft Java, Xbox restriction, …): another account is needed. */
        REFUSED
    }

    private final Kind mKind;

    public ElymonAuthException(Kind kind, String message) {
        super(message);
        mKind = kind;
    }

    public ElymonAuthException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        mKind = kind;
    }

    public Kind getKind() {
        return mKind;
    }

    /** Whether signing in again with the Microsoft account would fix it. */
    public boolean needsReconnect() {
        return mKind == Kind.RECONNECT;
    }
}
