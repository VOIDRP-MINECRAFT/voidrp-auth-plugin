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
    }

    @Override
    public void onDisable() {
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
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && !sessions.isAuthenticated(player.getUniqueId())) {
                player.kick(Component.text("Вы не вошли вовремя. Зайдите заново.", NamedTextColor.RED));
            }
        }, config.loginTimeoutSeconds() * 20L);
    }

    public void runAsync(Runnable task) {
        getServer().getScheduler().runTaskAsynchronously(this, task);
    }

    public void runSync(Runnable task) {
        getServer().getScheduler().runTask(this, task);
    }
}
