package com.arcadia.bridge.api;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.data.Models.Link;

/**
 * API publique pour les autres mods Arcadia. Toutes les methodes lisent le cache memoire :
 * elles sont instantanees et utilisables depuis le thread principal.
 */
public final class ArcadiaBridgeAPI {

    private ArcadiaBridgeAPI() {
    }

    /** Le mod est demarre et la base de donnees est disponible. */
    public static boolean isAvailable() {
        Bridge bridge = Bridge.get();
        return bridge != null && bridge.isDatabaseReady();
    }

    public static boolean isDiscordConnected() {
        Bridge bridge = Bridge.get();
        return bridge != null && bridge.discord().isReady();
    }

    /** Le joueur a lie son compte Discord. */
    public static boolean isLinked(UUID player) {
        Bridge bridge = Bridge.get();
        return bridge != null && bridge.cache().isLinked(player);
    }

    public static OptionalLong getLinkedDiscordId(UUID player) {
        Bridge bridge = Bridge.get();
        if (bridge == null) {
            return OptionalLong.empty();
        }
        Optional<Link> link = bridge.cache().link(player);
        return link.map(l -> OptionalLong.of(l.discordId())).orElseGet(OptionalLong::empty);
    }

    public static boolean isBanned(UUID player) {
        Bridge bridge = Bridge.get();
        return bridge != null && bridge.cache().activeBan(player).isPresent();
    }

    public static boolean isMuted(UUID player) {
        Bridge bridge = Bridge.get();
        return bridge != null && bridge.cache().activeMute(player).isPresent();
    }

    /** Envoie un message texte dans le salon de chat Discord (mentions desactivees). */
    public static void sendToDiscord(String message) {
        Bridge bridge = Bridge.get();
        if (bridge != null) {
            bridge.discord().sendChat(message);
        }
    }
}
