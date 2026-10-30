package com.arcadia.bridge.chat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;

/**
 * /r : le dernier correspondant de chaque joueur en message prive (recu ou envoye), qu'il soit sur ce serveur,
 * sur un autre ou hors ligne. /r relance simplement /msg vers lui, qui sait deja joindre les trois.
 */
public final class Replies {

    private static final Map<UUID, String> LAST = new ConcurrentHashMap<>();

    private Replies() {
    }

    public static void remember(ServerPlayer player, String partner) {
        if (player != null && partner != null && partner.matches("[A-Za-z0-9_]{1,16}")
                && !partner.equalsIgnoreCase(player.getGameProfile().getName())) {
            LAST.put(player.getUUID(), partner);
        }
    }

    public static String partner(ServerPlayer player) {
        return LAST.get(player.getUUID());
    }

    public static void onLeave(ServerPlayer player) {
        LAST.remove(player.getUUID());
    }
}
