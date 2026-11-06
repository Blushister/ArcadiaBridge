package com.arcadia.bridge.chat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.arcadia.bridge.config.BridgeConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Appelle le service de traduction (translator). Rien ne bloque le jeu : un message que le service ne traite pas
 * (coupe, debordement) reste simplement en version originale.
 */
public final class MessageTranslator {

    /** {@code lang} : langue detectee ; {@code text} vide s'il n'y a rien a traduire. */
    public record Result(String lang, String text) {
        static final Result NONE = new Result("", "");
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_IN_FLIGHT = 32;
    /** Un message court attend sa traduction avant de partir : le service repond vite, ou le message part sans. */
    static final Duration FAST = Duration.ofMillis(1500);
    /** Traduction faite apres coup (long message, modification, rattrapage) : personne n'attend. */
    static final Duration SLOW = Duration.ofSeconds(8);
    /** Service en panne : on ne le rappelle pas tout de suite, pour que le chat ne prenne aucun retard. */
    private static final long PAUSE_AFTER_FAILURE_MS = 30_000;
    /** Une reponse trop lente n'est pas une panne, sauf si elles s'enchainent. */
    private static final int TIMEOUTS_BEFORE_PAUSE = 3;
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private final AtomicInteger inFlight = new AtomicInteger();
    /** Une seule alerte tant que le service reste injoignable. */
    private final AtomicBoolean warned = new AtomicBoolean();
    private volatile long pausedUntil;
    private final AtomicInteger timeouts = new AtomicInteger();

    public boolean enabled() {
        return !BridgeConfig.HISTORY_TRANSLATOR_URL.get().isBlank() || DeepLClient.active();
    }

    /** Le service n'est pas en pause apres une panne. */
    public boolean available() {
        return System.currentTimeMillis() >= pausedUntil;
    }

    CompletableFuture<Result> translate(String text, Duration timeout) {
        if (!enabled() || System.currentTimeMillis() < pausedUntil) {
            return CompletableFuture.completedFuture(Result.NONE);
        }
        if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) {
            inFlight.decrementAndGet();
            return CompletableFuture.completedFuture(Result.NONE);
        }
        if (DeepLClient.active()) {
            return DeepLClient.translate(CLIENT, text, timeout).handle((result, error) -> {
                inFlight.decrementAndGet();
                if (error != null) {
                    return onFailure(error);
                }
                timeouts.set(0);
                warned.set(false);
                return result;
            });
        }
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(BridgeConfig.HISTORY_TRANSLATOR_URL.get().trim() + "/translate"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            inFlight.decrementAndGet();
            warn(e);
            return CompletableFuture.completedFuture(Result.NONE);
        }
        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).handle((response, error) -> {
            inFlight.decrementAndGet();
            if (error != null) {
                return onFailure(error);
            }
            timeouts.set(0);
            warned.set(false);
            if (response.statusCode() != 200) {
                return Result.NONE;
            }
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            String lang = json.get("lang").getAsString();
            String translation = json.get("translation").getAsString();
            // Un autre objet que NONE : le service a repondu, meme s'il n'y a rien a traduire ("gg").
            return new Result(lang, translation);
        });
    }

    /** Une reponse lente n'est pas une panne, sauf si elles s'enchainent ; une vraie erreur met le service en pause. */
    private Result onFailure(Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        boolean slow = cause instanceof HttpTimeoutException && !(cause instanceof HttpConnectTimeoutException);
        if (!slow || timeouts.incrementAndGet() >= TIMEOUTS_BEFORE_PAUSE) {
            timeouts.set(0);
            pausedUntil = System.currentTimeMillis() + PAUSE_AFTER_FAILURE_MS;
            warn(cause);
        }
        return Result.NONE;
    }

    private void warn(Throwable error) {
        if (warned.compareAndSet(false, true)) {
            LOGGER.warn("[ArcadiaBridge] Service de traduction injoignable, messages laisses en version originale : {}", error.toString());
        }
    }
}
