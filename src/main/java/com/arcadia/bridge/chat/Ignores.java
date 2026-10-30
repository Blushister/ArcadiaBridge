package com.arcadia.bridge.chat;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.data.Models.PlayerRef;
import com.arcadia.bridge.data.Repository;
import com.arcadia.bridge.i18n.Lang;
import com.arcadia.bridge.permission.Permissions;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * /ignore : un joueur ne voit plus les messages d'un autre (chat local et global, messages prives, canaux, equipe,
 * "est en train d'ecrire"), sur tous les serveurs. La liste est gardee en base et chargee a la connexion.
 * Le staff ne peut pas etre ignore, et le chat staff n'est jamais filtre.
 */
public final class Ignores {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX = 100;

    /** Joueur connecte ici -> pseudos ignores (en minuscules) et leur reference. */
    private static final Map<UUID, Map<String, PlayerRef>> IGNORED = new ConcurrentHashMap<>();

    private Ignores() {
    }

    public static void onJoin(ServerPlayer player) {
        Repository repo = Bridge.get() == null ? null : Bridge.get().repository();
        if (repo == null) {
            return;
        }
        UUID uuid = player.getUUID();
        repo.loadIgnores(uuid).thenAccept(list -> {
            Map<String, PlayerRef> map = new ConcurrentHashMap<>();
            list.forEach(ref -> map.put(ref.name().toLowerCase(Locale.ROOT), ref));
            IGNORED.put(uuid, map);
        }).exceptionally(error -> {
            LOGGER.warn("[ArcadiaBridge] Joueurs ignores de {} illisibles : {}", player.getGameProfile().getName(),
                    error.toString());
            return null;
        });
    }

    public static void onLeave(ServerPlayer player) {
        IGNORED.remove(player.getUUID());
    }

    /** Le joueur ignore-t-il cet auteur ? (auteur vide : jamais) */
    public static boolean ignores(ServerPlayer player, String sender) {
        if (sender == null || sender.isEmpty()) {
            return false;
        }
        Map<String, PlayerRef> map = IGNORED.get(player.getUUID());
        return map != null && map.containsKey(sender.toLowerCase(Locale.ROOT));
    }

    public static List<String> names(ServerPlayer player) {
        Map<String, PlayerRef> map = IGNORED.getOrDefault(player.getUUID(), Map.of());
        return map.values().stream().map(PlayerRef::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    /**
     * Ignore un joueur, ou cesse de l'ignorer s'il l'etait deja ({@code toggle}) ; {@code remove} force le retrait.
     *
     * @return le message pour le joueur
     */
    public static CompletableFuture<Component> change(ServerPlayer player, String name, boolean remove) {
        Bridge bridge = Bridge.get();
        Repository repo = bridge == null ? null : bridge.repository();
        if (repo == null) {
            return CompletableFuture.completedFuture(Lang.mc("common.unavailable"));
        }
        Map<String, PlayerRef> map = IGNORED.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>());
        PlayerRef current = map.get(name.toLowerCase(Locale.ROOT));
        if (current != null) {
            map.remove(name.toLowerCase(Locale.ROOT));
            return repo.removeIgnore(player.getUUID(), current.uuid())
                    .thenApply(v -> (Component) Lang.mc("ignore.removed", "name", current.name()));
        }
        if (remove) {
            return CompletableFuture.completedFuture(Lang.mc("ignore.not_ignored", "name", name));
        }
        if (name.equalsIgnoreCase(player.getGameProfile().getName())) {
            return CompletableFuture.completedFuture(Lang.mc("ignore.self"));
        }
        if (map.size() >= MAX) {
            return CompletableFuture.completedFuture(Lang.mc("ignore.full", "max", MAX));
        }
        ServerPlayer online = bridge.server().getPlayerList().getPlayerByName(name);
        if (online != null && Permissions.has(online, Permissions.STAFF_CHAT)) {
            return CompletableFuture.completedFuture(Lang.mc("ignore.staff"));
        }
        return bridge.resolvePlayer(name).thenCompose(ref -> {
            if (ref == null) {
                return CompletableFuture.completedFuture((Component) Lang.mc("cmd.player_unknown", "name", name));
            }
            map.put(ref.name().toLowerCase(Locale.ROOT), ref);
            return repo.addIgnore(player.getUUID(), ref)
                    .thenApply(v -> (Component) Lang.mc("ignore.added", "name", ref.name()));
        });
    }
}
