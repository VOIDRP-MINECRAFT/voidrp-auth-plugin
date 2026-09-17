package ru.voidrp.auth.listener;

import java.util.Locale;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import ru.voidrp.auth.VoidRpAuthPlugin;
import ru.voidrp.auth.session.PendingAuth;

/**
 * Everything that happens after the connection is handed a Player object.
 *
 * <p>For a 1.21.6+ client this is mostly bookkeeping — the window was already answered
 * before the world existed. For an older client this is the actual gate: it is frozen,
 * silenced and untouchable until /login or /register goes through.
 */
public final class LimboListener implements Listener {

    private static final String[] ALLOWED_COMMANDS = {"/login", "/register", "/l", "/reg"};

    private final VoidRpAuthPlugin plugin;

    public LimboListener(VoidRpAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PendingAuth pending = plugin.pending().remove(player.getName());

        if (pending != null && pending.authenticated()) {
            plugin.sessions().markAuthenticated(
                    player.getUniqueId(),
                    player.getName(),
                    ip(player),
                    pending.fromLauncher(),
                    plugin.config().sessionMinutes());
            if (pending.fromLauncher()) {
                plugin.applyVerifiedMark(player);
                player.sendMessage(Component.text("Вход выполнен через лаунчер VoidRP.", NamedTextColor.AQUA));
            }
            return;
        }

        // No window was shown (old client, or the plugin started mid-session): freeze.
        player.sendMessage(Component.text("Войдите: /login <пароль>", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("Нет аккаунта? /register <почта> <пароль>", NamedTextColor.YELLOW));
        plugin.startLoginTimeout(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.sessions().forget(event.getPlayer().getUniqueId());
        plugin.pending().remove(event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (locked(event.getPlayer()) && movedBlock(event)) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (locked(event.getPlayer())) {
            event.setCancelled(true);
            warn(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!locked(event.getPlayer())) {
            return;
        }
        String command = event.getMessage().split(" ")[0].toLowerCase(Locale.ROOT);
        for (String allowed : ALLOWED_COMMANDS) {
            if (command.equals(allowed)) {
                return;
            }
        }
        event.setCancelled(true);
        warn(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (locked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (locked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (locked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (locked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && locked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && locked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && locked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && locked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamageBy(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && locked(player)) {
            event.setCancelled(true);
        }
    }

    private boolean locked(Player player) {
        return !plugin.sessions().isAuthenticated(player.getUniqueId());
    }

    private static boolean movedBlock(PlayerMoveEvent event) {
        return event.getTo() == null
                || event.getFrom().getBlockX() != event.getTo().getBlockX()
                || event.getFrom().getBlockZ() != event.getTo().getBlockZ()
                || event.getFrom().getBlockY() != event.getTo().getBlockY();
    }

    private static void warn(Player player) {
        player.sendMessage(Component.text("Сначала войдите: /login <пароль>", NamedTextColor.RED));
    }

    private static String ip(Player player) {
        return player.getAddress() == null || player.getAddress().getAddress() == null
                ? null
                : player.getAddress().getAddress().getHostAddress();
    }
}
