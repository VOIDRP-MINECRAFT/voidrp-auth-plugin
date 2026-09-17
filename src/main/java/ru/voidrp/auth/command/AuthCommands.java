package ru.voidrp.auth.command;

import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import ru.voidrp.auth.VoidRpAuthPlugin;
import ru.voidrp.auth.model.AuthResult;

/**
 * Chat fallback for clients too old to draw dialogs, plus the admin command.
 *
 * <p>Passwords typed into chat are echoed into the server log by Paper, so this path
 * exists only for clients that cannot show the window; everyone on 1.21.6+ goes through
 * the dialog and never types a password in chat.
 */
public final class AuthCommands implements CommandExecutor, TabCompleter {

    private final VoidRpAuthPlugin plugin;

    public AuthCommands(VoidRpAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName().toLowerCase()) {
            case "login" -> handleLogin(sender, args);
            case "register" -> handleRegister(sender, args);
            case "vauth" -> handleAdmin(sender, args);
            default -> false;
        };
    }

    private boolean handleLogin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Команда только для игроков.");
            return true;
        }
        if (plugin.sessions().isAuthenticated(player.getUniqueId())) {
            player.sendMessage(Component.text("Вы уже вошли.", NamedTextColor.GRAY));
            return true;
        }
        if (args.length < 1) {
            player.sendMessage(Component.text("Использование: /login <пароль>", NamedTextColor.YELLOW));
            return true;
        }
        String password = args[0];
        String ip = ip(player);
        plugin.runAsync(() -> {
            AuthResult result = plugin.backend().login(player.getName(), password, ip);
            plugin.runSync(() -> {
                if (!player.isOnline()) {
                    return;
                }
                if (!result.ok()) {
                    player.sendMessage(Component.text(result.message(), NamedTextColor.RED));
                    return;
                }
                if (result.needsConsents()) {
                    player.sendMessage(Component.text(
                            "Примите обновлённые документы на сайте: " + plugin.config().offerUrl(), NamedTextColor.YELLOW));
                    player.kick(Component.text("Примите обновлённые документы на сайте и зайдите снова."));
                    return;
                }
                plugin.completeLogin(player, false);
            });
        });
        return true;
    }

    private boolean handleRegister(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Команда только для игроков.");
            return true;
        }
        if (plugin.sessions().isAuthenticated(player.getUniqueId())) {
            player.sendMessage(Component.text("Вы уже вошли.", NamedTextColor.GRAY));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Использование: /register <почта> <пароль>", NamedTextColor.YELLOW));
            player.sendMessage(Component.text(
                    "Регистрируясь, вы принимаете оферту " + plugin.config().offerUrl(), NamedTextColor.GRAY));
            player.sendMessage(Component.text(
                    "и согласие на обработку данных " + plugin.config().privacyUrl(), NamedTextColor.GRAY));
            return true;
        }
        String email = args[0];
        String password = args[1];
        String ip = ip(player);
        plugin.runAsync(() -> {
            AuthResult result = plugin.backend().register(
                    player.getName(), email, password, true, true, true, true, false, ip);
            plugin.runSync(() -> {
                if (!player.isOnline()) {
                    return;
                }
                if (!result.ok()) {
                    player.sendMessage(Component.text(result.message(), NamedTextColor.RED));
                    return;
                }
                player.sendMessage(Component.text("Аккаунт создан. Письмо для подтверждения отправлено на почту.",
                        NamedTextColor.GREEN));
                plugin.completeLogin(player, false);
            });
        });
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("voidrp.auth.admin")) {
            sender.sendMessage(Component.text("Нет прав.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(Component.text("/vauth reload | unlock <ник> | status <ник>", NamedTextColor.YELLOW));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadPluginConfig();
                sender.sendMessage(Component.text("Конфиг перечитан.", NamedTextColor.GREEN));
            }
            case "unlock" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Укажите ник.", NamedTextColor.RED));
                    return true;
                }
                Player target = plugin.getServer().getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(Component.text("Игрок не в сети.", NamedTextColor.RED));
                    return true;
                }
                plugin.completeLogin(target, false);
                sender.sendMessage(Component.text("Игрок впущен вручную.", NamedTextColor.GREEN));
            }
            case "status" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Укажите ник.", NamedTextColor.RED));
                    return true;
                }
                Player target = plugin.getServer().getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(Component.text("Игрок не в сети.", NamedTextColor.RED));
                    return true;
                }
                boolean authed = plugin.sessions().isAuthenticated(target.getUniqueId());
                boolean verified = plugin.sessions().isVerifiedClient(target.getUniqueId());
                sender.sendMessage(Component.text(
                        target.getName() + ": вход " + (authed ? "выполнен" : "не выполнен")
                                + ", лаунчер " + (verified ? "наш" : "сторонний"),
                        NamedTextColor.GRAY));
            }
            default -> sender.sendMessage(Component.text("/vauth reload | unlock <ник> | status <ник>", NamedTextColor.YELLOW));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("vauth") && args.length == 1) {
            return List.of("reload", "unlock", "status");
        }
        return List.of();
    }

    private static String ip(Player player) {
        return player.getAddress() == null || player.getAddress().getAddress() == null
                ? null
                : player.getAddress().getAddress().getHostAddress();
    }
}
