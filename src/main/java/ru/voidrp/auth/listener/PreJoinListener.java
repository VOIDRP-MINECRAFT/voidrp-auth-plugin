package ru.voidrp.auth.listener;

import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import ru.voidrp.auth.VoidRpAuthPlugin;
import ru.voidrp.auth.config.AuthConfig;
import ru.voidrp.auth.dialog.AuthDialogs;
import ru.voidrp.auth.model.AuthResult;
import ru.voidrp.auth.session.PendingAuth;

/**
 * The whole login flow, run while the connection is still in the configuration phase —
 * before the player exists in the world, so there is nothing to freeze, protect or hide.
 *
 * <p>The event is fired off the main thread and the server waits for the handler to
 * return, which is what lets us block here until the player answers the window. Clients
 * older than 1.21.6 cannot draw dialogs, so they are handed to the chat fallback instead
 * and land in limbo on the other side.
 */
public final class PreJoinListener implements Listener {

    private final VoidRpAuthPlugin plugin;

    public PreJoinListener(VoidRpAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
        PlayerConfigurationConnection connection = event.getConnection();
        AuthConfig config = plugin.config();
        String nickname = connection.getProfile().getName();
        if (nickname == null || nickname.isBlank()) {
            return;
        }
        String ip = addressOf(connection);

        if (!config.isConfigured()) {
            plugin.getLogger().severe("backend.secret не задан — вход не проверяется, игроки заходят без пароля.");
            return;
        }

        // 1. Players of our launcher never see a window. Two ways to recognise them:
        //    a ticket carried in the address they connected to (needs a wildcard domain),
        //    or — the default — the ticket the launcher took for this nickname and address.
        String ticket = ticketFrom(connection.getVirtualHost(), config);
        if (ticket != null) {
            AuthResult consumed = plugin.backend().consumePlayTicket(ticket, nickname);
            if (consumed.ok()) {
                plugin.pending().put(nickname, PendingAuth.launcher());
                return;
            }
            plugin.getLogger().info("Билет лаунчера отклонён для " + nickname + ": " + consumed.message());
        }

        if (config.launcherTicketByNickname()) {
            AuthResult launcher = plugin.backend().launcherTicket(nickname, ip);
            if (launcher.ok()) {
                if (launcher.needsConsents() && plugin.dialogsSupported(connection)
                        && !runConsents(connection, config, nickname, ip)) {
                    return;
                }
                connection.getAudience().closeDialog();
                plugin.pending().put(nickname, PendingAuth.launcher());
                return;
            }
        }

        // 2. A player who was here minutes ago (restart, crash, timeout) walks back in —
        //    but the account is still re-checked, so one deleted or disabled on the site
        //    does not keep walking in on a session we handed out earlier.
        boolean hadSession = plugin.sessions().hasRecentSession(nickname, ip);
        if (hadSession) {
            AuthResult recheck = plugin.backend().accountState(nickname);
            if (recheck.ok() && recheck.registered() && recheck.accountActive() && !recheck.needsConsents()) {
                plugin.backend().seen(nickname, false);
                plugin.pending().put(nickname, PendingAuth.session());
                return;
            }
            plugin.sessions().clearSession(nickname, ip);
        }

        if (!plugin.dialogsSupported(connection)) {
            // Old client: let it into the world, LimboListener freezes it and the
            // /login and /register commands take over from there.
            plugin.pending().put(nickname, PendingAuth.chatFallback());
            return;
        }

        AuthResult state = plugin.backend().accountState(nickname);
        if (!state.ok()) {
            connection.disconnect(Component.text(state.message(), NamedTextColor.RED));
            return;
        }
        if (state.registered() && !state.accountActive()) {
            connection.disconnect(Component.text("Аккаунт отключён. Напишите в поддержку.", NamedTextColor.RED));
            return;
        }

        boolean authenticated = state.registered()
                ? runLogin(connection, config, nickname, ip)
                : runRegister(connection, config, nickname, ip);
        if (!authenticated) {
            return;
        }

        // Documents may have changed since this account last agreed to them.
        AuthResult fresh = plugin.backend().accountState(nickname);
        if (fresh.ok() && fresh.needsConsents() && !runConsents(connection, config, nickname, ip)) {
            return;
        }

        connection.getAudience().closeDialog();
        plugin.pending().put(nickname, PendingAuth.password());
    }

    private boolean runLogin(PlayerConfigurationConnection connection, AuthConfig config, String nickname, String ip) {
        String error = null;
        while (connection.isConnected()) {
            final String shownError = error;
            DialogResponseView response = ask(connection, config,
                    (dialogs, onSubmit) -> dialogs.login(nickname, shownError, onSubmit));
            if (response == null) {
                return false;
            }
            String password = text(response, AuthDialogs.FIELD_PASSWORD);
            if (password.isEmpty()) {
                error = "Введите пароль.";
                continue;
            }
            AuthResult result = plugin.backend().login(nickname, password, ip);
            if (result.ok()) {
                return true;
            }
            error = result.message();
        }
        return false;
    }

    private boolean runRegister(PlayerConfigurationConnection connection, AuthConfig config, String nickname, String ip) {
        String error = null;
        while (connection.isConnected()) {
            final String shownError = error;
            DialogResponseView response = ask(connection, config,
                    (dialogs, onSubmit) -> dialogs.register(nickname, shownError, onSubmit));
            if (response == null) {
                return false;
            }
            String email = text(response, AuthDialogs.FIELD_EMAIL);
            String password = text(response, AuthDialogs.FIELD_PASSWORD);
            String repeat = text(response, AuthDialogs.FIELD_PASSWORD_REPEAT);
            if (email.isEmpty() || !email.contains("@")) {
                error = "Укажите настоящую почту — на неё придёт письмо для подтверждения.";
                continue;
            }
            if (password.length() < 8) {
                error = "Пароль должен быть не короче 8 символов.";
                continue;
            }
            if (!password.equals(repeat)) {
                error = "Пароли не совпадают.";
                continue;
            }
            if (!bool(response, AuthDialogs.FIELD_OFFER) || !bool(response, AuthDialogs.FIELD_PERSONAL_DATA)) {
                error = "Без оферты и согласия на обработку данных аккаунт создать нельзя.";
                continue;
            }
            AuthResult result = plugin.backend().register(
                    nickname,
                    email,
                    password,
                    true,
                    true,
                    bool(response, AuthDialogs.FIELD_DIST_PROFILE),
                    bool(response, AuthDialogs.FIELD_DIST_MAP),
                    bool(response, AuthDialogs.FIELD_DIST_PURCHASES),
                    ip);
            if (result.ok()) {
                return true;
            }
            error = result.message();
        }
        return false;
    }

    private boolean runConsents(PlayerConfigurationConnection connection, AuthConfig config, String nickname, String ip) {
        String error = null;
        while (connection.isConnected()) {
            final String shownError = error;
            DialogResponseView response = ask(connection, config,
                    (dialogs, onSubmit) -> dialogs.consents(shownError, onSubmit));
            if (response == null) {
                return false;
            }
            if (!bool(response, AuthDialogs.FIELD_OFFER) || !bool(response, AuthDialogs.FIELD_PERSONAL_DATA)) {
                error = "Чтобы играть, нужно принять оба документа.";
                continue;
            }
            AuthResult result = plugin.backend().acceptConsents(
                    nickname,
                    bool(response, AuthDialogs.FIELD_DIST_PROFILE),
                    bool(response, AuthDialogs.FIELD_DIST_MAP),
                    bool(response, AuthDialogs.FIELD_DIST_PURCHASES),
                    ip);
            if (result.ok()) {
                return true;
            }
            error = result.message();
        }
        return false;
    }

    /**
     * Shows one window and waits for it, polling so a player who quits mid-window frees
     * the thread instead of holding it until the timeout.
     */
    private DialogResponseView ask(PlayerConfigurationConnection connection, AuthConfig config,
                                   java.util.function.BiFunction<AuthDialogs, java.util.function.Consumer<DialogResponseView>, Dialog> factory) {
        BlockingQueue<DialogResponseView> answer = new ArrayBlockingQueue<>(1);
        Dialog dialog = factory.apply(plugin.dialogs(), answer::offer);
        connection.getAudience().showDialog(dialog);

        long deadline = System.currentTimeMillis() + config.loginTimeoutSeconds() * 1000L;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (!connection.isConnected()) {
                    return null;
                }
                DialogResponseView response = answer.poll(500, TimeUnit.MILLISECONDS);
                if (response != null) {
                    return response;
                }
            }
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            return null;
        }
        connection.disconnect(Component.text("Вы слишком долго не вводили данные. Зайдите заново.", NamedTextColor.RED));
        return null;
    }

    private static String text(DialogResponseView response, String key) {
        String value = response.getText(key);
        return value == null ? "" : value.trim();
    }

    private static boolean bool(DialogResponseView response, String key) {
        Boolean value = response.getBoolean(key);
        return value != null && value;
    }

    private static String addressOf(PlayerConfigurationConnection connection) {
        InetSocketAddress address = connection.getClientAddress();
        return address == null || address.getAddress() == null ? null : address.getAddress().getHostAddress();
    }

    /**
     * Pulls the launcher ticket out of the address the client dialled: the launcher
     * connects to ``<ticket>.origins.void-rp.ru`` so a vanilla client can carry it
     * without any mod.
     */
    private static String ticketFrom(InetSocketAddress virtualHost, AuthConfig config) {
        if (!config.ticketFromHostname() || virtualHost == null) {
            return null;
        }
        String host = virtualHost.getHostString();
        if (host == null || host.isBlank()) {
            return null;
        }
        int dot = host.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        String candidate = host.substring(0, dot).toLowerCase(Locale.ROOT);
        // Tickets are long random strings; a plain "play.void-rp.ru" must not look like one.
        return candidate.length() >= 16 && candidate.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-')
                ? candidate
                : null;
    }
}
