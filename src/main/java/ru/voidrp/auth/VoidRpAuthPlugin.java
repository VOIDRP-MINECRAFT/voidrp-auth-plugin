package ru.voidrp.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import ru.voidrp.auth.backend.BackendAuthClient;
import ru.voidrp.auth.command.AuthCommands;
import ru.voidrp.auth.config.AuthConfig;
import ru.voidrp.auth.dialog.AuthDialogs;
import ru.voidrp.auth.listener.LimboListener;
import ru.voidrp.auth.listener.PreJoinListener;
import ru.voidrp.auth.session.AuthSessions;
import ru.voidrp.auth.session.PendingAuth;
import ru.voidrp.auth.skin.SkinService;

/**
 * Login and registration against the VoidRP site account, shown as native Minecraft
 * windows.
 *
 * <p>Players who arrive through our launcher carry a play ticket in the address they
 * connect to and never see a window; everyone else answers the same dialogs the site
 * form would ask for, and ends up with the very same account.
 */
public final class VoidRpAuthPlugin extends JavaPlugin {

    /** 1.21.6 is the first protocol that can draw server dialogs. */
    private static final int MIN_DIALOG_PROTOCOL = 771;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private AuthConfig config;
    private AuthDialogs dialogs;
    private BackendAuthClient backend;
    private final AuthSessions sessions = new AuthSessions();
    private final Map<String, PendingAuth> pending = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadPluginConfig();
        this.backend = new BackendAuthClient(this::config, getLogger());

        getServer().getPluginManager().registerEvents(new PreJoinListener(this), this);
        getServer().getPluginManager().registerEvents(new LimboListener(this), this);
        SkinService skins = new SkinService(this);
        getServer().getPluginManager().registerEvents(skins, this);
        if (getCommand("skin") != null) {
            getCommand("skin").setExecutor(skins);
        }

        AuthCommands commands = new AuthCommands(this);
        for (String name : new String[] {"login", "register", "vauth"}) {
            if (getCommand(name) != null) {
                getCommand(name).setExecutor(commands);
                getCommand(name).setTabCompleter(commands);
            }
        }

        if (!config.isConfigured()) {
            getLogger().severe("backend.secret не задан в config.yml — вход не будет проверяться!");
        }
        getLogger().info("VoidRpAuth включён. Окна входа: " + (config.preJoinDialog() ? "до входа в мир" : "выключены"));
        restoreReloadHandoff();
        startHeartbeat();
    }

    /**
     * A reload (PlugMan, /reload) wipes the in-memory sessions while players stay online, and
     * everyone would be locked until they typed the password again. On disable the players who
     * were in are written down; an enable within two minutes lets exactly them back in. After
     * a real restart nobody is online at enable, so the note is just dropped.
     */
    private java.io.File handoffFile() {
        return new java.io.File(getDataFolder(), "reload-handoff.txt");
    }

    private void writeReloadHandoff() {
        StringBuilder out = new StringBuilder();
        for (Player player : getServer().getOnlinePlayers()) {
            if (sessions.isAuthenticated(player.getUniqueId())) {
                out.append(player.getUniqueId()).append('|').append(sessions.isVerifiedClient(player.getUniqueId())).append('\n');
            }
        }
        try {
            java.nio.file.Files.writeString(handoffFile().toPath(), out.toString());
        } catch (java.io.IOException exc) {
            getLogger().warning("Не удалось записать сессии перед перезагрузкой: " + exc.getMessage());
        }
    }

    private void restoreReloadHandoff() {
        java.io.File file = handoffFile();
        if (!file.isFile()) {
            return;
        }
        try {
            boolean fresh = System.currentTimeMillis() - file.lastModified() < 120_000L;
            int restored = 0;
            if (fresh) {
                for (String line : java.nio.file.Files.readAllLines(file.toPath())) {
                    String[] parts = line.split("\\|");
                    if (parts.length < 2) continue;
                    Player player = getServer().getPlayer(java.util.UUID.fromString(parts[0]));
                    if (player == null) continue;
                    boolean fromLauncher = Boolean.parseBoolean(parts[1]);
                    sessions.markAuthenticated(player.getUniqueId(), player.getName(), null, fromLauncher, 0);
                    if (fromLauncher) applyVerifiedMark(player);
                    restored++;
                }
            }
            if (restored > 0) getLogger().info("После перезагрузки возвращён вход " + restored + " игрокам.");
        } catch (Exception exc) {
            getLogger().warning("Не удалось вернуть сессии после перезагрузки: " + exc.getMessage());
        } finally {
            file.delete();
        }
    }

    /**
     * Every 30 s: the admin panel counts the login module as working (required on partner
     * servers). Every 60 s: the login settings from the admin («Авторизация»).
     */
    private void startHeartbeat() {
        Runnable beat = () -> {
            if (config.isConfigured()) {
                backend.heartbeat(getDescription().getVersion(), getServer().getName() + " " + getServer().getVersion(), true);
            }
        };
        if (FOLIA) {
            getServer().getAsyncScheduler().runAtFixedRate(this, t -> beat.run(), 10, 30, java.util.concurrent.TimeUnit.SECONDS);
            getServer().getAsyncScheduler().runAtFixedRate(this, t -> pullSettings(), 1, 60, java.util.concurrent.TimeUnit.SECONDS);
        } else {
            getServer().getScheduler().runTaskTimerAsynchronously(this, beat, 20L * 10, 20L * 30);
            getServer().getScheduler().runTaskTimerAsynchronously(this, this::pullSettings, 20L, 20L * 60);
        }
    }

    private volatile String lastSettings;

    /** Takes «Авторизация» from the admin panel; keeps the current values when it does not answer. */
    public void pullSettings() {
        if (!config.isConfigured()) {
            return;
        }
        com.google.gson.JsonObject s = backend.authSettings();
        if (s == null || !s.has("auth_grace_seconds")) {
            return;
        }
        int grace = s.get("auth_grace_seconds").getAsInt();
        int reconnect = s.has("reconnect_grant_minutes") ? s.get("reconnect_grant_minutes").getAsInt() : config.sessionMinutes();
        long timeout = s.has("request_timeout_ms") ? s.get("request_timeout_ms").getAsLong() : config.requestTimeout().toMillis();
        config.applyLive(grace, reconnect, timeout);
        String now = "окно входа " + config.loginTimeoutSeconds() + " с, без пароля после выхода " + config.sessionMinutes()
                + " мин, ожидание сайта " + config.requestTimeout().toMillis() + " мс";
        if (!now.equals(lastSettings)) {
            getLogger().info("Настройки входа из админки: " + now);
            lastSettings = now;
        }
    }

    @Override
    public void onDisable() {
        writeReloadHandoff();
        pending.clear();
    }

    public void reloadPluginConfig() {
        reloadConfig();
        this.config = new AuthConfig(getConfig());
        this.dialogs = new AuthDialogs(config);
    }

    public AuthConfig config() {
        return config;
    }

    public AuthDialogs dialogs() {
        return dialogs;
    }

    public BackendAuthClient backend() {
        return backend;
    }

    public AuthSessions sessions() {
        return sessions;
    }

    public Map<String, PendingAuth> pending() {
        return pending;
    }

    /**
     * Can this client draw a dialog? ViaVersion knows the real protocol of a player
     * joining through it; without ViaVersion everyone is assumed modern, since the
     * server itself only accepts 26.2 clients then.
     */
    /** Channel our client mod (VoidRP Client Info ≥ 1.1.0) announces: it draws passwords as stars. */
    public static final String PASSWORD_MASK_CHANNEL = "voidrp_client_info:password_mask";

    /**
     * Whether this client hides the password in the login window. A NeoForge client
     * announces its channels a moment into the configuration phase, so a modded client
     * (or one with no brand yet) is given up to 1.5 s; a vanilla one is answered at once.
     */
    public boolean passwordMasked(PlayerConfigurationConnection connection) {
        long deadline = System.currentTimeMillis() + 1500;
        while (true) {
            if (connection.getListeningPluginChannels().contains(PASSWORD_MASK_CHANNEL)) {
                return true;
            }
            String brand = connection.getClientBrandName();
            boolean modded = brand == null || brand.toLowerCase(java.util.Locale.ROOT).contains("forge");
            if (!modded || System.currentTimeMillis() > deadline || !connection.isConnected()) {
                return false;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exc) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    public boolean dialogsSupported(PlayerConfigurationConnection connection) {
        if (!config.preJoinDialog()) {
            return false;
        }
        Integer protocol = viaProtocolOf(connection);
        return protocol == null || protocol >= MIN_DIALOG_PROTOCOL;
    }

    private Integer viaProtocolOf(PlayerConfigurationConnection connection) {
        if (getServer().getPluginManager().getPlugin("ViaVersion") == null) {
            return null;
        }
        try {
            java.util.UUID uuid = connection.getProfile().getId();
            if (uuid == null) {
                return null;
            }
            Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
            Object api = via.getMethod("getAPI").invoke(null);
            Object version = api.getClass().getMethod("getPlayerVersion", java.util.UUID.class).invoke(api, uuid);
            return version instanceof Integer value ? value : null;
        } catch (Exception exc) {
            getLogger().fine("Не удалось спросить ViaVersion о версии клиента: " + exc.getMessage());
            return null;
        }
    }

    /** Marks a player as logged in and, for launcher players, tags their name. */
    public void completeLogin(Player player, boolean fromLauncher) {
        sessions.markAuthenticated(
                player.getUniqueId(),
                player.getName(),
                player.getAddress() == null || player.getAddress().getAddress() == null
                        ? null
                        : player.getAddress().getAddress().getHostAddress(),
                fromLauncher,
                config.sessionMinutes());
        if (fromLauncher) {
            applyVerifiedMark(player);
        }
        player.sendMessage(Component.text("Вы вошли. Приятной игры!", NamedTextColor.GREEN));
    }

    /** The "verified client" tag for players who came through our launcher. */
    public void applyVerifiedMark(Player player) {
        String prefix = config.verifiedPrefix();
        String suffix = config.verifiedTabSuffix();
        if (prefix != null && !prefix.isBlank()) {
            player.displayName(LEGACY.deserialize(prefix).append(Component.text(player.getName())));
        }
        if (suffix != null && !suffix.isBlank()) {
            player.playerListName(Component.text(player.getName()).append(LEGACY.deserialize(suffix)));
        }
    }

    /** Kicks a player who never logged in after the grace period, for the chat fallback. */
    public void startLoginTimeout(Player player) {
        Runnable check = () -> {
            if (player.isOnline() && !sessions.isAuthenticated(player.getUniqueId())) {
                player.kick(Component.text("Вы не вошли вовремя. Зайдите заново.", NamedTextColor.RED));
            }
        };
        long ticks = Math.max(1L, config.loginTimeoutSeconds() * 20L);
        if (FOLIA) {
            player.getScheduler().runDelayed(this, t -> check.run(), null, ticks);
        } else {
            getServer().getScheduler().runTaskLater(this, check, ticks);
        }
    }

    // Folia (partner servers) has no main thread and refuses the Bukkit scheduler: there a
    // player's work runs on the player's own scheduler and background work on the async one.
    static final boolean FOLIA = classExists("io.papermc.paper.threadedregions.RegionizedServer");

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException exc) {
            return false;
        }
    }

    public void runAsync(Runnable task) {
        if (FOLIA) {
            getServer().getAsyncScheduler().runNow(this, t -> task.run());
        } else {
            getServer().getScheduler().runTaskAsynchronously(this, task);
        }
    }

    public void runAsyncLater(Runnable task, long seconds) {
        if (FOLIA) {
            getServer().getAsyncScheduler().runDelayed(this, t -> task.run(), seconds, java.util.concurrent.TimeUnit.SECONDS);
        } else {
            getServer().getScheduler().runTaskLaterAsynchronously(this, task, 20L * seconds);
        }
    }

    /** Runs on the thread that owns the player (the main thread, or its region on Folia). */
    public void runFor(Player player, Runnable task) {
        if (FOLIA) {
            player.getScheduler().run(this, t -> task.run(), null);
        } else {
            getServer().getScheduler().runTask(this, task);
        }
    }
}
