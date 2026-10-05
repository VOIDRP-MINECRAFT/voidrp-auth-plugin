package ru.voidrp.auth.skin;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import ru.voidrp.auth.VoidRpAuthPlugin;

/**
 * The skin from the player's VoidRP account, shown to everyone on an offline-mode server.
 *
 * <p>The backend hands a Mojang-signed {@code textures} property (the account's skin signed
 * through MineSkin, or the Mojang skin of the nickname). It goes into the game profile before
 * the player joins, so every client sees it — our launcher, a plain client, an old version
 * through ViaVersion, Bedrock through Geyser — with no client mod.
 *
 * <p>When the backend is still signing a fresh skin, the player joins with a plain one and the
 * skin is put on a little later, live. {@code /skin} does the same on demand, after a change
 * in the launcher or on the site.
 */
public final class SkinService implements Listener, CommandExecutor {

    /** After a join without a skin: ask again this many seconds after joining. */
    private static final long[] RETRY_SECONDS = {5, 20, 60};
    private static final Duration LIVE_TIMEOUT = Duration.ofSeconds(10);
    private static final long COMMAND_COOLDOWN_MS = 30_000;

    private final VoidRpAuthPlugin plugin;
    /** Players who joined without their skin, by name (lower case): retry after the join. */
    private final Map<String, Boolean> missing = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCommand = new ConcurrentHashMap<>();

    public SkinService(VoidRpAuthPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean active() {
        if (!plugin.config().skinsEnabled() || !plugin.config().isConfigured()) {
            return false;
        }
        // Two plugins setting skins would fight over the profile.
        return !plugin.getServer().getPluginManager().isPluginEnabled("SkinsRestorer");
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED || !active()) {
            return;
        }
        String name = event.getName();
        ProfileProperty textures = fetch(name, plugin.config().skinPreLoginWait());
        if (textures == null) {
            missing.put(name.toLowerCase(java.util.Locale.ROOT), Boolean.TRUE);
            return;
        }
        PlayerProfile profile = event.getPlayerProfile();
        profile.removeProperty("textures");
        profile.setProperty(textures);
        event.setPlayerProfile(profile);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (missing.remove(player.getName().toLowerCase(java.util.Locale.ROOT)) == null || !active()) {
            return;
        }
        retry(player, 0);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastCommand.remove(event.getPlayer().getUniqueId());
    }

    /** Ask the backend again a few times after the join; put the skin on once it is ready. */
    private void retry(Player player, int attempt) {
        if (attempt >= RETRY_SECONDS.length) {
            return;
        }
        plugin.runAsyncLater(() -> {
            if (!player.isOnline()) {
                return;
            }
            ProfileProperty textures = fetch(player.getName(), LIVE_TIMEOUT);
            if (textures == null) {
                retry(player, attempt + 1);
                return;
            }
            plugin.runFor(player, () -> apply(player, textures));
        }, RETRY_SECONDS[attempt]);
    }

    /** {@code /skin}: take the current skin from the account without rejoining. */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Только для игроков.");
            return true;
        }
        if (!active()) {
            player.sendMessage(Component.text("Скины сейчас выключены на сервере.", NamedTextColor.RED));
            return true;
        }
        long now = System.currentTimeMillis();
        Long last = lastCommand.get(player.getUniqueId());
        if (last != null && now - last < COMMAND_COOLDOWN_MS) {
            long left = (COMMAND_COOLDOWN_MS - (now - last) + 999) / 1000;
            player.sendMessage(Component.text("Подождите " + left + " с.", NamedTextColor.YELLOW));
            return true;
        }
        lastCommand.put(player.getUniqueId(), now);
        player.sendMessage(Component.text("Обновляю скин…", NamedTextColor.GRAY));
        plugin.runAsync(() -> {
            ProfileProperty textures = fetch(player.getName(), LIVE_TIMEOUT);
            plugin.runFor(player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (textures == null) {
                    player.sendMessage(Component.text(
                            "Скин ещё готовится — попробуйте через минуту. Поменять его можно в лаунчере или на сайте.",
                            NamedTextColor.YELLOW));
                    return;
                }
                if (current(player) != null && textures.getValue().equals(current(player))) {
                    player.sendMessage(Component.text("У вас уже актуальный скин.", NamedTextColor.GREEN));
                    return;
                }
                apply(player, textures);
                player.sendMessage(Component.text("Скин обновлён.", NamedTextColor.GREEN));
            });
        });
        return true;
    }

    private static String current(Player player) {
        for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
            if ("textures".equals(property.getName())) {
                return property.getValue();
            }
        }
        return null;
    }

    /** Puts the skin on a player in the world; the server re-sends them to everyone. */
    private void apply(Player player, ProfileProperty textures) {
        if (!player.isOnline()) {
            return;
        }
        PlayerProfile profile = player.getPlayerProfile();
        profile.removeProperty("textures");
        profile.setProperty(textures);
        player.setPlayerProfile(profile);
    }

    private ProfileProperty fetch(String name, Duration timeout) {
        JsonObject json = plugin.backend().playerSkin(name, timeout);
        if (json == null || !json.has("textures_value") || json.get("textures_value").isJsonNull()
                || !json.has("textures_signature") || json.get("textures_signature").isJsonNull()) {
            return null;
        }
        return new ProfileProperty("textures",
                json.get("textures_value").getAsString(),
                json.get("textures_signature").getAsString());
    }
}
