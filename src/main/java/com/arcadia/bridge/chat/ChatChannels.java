package com.arcadia.bridge.chat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
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
import com.arcadia.bridge.data.Models.ChannelInfo;
import com.arcadia.bridge.data.Models.ChatLine;
import com.arcadia.bridge.data.Models.JoinResult;
import com.arcadia.bridge.data.Models.Mail;
import com.arcadia.bridge.data.Models.PlayerRef;
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
    /**
     * Canal interne du relais : un joueur a ete ajoute a un canal ou retire ("uuid|canal|1" ou "uuid|canal|0"),
     * ou un canal a ete supprime ("*|canal|0"). Les autres serveurs rechargent alors les canaux concernes.
     */
    public static final String MEMBERSHIP = "membership";
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
        reloadMemberships(player);
    }

    /** Relit en base les canaux d'un joueur connecte ici, et met a jour ses onglets. */
    private CompletableFuture<Void> reloadMemberships(ServerPlayer player) {
        UUID uuid = player.getUUID();
        return bridge.repository().memberChannels(uuid).thenAccept(list -> {
            Set<String> set = ConcurrentHashMap.newKeySet();
            set.addAll(list);
            memberships.put(uuid, set);
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
        deliverLocal(TabsProtocol.CHANNEL_STAFF, Bridge.serverId(), name, line, this::isStaff);
        publish(name, text, line, TabsProtocol.CHANNEL_STAFF);
    }
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
        deliverLocal(tag, Bridge.serverId(), name, line, p -> isMember(p.getUUID(), channel));
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
        bridge.runOnMain(() -> deliverLocal(TabsProtocol.CHANNEL_ALERT, Bridge.serverId(), "", line, this::isStaff));
        publish("", message, line, TabsProtocol.CHANNEL_ALERT);
    }

    // ---------------------------------------------------------------- messages prives entre serveurs

    /** Mise a jour a chaque synchronisation : qui est connecte ailleurs. */
    public void updateRemotePlayers(List<ServerStatus> online) {
        Map<String, String> names = new HashMap<>();
        for (ServerStatus server : online) {
            if (!server.serverId().equals(Bridge.serverId())) {
                server.names().forEach(name -> names.put(name.toLowerCase(Locale.ROOT), name));
            }
        }
        remotePlayers = Map.copyOf(names);
        List<String> list = List.copyOf(names.values());
        bridge.runOnMain(() -> ChatTabs.updateRemotePlayers(list));
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
        ChatTabs.deliver(sender, TabsProtocol.CHANNEL_PRIVATE, target, name, outgoing);
        LOGGER.info("[MP] {} -> {} ({}) : {}", name, target, serverName(), Text.stripFormatting(text));
        publish(name, text, incoming, PRIVATE_PREFIX + target.toLowerCase(Locale.ROOT));
    }

    // ---------------------------------------------------------------- messages hors ligne

    private static final DateTimeFormatter MAIL_DATE =
            DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault());

    /** /msg a un joueur hors ligne : le message l'attend, et il est prevenu sur Discord si son compte est lie. */
    public void sendOffline(ServerPlayer sender, PlayerRef target, String text) {
        Repository repo = bridge.repository();
        if (repo == null || !acceptMessage(sender, text)) {
            return;
        }
        String name = sender.getGameProfile().getName();
        String body = Text.forMinecraft(text, BridgeConfig.CHAT_MAX_LENGTH.get());
        repo.addMail(target.uuid(), name, serverName(), body, BridgeConfig.CHAT_OFFLINE_MAX.get())
                .thenAccept(stored -> bridge.runOnMain(() -> {
                    if (!stored) {
                        sender.sendSystemMessage(Lang.mc("mail.full", "name", target.name()));
                        return;
                    }
                    Component outgoing = Component.translatable("commands.message.display.outgoing", target.name(), body)
                            .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)
                            .append(Lang.mc("mail.offline_tag"));
                    ChatTabs.deliver(sender, TabsProtocol.CHANNEL_PRIVATE, target.name(), name, outgoing);
                    LOGGER.info("[MP hors ligne] {} -> {} : {}", name, target.name(), Text.stripFormatting(text));
                    notifyOnDiscord(target, name, body);
                }))
                .exceptionally(error -> {
                    LOGGER.warn("[ArcadiaBridge] Message hors ligne non enregistre : {}", error.toString());
                    bridge.runOnMain(() -> sender.sendSystemMessage(Lang.mc("common.error")));
                    return null;
                });
    }

    private void notifyOnDiscord(PlayerRef target, String from, String body) {
        if (!BridgeConfig.CHAT_OFFLINE_DISCORD.get() || !bridge.discord().isReady()) {
            return;
        }
        bridge.cache().link(target.uuid()).ifPresent(link -> bridge.discord().sendDirectMessage(link.discordId(),
                Lang.get("mail.discord", "name", Text.forDiscord(from), "server", Text.forDiscord(serverName()),
                        "message", Text.forDiscord(body))));
    }

    /** A la connexion : les messages recus pendant l'absence du joueur. */
    public void deliverMail(ServerPlayer player) {
        Repository repo = bridge.repository();
        if (repo == null || !BridgeConfig.CHAT_OFFLINE_MESSAGES.get()) {
            return;
        }
        repo.takeMail(player.getUUID()).thenAccept(list -> {
            if (list.isEmpty()) {
                return;
            }
            bridge.runOnMain(() -> {
                if (player.hasDisconnected()) {
                    return;
                }
                ChatTabs.deliver(player, TabsProtocol.CHANNEL_PRIVATE, "", "", Lang.mc("mail.header", "count", list.size()));
                for (Mail mail : list) {
                    Component line = Component.translatable("commands.message.display.incoming", mail.fromName(),
                                    mail.message()).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)
                            .append(Lang.mc("mail.date", "date", MAIL_DATE.format(Instant.ofEpochMilli(
                                    mail.createdAt())), "server", mail.fromServer()));
                    ChatTabs.deliver(player, TabsProtocol.CHANNEL_PRIVATE, mail.fromName(), mail.fromName(), line);
                }
            });
        }).exceptionally(error -> {
            LOGGER.warn("[ArcadiaBridge] Messages hors ligne de {} illisibles : {}", player.getGameProfile().getName(),
                    error.toString());
            return null;
        });
    }
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
                ChatTabs.deliver(target, TabsProtocol.CHANNEL_PRIVATE, line.playerName(), line.playerName(), component);
            }
            return;
        }
        if (TabsProtocol.CHANNEL_STAFF.equals(channel) || TabsProtocol.CHANNEL_ALERT.equals(channel)) {
            if (staffEnabled()) {
                deliverLocal(channel, line.serverId(), line.playerName(), component, this::isStaff);
            }
        } else if (channel.startsWith(TabsProtocol.CHANNEL_PREFIX) && channelsEnabled()) {
            String name = channel.substring(TabsProtocol.CHANNEL_PREFIX.length());
            deliverLocal(channel, line.serverId(), line.playerName(), component, p -> isMember(p.getUUID(), name));
        }
    }

    private void deliverLocal(String channel, String origin, String sender, Component line,
                              Predicate<ServerPlayer> recipients) {
        for (ServerPlayer player : bridge.server().getPlayerList().getPlayers()) {
            if (recipients.test(player)) {
                ChatTabs.deliver(player, channel, origin, sender, line);
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

    /** Le joueur rejoint un canal public (un canal prive le refuse). */
    public CompletableFuture<JoinResult> join(ServerPlayer player, String channel) {
        return bridge.repository().joinChannel(player.getUUID(), channel, false).thenApply(result -> {
            if (result == JoinResult.JOINED) {
                memberships.computeIfAbsent(player.getUUID(), k -> ConcurrentHashMap.newKeySet()).add(channel);
                bridge.runOnMain(() -> ChatTabs.sendChannels(player));
                sendHistory(player, channel);
            }
            return result;
        });
    }

    /**
     * Ajout d'office par le staff, y compris dans un canal prive. Le joueur est prevenu s'il est connecte,
     * sur ce serveur ou sur un autre.
     */
    public CompletableFuture<JoinResult> add(PlayerRef target, String channel) {
        return bridge.repository().joinChannel(target.uuid(), channel, true).thenApply(result -> {
            if (result == JoinResult.JOINED) {
                announceMembership(target.uuid().toString(), channel, true);
            }
            return result;
        });
    }

    /** Retrait par le staff. */
    public CompletableFuture<Void> kick(PlayerRef target, String channel) {
        return bridge.repository().leaveChannel(target.uuid(), channel)
                .thenRun(() -> announceMembership(target.uuid().toString(), channel, false));
    }

    public CompletableFuture<List<String>> members(String channel) {
        return bridge.repository().channelMembers(channel);
    }

    /** @return false si le canal n'existe pas */
    public CompletableFuture<Boolean> setPrivate(String channel, boolean isPrivate) {
        return bridge.repository().setChannelPrivate(channel, isPrivate);
    }

    /** Applique un changement de membres ici, et le transmet aux autres serveurs. */
    private void announceMembership(String who, String channel, boolean added) {
        String event = who + "|" + channel + "|" + (added ? "1" : "0");
        onMembershipEvent(event);
        Repository repo = bridge.repository();
        if (repo != null) {
            repo.publishChat(Bridge.serverId(), "", "", event, null, MEMBERSHIP);
        }
    }

    /** Changement de membres (de ce serveur ou d'un autre) : on recharge les joueurs concernes connectes ici. */
    public void onMembershipEvent(String event) {
        String[] parts = event.split("\\|");
        if (parts.length != 3) {
            return;
        }
        String channel = parts[1];
        boolean added = "1".equals(parts[2]);
        bridge.runOnMain(() -> {
            for (ServerPlayer player : bridge.server().getPlayerList().getPlayers()) {
                if (!"*".equals(parts[0]) && !player.getUUID().toString().equals(parts[0])) {
                    continue;
                }
                reloadMemberships(player).thenRun(() -> {
                    if ("*".equals(parts[0])) {
                        return;
                    }
                    bridge.runOnMain(() -> player.sendSystemMessage(
                            Lang.mc(added ? "cmd.channel.added_you" : "cmd.channel.removed_you", "channel", channel)));
                    if (added) {
                        sendHistory(player, channel);
                    }
                });
            }
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
            ChatTabs.deliver(player, tag, Bridge.serverId(), "",
                    Lang.mc("cmd.channel.history", "channel", channel, "count", lines.size()));
            for (ChatLine line : lines) {
                Component component = CrossServerChat.parse(line.formatted(), registries);
                if (component != null) {
                    ChatTabs.deliver(player, tag, line.serverId(), line.playerName(), component);
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

    public CompletableFuture<Boolean> create(String channel, String createdBy, boolean isPrivate) {
        return bridge.repository().createChannel(channel, createdBy, isPrivate);
    }

    /** Supprime le canal ; ses membres le perdent tout de suite, sur tous les serveurs. */
    public CompletableFuture<Boolean> delete(String channel) {
        return bridge.repository().deleteChannel(channel).thenApply(existed -> {
            memberships.values().forEach(set -> set.remove(channel));
            announceMembership("*", channel, false);
            return existed;
        });
    }

    public CompletableFuture<List<ChannelInfo>> list() {
        return bridge.repository().listChannelInfos();
    }
}
