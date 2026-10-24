package com.arcadia.bridge.tabs;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.chat.ChatChannels;
import com.arcadia.bridge.chat.CrossServerChat;
import com.arcadia.bridge.compat.FtbTeamsCompat;
import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.data.Models.ServerStatus;
import com.arcadia.bridge.permission.Permissions;
import com.arcadia.bridge.util.Text;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Cote serveur du chat a onglets : pour les joueurs equipes d'ArcadiaChat, chaque ligne de chat part avec
 * son canal (global, local, discord, prive, staff, canaux) au lieu d'un simple message systeme. Les autres
 * joueurs ne voient aucune difference.
 */
public final class ChatTabs {

    /** Joueurs connectes qui se sont annonces avec ArcadiaChat. */
    private static final Set<UUID> READY = ConcurrentHashMap.newKeySet();
    /** Dernier "est en train d'ecrire" relaye, par joueur : limite le debit. */
    private static final Map<UUID, Long> TYPING_SENT = new ConcurrentHashMap<>();
    private static final long TYPING_MIN_INTERVAL_MS = 1000;

    /** Auteur du message d'equipe FTB Teams en cours de diffusion (thread principal), ou null. */
    private static String teamSender;

    public static boolean teamTabAvailable() {
        return BridgeConfig.CHAT_TEAM.get() && FtbTeamsCompat.isLoaded();
    }

    public static void beginTeamMessage(UUID from) {
        Bridge bridge = Bridge.get();
        ServerPlayer author = bridge == null ? null : bridge.server().getPlayerList().getPlayer(from);
        teamSender = author == null ? "" : author.getGameProfile().getName();
    }

    public static void endTeamMessage() {
        teamSender = null;
    }

    /**
     * Ligne systeme envoyee a un joueur : pendant un message d'equipe FTB Teams, elle part dans l'onglet Equipe.
     *
     * @return true si elle a ete envoyee ainsi (le message systeme habituel est alors inutile)
     */
    public static boolean tagTeamLine(ServerPlayer player, Component line) {
        return teamSender != null && teamTabAvailable()
                && send(player, TabsProtocol.CHANNEL_TEAM, Bridge.serverId(), teamSender, line);
    }

    /** Derniere liste de serveurs envoyee aux clients. */
    private static volatile List<TabsProtocol.ServerInfo> servers = List.of();
    /** Joueurs connectes aux autres serveurs, envoyes aux clients pour la completion des pseudos. */
    private static volatile List<String> remotePlayers = List.of();

    private ChatTabs() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(TabsProtocol.VERSION).optional();
        // Les paquets vers le client ne sont traites que par ArcadiaChat : rien a faire ici.
        registrar.playToClient(TabsProtocol.Hello.TYPE, TabsProtocol.Hello.STREAM_CODEC, (payload, context) -> { });
        registrar.playToClient(TabsProtocol.Servers.TYPE, TabsProtocol.Servers.STREAM_CODEC, (payload, context) -> { });
        registrar.playToClient(TabsProtocol.Channels.TYPE, TabsProtocol.Channels.STREAM_CODEC, (payload, context) -> { });
        registrar.playToClient(TabsProtocol.Line.TYPE, TabsProtocol.Line.STREAM_CODEC, (payload, context) -> { });
        registrar.playToClient(TabsProtocol.TypingState.TYPE, TabsProtocol.TypingState.STREAM_CODEC,
                (payload, context) -> { });
        registrar.playToServer(TabsProtocol.Ready.TYPE, TabsProtocol.Ready.STREAM_CODEC, ChatTabs::onReady);
        registrar.playToServer(TabsProtocol.Scope.TYPE, TabsProtocol.Scope.STREAM_CODEC, ChatTabs::onScope);
        registrar.playToServer(TabsProtocol.Send.TYPE, TabsProtocol.Send.STREAM_CODEC, ChatTabs::onSend);
        registrar.playToServer(TabsProtocol.Typing.TYPE, TabsProtocol.Typing.STREAM_CODEC, ChatTabs::onTyping);
        registrar.playToClient(TabsProtocol.Reaction.TYPE, TabsProtocol.Reaction.STREAM_CODEC, (payload, context) -> { });
        registrar.playToServer(TabsProtocol.React.TYPE, TabsProtocol.React.STREAM_CODEC, ChatTabs::onReact);
        registrar.playToClient(TabsProtocol.RemotePlayers.TYPE, TabsProtocol.RemotePlayers.STREAM_CODEC,
                (payload, context) -> { });
        registrar.playToClient(TabsProtocol.ChannelDirectory.TYPE, TabsProtocol.ChannelDirectory.STREAM_CODEC,
                (payload, context) -> { });
        registrar.playToServer(TabsProtocol.ChannelsRequest.TYPE, TabsProtocol.ChannelsRequest.STREAM_CODEC,
                ChatTabs::onChannelsRequest);
    }

    /**
     * Le joueur a-t-il ArcadiaChat ? Il faut qu'il se soit annonce : les canaux seuls ne suffisent pas, un
     * autre mod present sur son client pourrait les declarer sans savoir afficher le chat etiquete.
     */
    public static boolean supports(ServerPlayer player) {
        return READY.contains(player.getUUID());
    }

    /**
     * Envoie une ligne de chat avec son canal si le joueur a ArcadiaChat.
     *
     * @return false si le joueur n'a pas le mod : l'appelant envoie alors le message habituel
     */
    public static boolean send(ServerPlayer player, String channel, String origin, String sender, Component message) {
        if (!supports(player)) {
            return false;
        }
        PacketDistributor.sendToPlayer(player, new TabsProtocol.Line(channel, origin, sender == null ? "" : sender, message));
        return true;
    }

    /** Derniere ligne analysee par {@link #senderOf} : LPC envoie la meme ligne a chaque joueur, une a une. */
    private static Component lastLine;
    private static String lastSender = "";

    /**
     * Auteur d'une ligne de chat deja mise en forme (LPC ne le transmet pas) : le joueur connecte dont le pseudo
     * apparait avant le premier ":" (le plus long s'il y en a plusieurs), ou vide. Calcule une fois par ligne.
     */
    public static String senderOf(ServerPlayer anyPlayer, Component line) {
        return senderOf(anyPlayer.server, line);
    }

    public static String senderOf(net.minecraft.server.MinecraftServer server, Component line) {
        if (line == lastLine) {
            return lastSender;
        }
        String text = line.getString();
        int colon = text.indexOf(':');
        String head = colon < 0 ? text : text.substring(0, colon);
        String found = "";
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String name = player.getGameProfile().getName();
            if (name.length() > found.length() && containsWord(head, name)) {
                found = name;
            }
        }
        lastLine = line;
        lastSender = found;
        return found;
    }

    /** {@code word} present dans {@code text}, sans lettre, chiffre ni "_" colle de part et d'autre. */
    private static boolean containsWord(String text, String word) {
        for (int i = text.indexOf(word); i >= 0; i = text.indexOf(word, i + 1)) {
            int end = i + word.length();
            if ((i == 0 || !isNameChar(text.charAt(i - 1))) && (end == text.length() || !isNameChar(text.charAt(end)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Envoie une ligne a un joueur : etiquetee s'il a ArcadiaChat, en message systeme sinon. */
    public static void deliver(ServerPlayer player, String channel, String origin, String sender, Component message) {
        if (!send(player, channel, origin, sender, message)) {
            player.sendSystemMessage(message);
        }
    }

    // ---------------------------------------------------------------- connexion

    /** Le client s'annonce : on lui decrit ce serveur, les serveurs en ligne et ses canaux. */
    private static void onReady(TabsProtocol.Ready payload, IPayloadContext context) {
        Bridge bridge = Bridge.get();
        if (bridge == null || !(context.player() instanceof ServerPlayer player)) {
            return;
        }
        READY.add(player.getUUID());
        String name = BridgeConfig.SERVER_NAME.get();
        boolean crossServer = CrossServerChat.isEnabled();
        PacketDistributor.sendToPlayer(player, new TabsProtocol.Hello(Bridge.serverId(),
                name.isBlank() ? Bridge.serverId() : name, crossServer,
                crossServer && bridge.crossChat().isGlobal(player.getUUID()),
                bridge.channels().isStaff(player), BridgeConfig.CHAT_TYPING.get(), teamTabAvailable()));
        PacketDistributor.sendToPlayer(player, new TabsProtocol.Servers(servers));
        PacketDistributor.sendToPlayer(player, new TabsProtocol.RemotePlayers(remotePlayers));
        sendChannels(player);
    }

    public static void onLogout(ServerPlayer player) {
        READY.remove(player.getUUID());
        if (TYPING_SENT.remove(player.getUUID()) != null) {
            broadcastTyping(player, false);
            Bridge bridge = Bridge.get();
            if (bridge != null && CrossServerChat.isEnabled()) {
                bridge.channels().publishTyping(player.getGameProfile().getName(), false);
            }
        }
    }

    /** Canaux thematiques du joueur (a la connexion et a chaque changement). */
    public static void sendChannels(ServerPlayer player) {
        Bridge bridge = Bridge.get();
        if (bridge != null && supports(player) && ChatChannels.channelsEnabled()) {
            PacketDistributor.sendToPlayer(player, new TabsProtocol.Channels(bridge.channels().channelsOf(player.getUUID())));
        }
    }

    /** Serveurs en ligne, mis a jour a chaque synchronisation ; envoyes aux clients seulement s'ils changent. */
    public static void updateServers(List<ServerStatus> online) {
        List<TabsProtocol.ServerInfo> list = online.stream()
                .map(st -> new TabsProtocol.ServerInfo(st.serverId(),
                        st.displayName() == null || st.displayName().isBlank() ? st.serverId() : st.displayName(),
                        st.address() == null ? "" : st.address()))
                .toList();
        if (list.equals(servers)) {
            return;
        }
        servers = list;
        Bridge bridge = Bridge.get();
        if (bridge == null) {
            return;
        }
        for (ServerPlayer player : bridge.server().getPlayerList().getPlayers()) {
            if (supports(player)) {
                PacketDistributor.sendToPlayer(player, new TabsProtocol.Servers(list));
            }
        }
    }

    /** Joueurs des autres serveurs, mis a jour a chaque synchronisation ; envoyes seulement s'ils changent. */
    public static void updateRemotePlayers(List<String> names) {
        List<String> sorted = names.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        if (sorted.equals(remotePlayers)) {
            return;
        }
        remotePlayers = sorted;
        Bridge bridge = Bridge.get();
        if (bridge == null) {
            return;
        }
        var payload = new TabsProtocol.RemotePlayers(sorted);
        for (ServerPlayer player : bridge.server().getPlayerList().getPlayers()) {
            if (supports(player)) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    // ---------------------------------------------------------------- messages du client

    /** Le joueur change d'onglet d'ecriture : global ou serveur seul. */
    private static void onScope(TabsProtocol.Scope payload, IPayloadContext context) {
        Bridge bridge = Bridge.get();
        if (bridge != null && context.player() instanceof ServerPlayer player && CrossServerChat.isEnabled()
                && bridge.crossChat().isGlobal(player.getUUID()) != payload.global()) {
            bridge.crossChat().setGlobal(player.getUUID(), payload.global());
        }
    }

    /** Message ecrit dans l'onglet Staff ou dans un canal thematique. */
    private static void onSend(TabsProtocol.Send payload, IPayloadContext context) {
        Bridge bridge = Bridge.get();
        if (bridge == null || !(context.player() instanceof ServerPlayer player)) {
            return;
        }
        String text = Text.stripFormatting(payload.text()).trim();
        if (TabsProtocol.CHANNEL_STAFF.equals(payload.channel())) {
            bridge.channels().sendStaff(player, text);
        } else if (TabsProtocol.CHANNEL_TEAM.equals(payload.channel())) {
            bridge.channels().sendTeam(player, text);
        } else if (payload.channel().startsWith(TabsProtocol.CHANNEL_PREFIX)) {
            bridge.channels().sendChannel(player, ChatChannels.normalize(payload.channel()), text);
        }
    }

    /**
     * Ecran Canaux d'ArcadiaChat : les canaux que le joueur peut voir (publics, prives dont il est membre ;
     * tous pour le staff), et s'il peut les gerer.
     */
    private static void onChannelsRequest(TabsProtocol.ChannelsRequest payload, IPayloadContext context) {
        Bridge bridge = Bridge.get();
        if (bridge == null || bridge.repository() == null || !ChatChannels.channelsEnabled()
                || !(context.player() instanceof ServerPlayer player)) {
            return;
        }
        boolean manager = Permissions.has(player, Permissions.CHANNELS_MANAGE);
        List<String> mine = bridge.channels().channelsOf(player.getUUID());
        bridge.channels().list().thenAccept(infos -> {
            List<TabsProtocol.ChannelEntry> entries = infos.stream()
                    .filter(info -> !info.isPrivate() || manager || mine.contains(info.name()))
                    .map(info -> new TabsProtocol.ChannelEntry(info.name(), info.isPrivate(), mine.contains(info.name())))
                    .toList();
            bridge.runOnMain(() -> {
                if (!player.hasDisconnected()) {
                    PacketDistributor.sendToPlayer(player, new TabsProtocol.ChannelDirectory(entries, manager));
                }
            });
        });
    }

    /** Reactions possibles (le client choisit l'apparence : emoji Emojiful ou symbole). */
    private static final Set<String> EMOJIS = Set.of("like", "laugh", "love", "wow", "sad");
    private static final Map<UUID, Long> REACTED = new ConcurrentHashMap<>();
    private static final long REACT_MIN_INTERVAL_MS = 300;

    /** Un joueur reagit a un message : relaye a tous les joueurs equipes d'ArcadiaChat, sur tous les serveurs. */
    private static void onReact(TabsProtocol.React payload, IPayloadContext context) {
        Bridge bridge = Bridge.get();
        if (bridge == null || !(context.player() instanceof ServerPlayer player) || !EMOJIS.contains(payload.emoji())
                || payload.key().isBlank() || !payload.key().matches("[0-9a-f]{8,64}")) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = REACTED.put(player.getUUID(), now);
        if (last != null && now - last < REACT_MIN_INTERVAL_MS) {
            return;
        }
        String name = player.getGameProfile().getName();
        deliverReaction(payload.key(), payload.emoji(), name, payload.add());
        bridge.channels().publishReaction(payload.key(), payload.emoji(), name, payload.add());
    }

    /** Reaction (de ce serveur ou d'un autre) envoyee aux joueurs d'ici equipes d'ArcadiaChat. */
    public static void deliverReaction(String key, String emoji, String reactor, boolean add) {
        Bridge bridge = Bridge.get();
        if (bridge == null || !EMOJIS.contains(emoji)) {
            return;
        }
        var payload = new TabsProtocol.Reaction(key, emoji, reactor, add);
        for (ServerPlayer player : bridge.server().getPlayerList().getPlayers()) {
            if (supports(player)) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    /** "Est en train d'ecrire" : relaye aux autres joueurs du serveur equipes d'ArcadiaChat. */
    private static void onTyping(TabsProtocol.Typing payload, IPayloadContext context) {
        if (!BridgeConfig.CHAT_TYPING.get() || !(context.player() instanceof ServerPlayer player)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (payload.typing()) {
            Long last = TYPING_SENT.get(player.getUUID());
            if (last != null && now - last < TYPING_MIN_INTERVAL_MS) {
                return;
            }
            TYPING_SENT.put(player.getUUID(), now);
        } else if (TYPING_SENT.remove(player.getUUID()) == null) {
            return;
        }
        broadcastTyping(player, payload.typing());
        // Dans le chat global, les autres serveurs le voient aussi ecrire.
        Bridge bridge = Bridge.get();
        if (bridge != null && CrossServerChat.isEnabled() && bridge.crossChat().isGlobal(player.getUUID())) {
            bridge.channels().publishTyping(player.getGameProfile().getName(), payload.typing());
        }
    }

    /** Un joueur d'un autre serveur ecrit dans le chat global. */
    public static void remoteTyping(String name, boolean typing) {
        Bridge bridge = Bridge.get();
        if (bridge == null) {
            return;
        }
        var state = new TabsProtocol.TypingState(name, typing, true);
        for (ServerPlayer player : bridge.server().getPlayerList().getPlayers()) {
            if (supports(player) && bridge.crossChat().isGlobal(player.getUUID())) {
                PacketDistributor.sendToPlayer(player, state);
            }
        }
    }

    private static void broadcastTyping(ServerPlayer player, boolean typing) {
        Bridge bridge = Bridge.get();
        if (bridge == null) {
            return;
        }
        var state = new TabsProtocol.TypingState(player.getGameProfile().getName(), typing, false);
        for (ServerPlayer other : bridge.server().getPlayerList().getPlayers()) {
            if (other != player && supports(other)) {
                PacketDistributor.sendToPlayer(other, state);
            }
        }
    }
}
