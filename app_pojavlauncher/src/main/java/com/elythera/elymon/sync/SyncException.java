package com.elythera.elymon.sync;

/** A sync failure with a French message meant for the player. */
public class SyncException extends Exception {
    public final boolean cancelled;

    public SyncException(String userMessage, Throwable cause) {
        this(userMessage, cause, false);
    }

    public SyncException(String userMessage, Throwable cause, boolean cancelled) {
        super(userMessage, cause);
        this.cancelled = cancelled;
    }
}
