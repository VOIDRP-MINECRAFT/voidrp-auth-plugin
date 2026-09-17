package ru.voidrp.auth.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is past the login window right now, and who may skip it for a while.
 *
 * <p>A short-lived session keyed by nickname + address means a player who is dropped by
 * a restart or a crash walks straight back in instead of typing the password again. It
 * is intentionally not persisted: a server restart is exactly when we want to ask again.
 */
public final class AuthSessions {

    private final Map<UUID, Boolean> authenticated = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> verifiedClient = new ConcurrentHashMap<>();
    private final Map<String, Instant> recentLogins = new ConcurrentHashMap<>();

    public void markAuthenticated(UUID playerId, String nickname, String ip, boolean fromLauncher, int sessionMinutes) {
        authenticated.put(playerId, Boolean.TRUE);
        verifiedClient.put(playerId, fromLauncher);
        if (sessionMinutes > 0) {
            recentLogins.put(sessionKey(nickname, ip), Instant.now().plus(Duration.ofMinutes(sessionMinutes)));
        }
    }

    public boolean isAuthenticated(UUID playerId) {
        return authenticated.getOrDefault(playerId, Boolean.FALSE);
    }

    public boolean isVerifiedClient(UUID playerId) {
        return verifiedClient.getOrDefault(playerId, Boolean.FALSE);
    }

    public boolean hasRecentSession(String nickname, String ip) {
        Instant until = recentLogins.get(sessionKey(nickname, ip));
        if (until == null) {
            return false;
        }
        if (until.isBefore(Instant.now())) {
            recentLogins.remove(sessionKey(nickname, ip));
            return false;
        }
        return true;
    }

    public void clearSession(String nickname, String ip) {
        recentLogins.remove(sessionKey(nickname, ip));
    }

    public void forget(UUID playerId) {
        authenticated.remove(playerId);
        verifiedClient.remove(playerId);
    }

    private static String sessionKey(String nickname, String ip) {
        return (nickname == null ? "" : nickname.toLowerCase()) + "|" + (ip == null ? "" : ip);
    }
}
