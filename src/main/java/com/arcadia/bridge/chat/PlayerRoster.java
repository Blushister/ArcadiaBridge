package com.arcadia.bridge.chat;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.compat.LuckPermsHook;
import com.arcadia.bridge.tabs.ChatTabs;
import com.arcadia.bridge.tabs.TabsProtocol;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Grade et absence des joueurs connectes, sur tous les serveurs : la colonne des joueurs de l'ecran des salons les range par
 * grade et signale ceux qui sont absents. Chaque serveur publie les siens par le relais du chat ; rien n'est ecrit en base.
 */
public final class PlayerRoster {

    /** Canal interne du relais : les joueurs d'un serveur (JSON dans la ligne mise en forme). */
    public static final String RELAY = "player_roster";
    /** Sans bouger ni ecrire pendant ce temps, un joueur est absent. */
    private static final long AFK_MS = 5 * 60_000L;
    private static final long REMOTE_TTL_MS = 60_000L;
    private static final Type LIST = new TypeToken<List<TabsProtocol.RosterEntry>>() { }.getType();

    private record Activity(double x, double y, double z, float yaw, float pitch, long since) {
    }

    private record RemoteState(long at, List<TabsProtocol.RosterEntry> entries) {
    }

    private final Bridge bridge;
    private final Map<UUID, Activity> activity = new HashMap<>();
    private final Map<String, RemoteState> remote = new ConcurrentHashMap<>();
    private List<TabsProtocol.RosterEntry> local = List.of();
    private List<TabsProtocol.RosterEntry> sent = List.of();
    private String lastPublished = "";

    public PlayerRoster(Bridge bridge) {
        this.bridge = bridge;
    }

    /** Thread principal, chaque seconde : qui a bouge depuis la derniere fois. */
    public void tick() {
        long now = System.currentTimeMillis();
        List<TabsProtocol.RosterEntry> entries = new ArrayList<>();
        for (ServerPlayer player : bridge.server().getPlayerList().getPlayers()) {
            Activity last = activity.get(player.getUUID());
            boolean moved = last == null || last.x() != player.getX() || last.y() != player.getY() || last.z() != player.getZ()
                    || last.yaw() != player.getYRot() || last.pitch() != player.getXRot();
            long since = moved ? now : last.since();
            activity.put(player.getUUID(), new Activity(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(), since));
            entries.add(new TabsProtocol.RosterEntry(player.getGameProfile().getName(), LuckPermsHook.prefix(player.getUUID()),
                    LuckPermsHook.weight(player.getUUID()), now - since > AFK_MS));
        }
        if (!entries.equals(local)) {
            local = List.copyOf(entries);
            publish(false);
            broadcast();
        }
    }

    /** Un joueur qui ecrit n'est pas absent, meme immobile. */
    public void onActivity(ServerPlayer player) {
        Activity last = activity.get(player.getUUID());
        if (last != null) {
            activity.put(player.getUUID(), new Activity(last.x(), last.y(), last.z(), last.yaw(), last.pitch(), System.currentTimeMillis()));
        }
    }

    public void onLeave(ServerPlayer player) {
        activity.remove(player.getUUID());
    }

    /** A chaque synchronisation : les autres serveurs savent qu'on est toujours la. */
    public void heartbeat() {
        bridge.runOnMain(() -> publish(true));
    }

    private void publish(boolean force) {
        if (bridge.repository() == null) {
            return;
        }
        String text = new Gson().toJson(local);
        if (force || !text.equals(lastPublished)) {
            lastPublished = text;
            bridge.repository().publishChat(Bridge.serverId(), "", "", "", text, RELAY);
        }
    }

    /** Joueurs annonces par un autre serveur (thread principal). */
    public void onRemote(String serverId, String json) {
        if (json == null || Bridge.serverId().equals(serverId)) {
            return;
        }
        List<TabsProtocol.RosterEntry> parsed;
        try {
            parsed = new Gson().fromJson(json, LIST);
        } catch (RuntimeException e) {
            return;
        }
        remote.put(serverId, new RemoteState(System.currentTimeMillis(), parsed == null ? List.of() : List.copyOf(parsed)));
        broadcast();
    }

    private List<TabsProtocol.RosterEntry> merged() {
        long now = System.currentTimeMillis();
        remote.values().removeIf(state -> now - state.at() > REMOTE_TTL_MS);
        Map<String, TabsProtocol.RosterEntry> all = new LinkedHashMap<>();
        local.forEach(entry -> all.put(entry.name().toLowerCase(Locale.ROOT), entry));
        remote.values().forEach(state -> state.entries().forEach(entry -> all.putIfAbsent(entry.name().toLowerCase(Locale.ROOT), entry)));
        return List.copyOf(all.values());
    }

    private void broadcast() {
        List<TabsProtocol.RosterEntry> list = merged();
        if (list.equals(sent)) {
            return;
        }
        sent = list;
        TabsProtocol.Roster payload = new TabsProtocol.Roster(list);
        for (ServerPlayer player : bridge.server().getPlayerList().getPlayers()) {
            if (ChatTabs.supportsRooms(player)) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    public void sendTo(ServerPlayer player) {
        if (ChatTabs.supportsRooms(player)) {
            PacketDistributor.sendToPlayer(player, new TabsProtocol.Roster(merged()));
        }
    }
}
