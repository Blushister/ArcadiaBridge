package com.arcadia.bridge.chat;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.compat.VoiceCompat;
import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.db.Database;
import com.arcadia.bridge.i18n.Lang;
import com.arcadia.bridge.permission.Permissions;
import com.arcadia.bridge.tabs.ChatTabs;
import com.arcadia.bridge.tabs.TabsProtocol;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

/**
 * Salons vocaux de l'ecran des salons, publics ou prives, si Simple Voice Chat est installe sur le serveur. La liste est
 * commune a tous les serveurs ; l'audio, lui, reste propre a chaque serveur Minecraft.
 *
 * <p>Sans le mod, rien n'est envoye aux joueurs et les classes de {@link VoiceCompat} ne sont jamais chargees.
 */
public final class VoiceRooms {

    /** Canal interne du relais : la liste des salons ou leurs membres ont change. */
    public static final String RELAY = "voice_update";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{2,24}");

    private record Room(String name, boolean isPrivate, Set<UUID> members, boolean temporary, long createdAt) {
    }

    /** Un salon temporaire vide disparait, une fois passe ce delai apres sa creation. */
    private static final long TEMPORARY_GRACE_MS = 60_000;

    private record RemoteState(long at, Map<String, Map<String, String>> rooms) {
    }

    /** Canal interne du relais : qui est dans quel salon sur un serveur (JSON dans la ligne mise en forme). */
    public static final String OCCUPANTS = "voice_occupants";
    private static final long REMOTE_TTL_MS = 60_000;
    private final Map<String, RemoteState> remote = new ConcurrentHashMap<>();
    private String lastPublished = "";

    private final Bridge bridge;
    private volatile Map<String, Room> rooms = Map.of();
    private final AtomicBoolean broadcastPending = new AtomicBoolean();
    private static final int ACTIONS_PER_WINDOW = 8;
    private static final long ACTION_WINDOW_MS = 2000;
    private final Map<UUID, java.util.ArrayDeque<Long>> recentActions = new ConcurrentHashMap<>();

    private boolean tooMany(ServerPlayer player) {
        long now = System.currentTimeMillis();
        java.util.ArrayDeque<Long> times = recentActions.computeIfAbsent(player.getUUID(), id -> new java.util.ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > ACTION_WINDOW_MS) {
                times.pollFirst();
            }
            if (times.size() >= ACTIONS_PER_WINDOW) {
                return true;
            }
            times.addLast(now);
            return false;
        }
    }

    public VoiceRooms(Bridge bridge) {
        this.bridge = bridge;
    }

    public static boolean available() {
        return BridgeConfig.VOICE_ENABLED.get() && ModList.get().isLoaded("voicechat");
    }

    private Database db() {
        return available() ? bridge.database() : null;
    }

    public void start() {
        reload();
    }

    /** Relit les salons en base (au demarrage, et quand un autre serveur les a changes). */
    public void reload() {
        Database db = db();
        if (db == null) {
            return;
        }
        db.async(c -> {
            Map<String, Room> loaded = new LinkedHashMap<>();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT name, is_private, temporary, created_at FROM " + db.t("voice_rooms") + " ORDER BY name")) {
                while (rs.next()) {
                    loaded.put(rs.getString(1), new Room(rs.getString(1), rs.getBoolean(2), new HashSet<>(), rs.getBoolean(3), rs.getLong(4)));
                }
            }
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT room, uuid FROM " + db.t("voice_members"))) {
                while (rs.next()) {
                    Room room = loaded.get(rs.getString(1));
                    if (room != null) {
                        room.members().add(UUID.fromString(rs.getString(2)));
                    }
                }
            }
            return loaded;
        }).thenAccept(loaded -> bridge.runOnMain(() -> apply(loaded))).exceptionally(error -> {
            LOGGER.warn("[ArcadiaBridge] Salons vocaux illisibles : {}", error.toString());
            return null;
        });
    }

    private void apply(Map<String, Room> loaded) {
        Map<String, Room> previous = rooms;
        rooms = loaded;
        var players = bridge.server().getPlayerList().getPlayers();
        for (String name : previous.keySet()) {
            if (!loaded.containsKey(name)) {
                VoiceCompat.remove(name, List.copyOf(players));
            }
        }
        // Retire d'un salon prive ceux qui n'y ont plus droit.
        for (ServerPlayer player : players) {
            String current = VoiceCompat.roomOf(player, loaded.keySet());
            Room room = loaded.get(current);
            if (room != null && !mayJoin(player, room)) {
                VoiceCompat.leave(player);
            }
        }
        broadcast();
    }

    private static boolean isManager(ServerPlayer player) {
        return Permissions.has(player, Permissions.CHANNELS_MANAGE);
    }

    private static boolean mayJoin(ServerPlayer player, Room room) {
        return !room.isPrivate() || isManager(player) || room.members().contains(player.getUUID());
    }

    // ---------------------------------------------------------------- envoi aux clients

    public void sendTo(ServerPlayer player) {
        if (!available() || !ChatTabs.supportsRooms(player) || player.hasDisconnected()) {
            return;
        }
        List<TabsProtocol.VoiceRoomEntry> entries = new ArrayList<>();
        Map<String, Map<String, String>> occupants = occupants();
        for (Room room : rooms.values()) {
            if (mayJoin(player, room)) {
                Map<String, String> inside = occupants.getOrDefault(room.name(), Map.of());
                entries.add(new TabsProtocol.VoiceRoomEntry(room.name(), room.isPrivate(), List.copyOf(inside.keySet()), List.copyOf(inside.values()),
                        room.temporary()));
            }
        }
        PacketDistributor.sendToPlayer(player, new TabsProtocol.VoiceState(isManager(player), VoiceCompat.relayActive(),
                VoiceCompat.roomOf(player, rooms.keySet()), entries));
    }

    /** Salon -> joueur -> UUID, ici et sur les autres serveurs, dans l'ordre des noms. */
    private Map<String, Map<String, String>> occupants() {
        Map<String, Map<String, String>> all = new HashMap<>();
        localOccupants().forEach((room, inside) -> all.computeIfAbsent(room, k -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER)).putAll(inside));
        long now = System.currentTimeMillis();
        remote.values().removeIf(state -> now - state.at() > REMOTE_TTL_MS);
        remote.values().forEach(state -> state.rooms().forEach((room, inside) ->
                all.computeIfAbsent(room, k -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER)).putAll(inside)));
        return all;
    }

    private Map<String, Map<String, String>> localOccupants() {
        var everyone = List.copyOf(bridge.server().getPlayerList().getPlayers());
        Map<String, Map<String, String>> local = new TreeMap<>();
        for (String room : rooms.keySet()) {
            Map<String, String> inside = new TreeMap<>();
            VoiceCompat.occupants(room, everyone).forEach(player -> inside.put(player.getGameProfile().getName(), player.getUUID().toString()));
            if (!inside.isEmpty()) {
                local.put(room, inside);
            }
        }
        return local;
    }

    private void broadcast() {
        VoiceCompat.refresh(rooms.keySet(), List.copyOf(bridge.server().getPlayerList().getPlayers()));
        publishOccupants(false);
        bridge.server().getPlayerList().getPlayers().forEach(this::sendTo);
    }

    /** Dit aux autres serveurs qui est dans quel salon ici : si ca change, et regulierement pour qu'ils sachent qu'on est la. */
    private void publishOccupants(boolean force) {
        if (bridge.repository() == null || !VoiceCompat.relayActive()) {
            return;
        }
        String text = new Gson().toJson(localOccupants());
        if (force || !text.equals(lastPublished)) {
            lastPublished = text;
            bridge.repository().publishChat(Bridge.serverId(), "", "", "", text, OCCUPANTS);
        }
    }

    /** Occupants annonces par un autre serveur (thread principal). */
    public void onRemoteOccupants(String serverId, String json) {
        if (!available() || json == null) {
            return;
        }
        Map<String, Map<String, String>> parsed = new Gson().fromJson(json, new TypeToken<Map<String, Map<String, String>>>() { }.getType());
        RemoteState previous = remote.put(serverId, new RemoteState(System.currentTimeMillis(), parsed == null ? Map.of() : parsed));
        if (previous == null || !previous.rooms().equals(remote.get(serverId).rooms())) {
            bridge.server().getPlayerList().getPlayers().forEach(this::sendTo);
        }
    }

    /** A chaque synchronisation : presence aupres des autres serveurs. */
    public void heartbeat() {
        if (available()) {
            bridge.runOnMain(() -> {
                publishOccupants(true);
                removeEmptyTemporary();
            });
        }
    }

    /** Les salons temporaires vides disparaissent. Sans relais, on ne sait que ce qui se passe ici. */
    private void removeEmptyTemporary() {
        Database db = db();
        if (db == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, Map<String, String>> occupied = occupants();
        for (Room room : rooms.values()) {
            if (room.temporary() && now - room.createdAt() > TEMPORARY_GRACE_MS && !occupied.containsKey(room.name())) {
                db.async(c -> {
                    for (String sql : List.of("DELETE FROM " + db.t("voice_members") + " WHERE room = ?",
                            "DELETE FROM " + db.t("voice_rooms") + " WHERE name = ? AND temporary = 1")) {
                        try (PreparedStatement ps = c.prepareStatement(sql)) {
                            ps.setString(1, room.name());
                            ps.executeUpdate();
                        }
                    }
                    return null;
                }).thenRun(this::announce);
            }
        }
    }

    /** Depuis n'importe quel thread : plusieurs changements rapproches ne font qu'un envoi. */
    public void broadcastSoon() {
        if (broadcastPending.compareAndSet(false, true)) {
            bridge.runOnMainLater(() -> {
                broadcastPending.set(false);
                broadcast();
            }, 100);
        }
    }

    // ---------------------------------------------------------------- actions du client

    public void onAction(ServerPlayer player, TabsProtocol.RoomAction payload) {
        if (!available()) {
            return;
        }
        String name = payload.channel().toLowerCase(Locale.ROOT);
        // Pas plus de 8 actions en 2 secondes : un humain n'y arrive pas, un client modifie qui inonde la base, si.
        if (!"voice_leave".equals(payload.kind()) && tooMany(player)) {
            return;
        }
        switch (payload.kind()) {
            case "voice_join" -> join(player, name);
            case "voice_leave" -> {
                VoiceCompat.leave(player);
                broadcastSoon();
            }
            case "voice_create" -> create(player, name, payload.text());
            case "voice_delete" -> delete(player, name);
            case "voice_add" -> changeMember(player, name, payload.text(), true);
            case "voice_remove" -> changeMember(player, name, payload.text(), false);
            default -> { }
        }
    }

    private void join(ServerPlayer player, String name) {
        Room room = rooms.get(name);
        if (room == null || !mayJoin(player, room)) {
            player.sendSystemMessage(Lang.mc("voice.denied", "room", name));
            return;
        }
        switch (VoiceCompat.join(player, name)) {
            case OK -> broadcastSoon();
            case NOT_READY -> player.sendSystemMessage(Lang.mc("voice.not_ready"));
            case NOT_CONNECTED -> player.sendSystemMessage(Lang.mc("voice.not_connected"));
        }
    }

    /** {@code kind} : "public", "private" (reserves au staff) ou "temp" (tout le monde, un a la fois, disparait vide). */
    private void create(ServerPlayer player, String name, String kind) {
        Database db = db();
        boolean temporary = "temp".equals(kind);
        boolean isPrivate = "private".equals(kind);
        if (db == null || (!temporary && !isManager(player))) {
            return;
        }
        // Un nom que le filtre du chat changerait n'est pas accepte : les salons sont visibles par tous.
        if (!NAME.matcher(name).matches() || !bridge.moderation().clean(player, name).equals(name)) {
            player.sendSystemMessage(Lang.mc("voice.bad_name"));
            return;
        }
        String creator = player.getGameProfile().getName();
        db.async(c -> {
            if (temporary && !isManager(player)) {
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + db.t("voice_rooms") + " WHERE created_by = ? AND temporary = 1")) {
                    ps.setString(1, creator);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && rs.getInt(1) > 0) {
                            return "limit";
                        }
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + db.t("voice_rooms")
                    + " (name, is_private, created_by, created_at, temporary) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, name);
                ps.setBoolean(2, isPrivate);
                ps.setString(3, creator);
                ps.setLong(4, System.currentTimeMillis());
                ps.setBoolean(5, temporary);
                return ps.executeUpdate() > 0 ? "created" : "exists";
            }
        }).thenAccept(result -> {
            switch (result) {
                case "created" -> {
                    announce();
                    // Comme sur Discord : on entre dans le salon que l'on vient d'ouvrir.
                    if (temporary) {
                        bridge.runOnMainLater(() -> join(player, name), 1000);
                    }
                }
                case "limit" -> bridge.runOnMain(() -> player.sendSystemMessage(Lang.mc("voice.temp_limit")));
                default -> bridge.runOnMain(() -> player.sendSystemMessage(Lang.mc("voice.exists", "room", name)));
            }
        });
    }

    private void delete(ServerPlayer player, String name) {
        Database db = db();
        if (db == null || !isManager(player)) {
            return;
        }
        db.async(c -> {
            for (String sql : List.of("DELETE FROM " + db.t("voice_members") + " WHERE room = ?", "DELETE FROM " + db.t("voice_rooms") + " WHERE name = ?")) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, name);
                    ps.executeUpdate();
                }
            }
            return null;
        }).thenRun(this::announce);
    }

    private void changeMember(ServerPlayer player, String name, String target, boolean add) {
        Database db = db();
        if (db == null || !isManager(player) || !rooms.containsKey(name)) {
            return;
        }
        CompletableFuture<Void> done = bridge.resolvePlayer(target).thenCompose(ref -> {
            if (ref == null) {
                bridge.runOnMain(() -> player.sendSystemMessage(Lang.mc("cmd.player_unknown", "name", target)));
                return CompletableFuture.completedFuture(null);
            }
            return db.<Void>async(c -> {
                String sql = add ? "INSERT IGNORE INTO " + db.t("voice_members") + " (room, uuid) VALUES (?, ?)"
                        : "DELETE FROM " + db.t("voice_members") + " WHERE room = ? AND uuid = ?";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, name);
                    ps.setString(2, ref.uuid().toString());
                    ps.executeUpdate();
                }
                return null;
            }).thenRun(() -> {
                announce();
                bridge.runOnMain(() -> player.sendSystemMessage(Lang.mc(add ? "voice.member_added" : "voice.member_removed", "name", ref.name(), "room", name)));
            });
        });
        done.exceptionally(error -> {
            LOGGER.warn("[ArcadiaBridge] Membres du salon vocal {} : {}", name, error.toString());
            return null;
        });
    }

    /** Recharge ici et previent les autres serveurs. */
    private void announce() {
        reload();
        if (bridge.repository() != null) {
            bridge.repository().publishChat(Bridge.serverId(), "", "", "", null, RELAY);
        }
    }
}
