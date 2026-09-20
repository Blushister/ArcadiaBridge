package com.arcadia.bridge.discord;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.util.Text;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Envoi des messages de chat via un webhook Discord : chaque joueur apparait avec son pseudo et sa
 * tete, au lieu d'un message unique du bot. Optionnel (URL vide = messages classiques du bot).
 */
public final class ChatWebhook {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    /** Limite Discord sur le pseudo affiche par un webhook. */
    private static final int NAME_MAX = 80;

    /** Webhook cree automatiquement par le bot quand la configuration n'en impose pas un. */
    private static volatile String runtimeUrl = "";

    private ChatWebhook() {
    }

    static void setRuntimeUrl(String url) {
        runtimeUrl = url == null ? "" : url;
    }

    /** URL retenue : celle de la configuration si elle est remplie, sinon celle creee par le bot. */
    private static String url() {
        String configured = BridgeConfig.CHAT_WEBHOOK_URL.get().trim();
        return configured.isEmpty() ? runtimeUrl : configured;
    }

    public static boolean isConfigured() {
        String url = url();
        return url.startsWith("https://discord.com/api/webhooks/")
                || url.startsWith("https://discordapp.com/api/webhooks/");
    }

    /**
     * Poste un message de joueur. Renvoie false si l'envoi n'a pas pu etre tente, pour que l'appelant
     * repasse par le bot.
     */
    public static boolean send(UUID uuid, String displayName, String message) {
        if (!isConfigured()) {
            return false;
        }
        String avatar = Text.fill(BridgeConfig.CHAT_AVATAR_URL.get(), "uuid", uuid, "name", displayName);
        JsonObject body = new JsonObject();
        body.addProperty("username", trim(displayName, NAME_MAX));
        if (!avatar.isBlank()) {
            body.addProperty("avatar_url", avatar);
        }
        body.addProperty("content", trim(message, 2000));
        // Ceinture et bretelles : le texte est deja desamorce, et Discord n'autorise aucune mention ici.
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray());
        body.add("allowed_mentions", allowed);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenAccept(response -> {
                    if (response.statusCode() >= 300) {
                        LOGGER.warn("[ArcadiaBridge] Webhook refuse (HTTP {}). Verifie l'URL dans la configuration.",
                                response.statusCode());
                    }
                })
                .exceptionally(error -> {
                    LOGGER.warn("[ArcadiaBridge] Envoi webhook impossible : {}", error.toString());
                    return null;
                });
        return true;
    }

    private static String trim(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
