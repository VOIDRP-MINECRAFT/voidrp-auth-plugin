package ru.voidrp.auth.session;

/**
 * How a connection got through the configuration phase, carried over to the join event
 * where the Player object finally exists.
 */
public record PendingAuth(boolean authenticated, boolean fromLauncher) {

    public static PendingAuth launcher() {
        return new PendingAuth(true, true);
    }

    public static PendingAuth password() {
        return new PendingAuth(true, false);
    }

    public static PendingAuth session() {
        return new PendingAuth(true, false);
    }

    /** Old client: it has to log in with commands once it is in the world. */
    public static PendingAuth chatFallback() {
        return new PendingAuth(false, false);
    }
}
