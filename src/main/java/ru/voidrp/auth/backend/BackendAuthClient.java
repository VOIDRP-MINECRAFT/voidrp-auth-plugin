package ru.voidrp.auth.backend;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.voidrp.auth.config.AuthConfig;
import ru.voidrp.auth.model.AuthResult;

/**
 * Talks to ``/api/v1/server/auth`` on the site. Every call blocks, so callers must be
 * off the main thread — the dialogs already run on the connection thread, and the
 * chat fallback hops to an async task.
 */
public final class BackendAuthClient {

    private final Supplier<AuthConfig> config;
    private final Logger logger;
    private final HttpClient http;

    public BackendAuthClient(Supplier<AuthConfig> config, Logger logger) {
        this.config = config;
        this.logger = logger;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public AuthResult accountState(String nickname) {
        String path = "/server/auth/game/account/" + URLEncoder.encode(nickname, StandardCharsets.UTF_8);
        return send(request(path).GET().build(), "проверить аккаунт");
    }

    public AuthResult login(String nickname, String password, String ip) {
        JsonObject body = new JsonObject();
        body.addProperty("minecraft_nickname", nickname);
        body.addProperty("password", password);
        if (ip != null) body.addProperty("ip", ip);
        return send(request("/server/auth/game/login").POST(json(body)).build(), "войти");
    }

    public AuthResult register(String nickname, String email, String password, boolean offer,
                               boolean personalData, boolean distProfile, boolean distMap,
                               boolean distPurchases, String ip) {
        JsonObject body = new JsonObject();
        body.addProperty("minecraft_nickname", nickname);
        body.addProperty("email", email);
        body.addProperty("password", password);
        body.addProperty("password_repeat", password);
        body.addProperty("accept_offer", offer);
        body.addProperty("accept_personal_data", personalData);
        body.addProperty("distribution_profile", distProfile);
        body.addProperty("distribution_map", distMap);
        body.addProperty("distribution_purchases", distPurchases);
        if (ip != null) body.addProperty("ip", ip);
        return send(request("/server/auth/game/register").POST(json(body)).build(), "зарегистрироваться");
    }

    public AuthResult acceptConsents(String nickname, boolean distProfile, boolean distMap,
                                     boolean distPurchases, String ip) {
        JsonObject body = new JsonObject();
        body.addProperty("minecraft_nickname", nickname);
        body.addProperty("accept_offer", true);
        body.addProperty("accept_personal_data", true);
        body.addProperty("distribution_profile", distProfile);
        body.addProperty("distribution_map", distMap);
        body.addProperty("distribution_purchases", distPurchases);
        if (ip != null) body.addProperty("ip", ip);
        return send(request("/server/auth/game/consents").POST(json(body)).build(), "принять документы");
    }

    /** Counts a login that skipped the password window (kept session, launcher ticket). */
    public AuthResult seen(String nickname, boolean fromLauncher) {
        JsonObject body = new JsonObject();
        body.addProperty("minecraft_nickname", nickname);
        body.addProperty("client", fromLauncher ? "launcher" : "external");
        return send(request("/server/auth/game/seen").POST(json(body)).build(), "отметить вход");
    }

    /** Validates a launcher ticket taken from the address the client connected to. */
    public AuthResult consumePlayTicket(String ticket, String nickname) {
        JsonObject body = new JsonObject();
        body.addProperty("ticket", ticket);
        body.addProperty("player_name", nickname);
        return send(request("/server/auth/consume-play-ticket").POST(json(body)).build(), "проверить билет лаунчера");
    }

    private HttpRequest.Builder request(String path) {
        AuthConfig cfg = config.get();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(cfg.backendUrl() + "/api/v1" + path))
                .timeout(cfg.requestTimeout())
                .header("X-Game-Auth-Secret", cfg.secret())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        if (cfg.serverSlug() != null && !cfg.serverSlug().isBlank()) {
            builder.header("X-Server-Slug", cfg.serverSlug());
        }
        return builder;
    }

    private static HttpRequest.BodyPublisher json(JsonObject body) {
        return HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8);
    }

    private AuthResult send(HttpRequest request, String what) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonObject json = parse(response.body());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return success(json);
            }
            return AuthResult.failure(response.statusCode(), detail(json, what));
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            return AuthResult.error("Соединение прервано. Попробуйте зайти ещё раз.");
        } catch (Exception exc) {
            logger.log(Level.WARNING, "Не удалось " + what + ": " + exc.getMessage());
            return AuthResult.error("Сайт сейчас недоступен. Попробуйте через пару минут.");
        }
    }

    private static JsonObject parse(String body) {
        try {
            JsonElement element = JsonParser.parseString(body == null ? "" : body);
            return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (Exception exc) {
            return new JsonObject();
        }
    }

    private static AuthResult success(JsonObject json) {
        List<String> missing = new ArrayList<>();
        if (json.has("consents_missing") && json.get("consents_missing").isJsonArray()) {
            JsonArray array = json.getAsJsonArray("consents_missing");
            for (JsonElement element : array) {
                missing.add(element.getAsString());
            }
        }
        return new AuthResult(
                true,
                200,
                null,
                optString(json, "minecraft_nickname"),
                !json.has("registered") || json.get("registered").getAsBoolean(),
                !json.has("account_active") || json.get("account_active").getAsBoolean(),
                json.has("email_verified") && json.get("email_verified").getAsBoolean(),
                missing);
    }

    /** FastAPI puts the human-readable reason in ``detail``; validation errors make it a list. */
    private static String detail(JsonObject json, String what) {
        JsonElement detail = json.get("detail");
        if (detail == null) {
            return "Не удалось " + what + ". Попробуйте ещё раз.";
        }
        if (detail.isJsonPrimitive()) {
            return detail.getAsString();
        }
        if (detail.isJsonArray() && !detail.getAsJsonArray().isEmpty()) {
            JsonElement first = detail.getAsJsonArray().get(0);
            if (first.isJsonObject() && first.getAsJsonObject().has("msg")) {
                return first.getAsJsonObject().get("msg").getAsString();
            }
        }
        return "Не удалось " + what + ". Проверьте введённые данные.";
    }

    private static String optString(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }
}
