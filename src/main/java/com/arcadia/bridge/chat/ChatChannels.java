package com.arcadia.bridge.chat;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.data.Models.ChatLine;
import com.arcadia.bridge.data.Models.ServerStatus;
import com.arcadia.bridge.data.Repository;
import com.arcadia.bridge.i18n.Lang;
import com.arcadia.bridge.minecraft.ChatGuard;
import com.arcadia.bridge.permission.Permissions;
import com.arcadia.bridge.tabs.ChatTabs;
import com.arcadia.bridge.tabs.TabsProtocol;
import com.arcadia.bridge.util.Text;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * Chats paralleles au chat public, communs a tous les serveurs : le chat du staff (avec les alertes) et les
 * canaux thematiques crees par le staff. Les lignes passent par la base comme le chat inter-serveurs.
 *
 * <p>Tout fonctionne sans ArcadiaChat (/sc, /canal say) ; avec lui, chaque chat a son onglet.
 */
public final class ChatChannels {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{2,24}");
    /** Canal interne du relais : "est en train d'ecrire" dans le chat global. */
    public static final String TYPING = "typing";
    /** Prefixe interne du relais pour un message prive : "@pseudo" (en minuscules). */
    public static final String PRIVATE_PREFIX = "@";
    /** Un message toutes les 500 ms au plus par joueur : ces chats ne passent pas par l'anti-spam du jeu. */
    private static final long MIN_INTERVAL_MS = 500;

    private final Bridge bridge;
    /** Canaux rejoints, par joueur connecte a ce serveur. */
    private final Map<UUID, Set<String>> memberships = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMessage = new ConcurrentHashMap<>();
    /** Joueurs connectes aux autres serveurs : pseudo en minuscules -> pseudo exact. */
    private volatile Map<String, String> remotePlayers = Map.of();

    public ChatChannels(Bridge bridge) {
        this.bridge = bridge;
    }

    public static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceFirst("^#", "");
    }

    public static boolean isValidName(String name) {
        return NAME.matcher(name).matches();
    }

    public static boolean staffEnabled() {
        return BridgeConfig.CHAT_STAFF.get();
    }

    public static boolean channelsEnabled() {
        return BridgeConfig.CHAT_CHANNELS.get();
    }

    public boolean isStaff(ServerPlayer player) {
        return staffEnabled() && Permissions.has(player, Permissions.STAFF_CHAT);
    }

    public List<String> channelsOf(UUID uuid) {
        return memberships.getOrDefault(uuid, Set.of()).stream().sorted().toList();
    }

    public boolean isMember(UUID uuid, String channel) {
        return memberships.getOrDefault(uuid, Set.of()).contains(channel);
    }

    // ---------------------------------------------------------------- connexion

    public void onJoin(ServerPlayer player) {
        Repository repo = bridge.repository();
        if (repo == null || !channelsEnabled()) {
            return;
        }
        UUID uuid = player.getUUID();
        repo.memberChannels(uuid).thenAccept(list -> {
            memberships.put(uuid, ConcurrentHashMap.newKeySet());
            memberships.get(uuid).addAll(list);
            bridge.runOnMain(() -> ChatTabs.sendChannels(player));
        }).exceptionally(error -> {
            LOGGER.warn("[ArcadiaBridge] Canaux de {} illisibles : {}", player.getGameProfile().getName(), error.toString());
            return null;
        });
    }

    public void onLeave(ServerPlayer player) {
        memberships.remove(player.getUUID());
        lastMessage.remove(player.getUUID());
    }

    // ---------------------------------------------------------------- envoi

    /** Message d'un membre du staff dans le chat staff. */
    public void sendStaff(ServerPlayer sender, String text) {
        if (!isStaff(sender) || !acceptMessage(sender, text)) {
            return;
        }
        String name = sender.getGameProfile().getName();
        Component line = Component.literal(Text.fill(Text.colorize(BridgeConfig.CHAT_STAFF_FORMAT.get()),
                "server", Text.stripFormatting(serverName()),
                "name", name,
                "message", Text.forMinecraft(text, BridgeConfig.CHAT_MAX_LENGTH.get())));
        LOGGER.info("[Staff] {} : {}", name, Text.stripFormatting(text));
        deliverLocal(TabsProtocol.CHANNEL_STAFF, line, this::isStaff);
        publish(name, text, line, TabsProtocol.CHANNEL_STAFF);
    }

    /** Message d'un joueur dans un canal thematique qu'il a rejoint. */
    public void sendChannel(ServerPlayer sender, String channel, String text) {
        if (!channelsEnabled()) {
            return;
        }
        if (!isMember(sender.getUUID(), channel)) {
            sender.sendSystemMessage(Lang.mc("cmd.channel.not_member", "channel", channel));
            return;
        }
        if (!acceptMessage(sender, text)) {
            return;
        }
        String name = sender.getGameProfile().getName();
        Component line = Component.literal(Text.fill(Text.colorize(BridgeConfig.CHAT_CHANNEL_FORMAT.get()),
                "channel", channel,
                "server", Text.stripFormatting(serverName()),
                "name", name,
                "message", Text.forMinecraft(text, BridgeConfig.CHAT_MAX_LENGTH.get())));
        String tag = TabsProtocol.CHANNEL_PREFIX + channel;
        deliverLocal(tag, line, p -> isMember(p.getUUID(), channel));
        publish(name, text, line, tag);
    }

    /** Alerte pour le staff de tous les serveurs (signalement, contournement de ban...). Thread quelconque. */
    public void alert(String message) {
        if (!staffEnabled()) {
            return;
        }
        Component line = Component.literal(Text.fill(Text.colorize(BridgeConfig.CHAT_ALERT_FORMAT.get()),
                "server", Text.stripFormatting(serverName()),
                "message", Text.stripFormatting(message)));
        bridge.runOnMain(() -> deliverLocal(TabsProtocol.CHANNEL_ALERT, line, this::isStaff));
        publish("", message, line, TabsProtocol.CHANNEL_ALERT);
    }

    // ---------------------------------------------------------------- messages prives entre serveurs

    /** Mise a jour a chaque synchronisation : qui est connecte ailleurs. */
    public void updateRemotePlayers(List<ServerStatus> online) {
        Map<String, String> names = new java.util.HashMap<>();
        for (ServerStatus server : online) {
            if (!server.serverId().equals(Bridge.serverId())) {
                server.names().forEach(name -> names.put(name.toLowerCase(Locale.ROOT), name));
            }
        }
        remotePlayers = Map.copyOf(names);
    }

    /** Pseudo exact d'un joueur connecte a un autre serveur, ou null. */
    public String remotePlayer(String name) {
        return remotePlayers.get(name.toLowerCase(Locale.ROOT));
    }

    /** /msg vers un joueur d'un autre serveur : meme presentation que le /msg de Minecraft. */
    public void sendPrivate(ServerPlayer sender, String target, String text) {
        if (!acceptMessage(sender, text)) {
            return;
        }
        String name = sender.getGameProfile().getName();
        String body = Text.forMinecraft(text, BridgeConfig.CHAT_MAX_LENGTH.get());
        Component outgoing = Component.translatable("commands.message.display.outgoing", target, body)
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
        Component incoming = Component.translatable("commands.message.display.incoming", name, body)
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
        ChatTabs.deliver(sender, TabsProtocol.CHANNEL_PRIVATE, target, outgoing);
        LOGGER.info("[MP] {} -> {} ({}) : {}", name, target, serverName(), Text.stripFormatting(text));
        publish(name, text, incoming, PRIVATE_PREFIX + target.toLowerCase(Locale.ROOT));
    }

    public void publishTyping(String name, boolean typing) {
        Repository repo = bridge.repository();
        if (repo != null) {
            repo.publishChat(Bridge.serverId(), name, "", typing ? "1" : "0", null, TYPING);
        }
    }

    /** Ligne venue d'un autre serveur (thread principal). */
    public void deliverRemote(ChatLine line, Component component) {
        String channel = line.channel();
        if (channel.startsWith(PRIVATE_PREFIX)) {
            ServerPlayer target = bridge.server().getPlayerList().getPlayerByName(channel.substring(PRIVATE_PREFIX.length()));
            if (target != null) {
                ChatTabs.deliver(target, TabsProtocol.CHANNEL_PRIVATE, line.playerName(), component);
            }
            return;
        }
        if (TabsProtocol.CHANNEL_STAFF.equals(channel) || TabsProtocol.CHANNEL_ALERT.equals(channel)) {
            if (staffEnabled()) {
                deliverLocal(channel, component, this::isStaff);
            }
        } else if (channel.startsWith(TabsProtocol.CHANNEL_PREFIX) && channelsEnabled()) {
            String name = channel.substring(TabsProtocol.CHANNEL_PREFIX.length());
            deliverLocal(channel, component, p -> isMember(p.getUUID(), name));
        }
    }

    private void deliverLocal(String channel, Component line, Predicate<ServerPlayer> recipients) {
        for (ServerPlayer player : bridge.server().getPlayerList().getPlayers()) {
            if (recipients.test(player)) {
                ChatTabs.deliver(player, channel, Bridge.serverId(), line);
            }
        }
    }

    private void publish(String name, String text, Component line, String channel) {
        Repository repo = bridge.repository();
        if (repo == null) {
            return;
        }
        String json = CrossServerChat.toJson(line, bridge.server().registryAccess());
        repo.publishChat(Bridge.serverId(), name, "", Text.stripFormatting(text), json, channel)
                .exceptionally(error -> {
                    LOGGER.warn("[ArcadiaBridge] Publication sur {} impossible : {}", channel, error.toString());
                    return null;
                });
    }

    /** Joueur muet, en attente de verification, message vide ou trop rapproche : refuse. */
    private boolean acceptMessage(ServerPlayer sender, String text) {
        if (text == null || text.isBlank() || ChatGuard.shouldBlock(sender)) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = lastMessage.put(sender.getUUID(), now);
        return last == null || now - last >= MIN_INTERVAL_MS;
    }

    private static String serverName() {
        String name = BridgeConfig.SERVER_NAME.get();
        return name.isBlank() ? Bridge.serverId() : name;
    }

    // ---------------------------------------------------------------- gestion des canaux (/canal)

    public CompletableFuture<Boolean> join(ServerPlayer player, String channel) {
        return bridge.repository().joinChannel(player.getUUID(), channel).thenApply(exists -> {
            if (exists) {
                memberships.computeIfAbsent(player.getUUID(), k -> ConcurrentHashMap.newKeySet()).add(channel);
                bridge.runOnMain(() -> ChatTabs.sendChannels(player));
                sendHistory(player, channel);
            }
            return exists;
        });
    }

    /** Les derniers messages du canal, pour qui vient de le rejoindre (dans son onglet avec ArcadiaChat). */
    private void sendHistory(ServerPlayer player, String channel) {
        int limit = BridgeConfig.CHAT_CHANNEL_HISTORY.get();
        if (limit <= 0) {
            return;
        }
        String tag = TabsProtocol.CHANNEL_PREFIX + channel;
        bridge.repository().channelHistory(tag, limit).thenAccept(lines -> bridge.runOnMain(() -> {
            if (lines.isEmpty() || player.hasDisconnected()) {
                return;
            }
            var registries = bridge.server().registryAccess();
            ChatTabs.deliver(player, tag, Bridge.serverId(),
                    Lang.mc("cmd.channel.history", "channel", channel, "count", lines.size()));
            for (ChatLine line : lines) {
                Component component = CrossServerChat.parse(line.formatted(), registries);
                if (component != null) {
                    ChatTabs.deliver(player, tag, line.serverId(), component);
                }
            }
        })).exceptionally(error -> {
            LOGGER.warn("[ArcadiaBridge] Historique de #{} illisible : {}", channel, error.toString());
            return null;
        });
    }

    public CompletableFuture<Void> leave(ServerPlayer player, String channel) {
        return bridge.repository().leaveChannel(player.getUUID(), channel).thenRun(() -> {
            Set<String> set = memberships.get(player.getUUID());
            if (set != null) {
                set.remove(channel);
            }
            bridge.runOnMain(() -> ChatTabs.sendChannels(player));
        });
    }

    public CompletableFuture<Boolean> create(String channel, String createdBy) {
        return bridge.repository().createChannel(channel, createdBy);
    }

    /** Supprime le canal ; les membres connectes ici le perdent tout de suite, les autres a leur reconnexion. */
    public CompletableFuture<Boolean> delete(String channel) {
        return bridge.repository().deleteChannel(channel).thenApply(existed -> {
            memberships.values().forEach(set -> set.remove(channel));
            bridge.runOnMain(() -> bridge.server().getPlayerList().getPlayers().forEach(ChatTabs::sendChannels));
            return existed;
        });
    }

    public CompletableFuture<List<String>> list() {
        return bridge.repository().listChannels();
    }
}
