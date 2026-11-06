package com.arcadia.bridge.compat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.logging.LogUtils;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * Salons vocaux dans Simple Voice Chat : un salon est un groupe isole (ses membres ne s'entendent qu'entre eux), cache du
 * menu des groupes du mod et protege par un mot de passe aleatoire, pour qu'on n'y entre que par l'ecran des salons.
 *
 * <p>Ne touche aux classes du mod que s'il est installe : voir {@link com.arcadia.bridge.chat.VoiceRooms}. Entre deux serveurs
 * Minecraft, l'audio passe par {@link VoiceRelay} ; sans relais configure, il reste local a chaque serveur.
 */
public final class VoiceCompat {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile VoicechatServerApi api;
    /** Identifiant du groupe -> nom du salon, pour reconnaitre d'un coup d'oeil la voix a relayer. */
    private static volatile Map<UUID, String> roomsById = Map.of();
    /** Occupants d'ici de chaque salon : lus par le relais sur le fil du reseau vocal, a chaque paquet. */
    private static volatile Map<String, Listeners> listeners = Map.of();
    private static final AtomicInteger GENERATION = new AtomicInteger();

    /** Les connexions vocales d'un salon ; {@code generation} change quand elles changent. */
    record Listeners(List<VoicechatConnection> connections, int generation) {
    }

    public enum JoinResult { OK, NOT_READY, NOT_CONNECTED }

    private VoiceCompat() {
    }

    /** Le relais entre serveurs est-il en service ? */
    public static boolean relayActive() {
        return VoiceRelay.active();
    }

    public static boolean relayReachable() {
        return VoiceRelay.reachable();
    }

    static void setApi(VoicechatServerApi serverApi) {
        api = serverApi;
    }

    static String roomOfGroup(UUID id) {
        return roomsById.get(id);
    }

    static Listeners listeners(String room) {
        return listeners.get(room);
    }

    /** Thread principal, a chaque changement : qui ecoute quel salon ici. */
    public static void refresh(Iterable<String> rooms, Iterable<ServerPlayer> players) {
        VoicechatServerApi current = api;
        if (current == null) {
            return;
        }
        Map<UUID, String> ids = new HashMap<>();
        Map<String, List<VoicechatConnection>> occupants = new HashMap<>();
        for (String room : rooms) {
            ids.put(idOf(room), room);
            occupants.put(room, new ArrayList<>());
        }
        for (ServerPlayer player : players) {
            VoicechatConnection connection = current.getConnectionOf(player.getUUID());
            Group group = connection == null ? null : connection.getGroup();
            String room = group == null ? null : ids.get(group.getId());
            if (room != null) {
                occupants.get(room).add(connection);
            }
        }
        int generation = GENERATION.incrementAndGet();
        Map<String, Listeners> next = new HashMap<>();
        occupants.forEach((room, connections) -> next.put(room, new Listeners(List.copyOf(connections), generation)));
        roomsById = Map.copyOf(ids);
        listeners = Map.copyOf(next);
    }

    private static UUID idOf(String room) {
        return UUID.nameUUIDFromBytes(("arcadia:voice:" + room).getBytes(StandardCharsets.UTF_8));
    }

    private static Group groupOf(VoicechatServerApi current, String room) {
        UUID id = idOf(room);
        // Pas getGroup(id) : pour un groupe inconnu, l'API rend une enveloppe vide au lieu de null, et setGroup plante dessus.
        for (Group group : current.getGroups()) {
            if (id.equals(group.getId())) {
                return group;
            }
        }
        return current.groupBuilder().setId(id).setName(room).setPassword(UUID.randomUUID().toString())
                .setPersistent(true).setHidden(true).setType(Group.Type.ISOLATED).build();
    }

    /** Place le joueur dans le salon (et le retire de celui ou il etait). */
    public static JoinResult join(ServerPlayer player, String room) {
        VoicechatServerApi current = api;
        if (current == null) {
            return JoinResult.NOT_READY;
        }
        VoicechatConnection connection = current.getConnectionOf(player.getUUID());
        if (connection == null || !connection.isInstalled()) {
            return JoinResult.NOT_CONNECTED;
        }
        connection.setGroup(groupOf(current, room));
        return JoinResult.OK;
    }

    public static void leave(ServerPlayer player) {
        VoicechatServerApi current = api;
        VoicechatConnection connection = current == null ? null : current.getConnectionOf(player.getUUID());
        if (connection != null && connection.isInGroup()) {
            connection.setGroup(null);
        }
    }

    /** Le salon vocal ou est ce joueur parmi les noms donnes, ou vide. */
    public static String roomOf(ServerPlayer player, Iterable<String> rooms) {
        VoicechatServerApi current = api;
        VoicechatConnection connection = current == null ? null : current.getConnectionOf(player.getUUID());
        Group group = connection == null ? null : connection.getGroup();
        if (group != null) {
            for (String room : rooms) {
                if (idOf(room).equals(group.getId())) {
                    return room;
                }
            }
        }
        return "";
    }

    /** Les joueurs de cette liste qui sont dans le salon. */
    public static List<ServerPlayer> occupants(String room, Iterable<ServerPlayer> players) {
        List<ServerPlayer> found = new ArrayList<>();
        VoicechatServerApi current = api;
        if (current == null) {
            return found;
        }
        UUID id = idOf(room);
        for (ServerPlayer player : players) {
            VoicechatConnection connection = current.getConnectionOf(player.getUUID());
            Group group = connection == null ? null : connection.getGroup();
            if (group != null && id.equals(group.getId())) {
                found.add(player);
            }
        }
        return found;
    }

    /** Salon supprime : ses occupants en sortent et le groupe disparait. */
    public static void remove(String room, Iterable<ServerPlayer> players) {
        VoicechatServerApi current = api;
        if (current == null) {
            return;
        }
        UUID id = idOf(room);
        for (ServerPlayer player : players) {
            VoicechatConnection connection = current.getConnectionOf(player.getUUID());
            Group group = connection == null ? null : connection.getGroup();
            if (group != null && id.equals(group.getId())) {
                connection.setGroup(null);
            }
        }
        if (!current.removeGroup(id)) {
            LOGGER.debug("[ArcadiaBridge] Groupe vocal {} deja absent.", room);
        }
    }
}
