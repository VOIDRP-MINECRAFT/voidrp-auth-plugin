package ru.voidrp.auth.config;

import java.time.Duration;

import org.bukkit.configuration.file.FileConfiguration;

/** Snapshot of config.yml, re-read on /vauth reload. */
public final class AuthConfig {

    private final String backendUrl;
    private final String secret;
    private final String serverSlug;
    private final Duration requestTimeout;
    private final String siteBaseUrl;
    private final String offerUrl;
    private final String privacyUrl;
    private final String resetPasswordUrl;
    private final int loginTimeoutSeconds;
    private final int sessionMinutes;
    private final boolean preJoinDialog;
    private final boolean ticketByNickname;
    private final boolean ticketFromHostname;
    private final String verifiedPrefix;
    private final String verifiedTabSuffix;

    public AuthConfig(FileConfiguration cfg) {
        this.backendUrl = stripTrailingSlash(cfg.getString("backend.url", "https://api.void-rp.ru"));
        this.secret = cfg.getString("backend.secret", "");
        this.serverSlug = cfg.getString("backend.server-slug", "");
        this.requestTimeout = Duration.ofMillis(Math.max(1000, cfg.getInt("backend.timeout-ms", 15000)));
        this.siteBaseUrl = stripTrailingSlash(cfg.getString("site.base-url", "https://void-rp.ru"));
        this.offerUrl = siteBaseUrl + cfg.getString("site.offer-path", "/offer");
        this.privacyUrl = siteBaseUrl + cfg.getString("site.privacy-path", "/privacy");
        this.resetPasswordUrl = siteBaseUrl + cfg.getString("site.reset-password-path", "/forgot-password");
        this.loginTimeoutSeconds = Math.max(30, cfg.getInt("login.timeout-seconds", 180));
        this.sessionMinutes = Math.max(0, cfg.getInt("login.session-minutes", 30));
        this.preJoinDialog = cfg.getBoolean("login.pre-join-dialog", true);
        this.ticketByNickname = cfg.getBoolean("launcher.ticket-by-nickname", true);
        this.ticketFromHostname = cfg.getBoolean("launcher.ticket-from-hostname", true);
        this.verifiedPrefix = cfg.getString("launcher.verified-prefix", "");
        this.verifiedTabSuffix = cfg.getString("launcher.verified-tab-suffix", "");
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public String backendUrl() {
        return backendUrl;
    }

    public String secret() {
        return secret;
    }

    public String serverSlug() {
        return serverSlug;
    }

    // Values from the admin panel (GET /server/auth/settings, polled every minute) override the
    // file; the file stays the fallback while the backend has not answered.
    private volatile Integer liveLoginSeconds;
    private volatile Integer liveSessionMinutes;
    private volatile Duration liveRequestTimeout;

    /**
     * Applies the admin's login settings. The auth-bridge mod reads the same ones; here
     * {@code auth_grace_seconds} is how long the login window waits (0 — "no limit" there —
     * becomes an hour: a window cannot wait forever), {@code reconnect_grant_minutes} how long
     * a player who left is let back without the password, and the request timeout is capped
     * at a minute so the window never hangs longer.
     */
    public void applyLive(int graceSeconds, int reconnectMinutes, long requestTimeoutMs) {
        this.liveLoginSeconds = graceSeconds <= 0 ? 3600 : Math.max(30, graceSeconds);
        this.liveSessionMinutes = Math.max(0, reconnectMinutes);
        this.liveRequestTimeout = Duration.ofMillis(Math.max(1000, Math.min(60_000, requestTimeoutMs)));
    }

    public Duration requestTimeout() {
        Duration live = liveRequestTimeout;
        return live != null ? live : requestTimeout;
    }

    public String offerUrl() {
        return offerUrl;
    }

    public String privacyUrl() {
        return privacyUrl;
    }

    public String resetPasswordUrl() {
        return resetPasswordUrl;
    }

    public int loginTimeoutSeconds() {
        Integer live = liveLoginSeconds;
        return live != null ? live : loginTimeoutSeconds;
    }

    public int sessionMinutes() {
        Integer live = liveSessionMinutes;
        return live != null ? live : sessionMinutes;
    }

    public boolean preJoinDialog() {
        return preJoinDialog;
    }

    public boolean launcherTicketByNickname() {
        return ticketByNickname;
    }

    public boolean ticketFromHostname() {
        return ticketFromHostname;
    }

    public String verifiedPrefix() {
        return verifiedPrefix;
    }

    public String verifiedTabSuffix() {
        return verifiedTabSuffix;
    }

    public boolean isConfigured() {
        return !backendUrl.isEmpty() && !secret.isEmpty() && !"CHANGE_ME".equals(secret);
    }
}
