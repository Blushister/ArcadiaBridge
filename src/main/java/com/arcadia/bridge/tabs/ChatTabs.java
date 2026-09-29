package com.arcadia.bridge.tabs;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.chat.CrossServerChat;
import com.arcadia.bridge.config.BridgeConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Cote serveur du chat a onglets : pour les joueurs equipes d'ArcadiaChat, chaque ligne de chat part avec
 * son canal (global, local, discord) au lieu d'un simple message systeme. Les autres joueurs ne voient
 * aucune difference.
 */
public final class ChatTabs {

    /** Joueurs connectes qui se sont annonces avec ArcadiaChat. */
    private static final Set<UUID> READY = ConcurrentHashMap.newKeySet();

    private ChatTabs() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(TabsProtocol.VERSION).optional();
        // Les paquets vers le client ne sont traites que par ArcadiaChat : rien a faire ici.
        registrar.playToClient(TabsProtocol.Hello.TYPE, TabsProtocol.Hello.STREAM_CODEC, (payload, context) -> { });
        registrar.playToClient(TabsProtocol.Line.TYPE, TabsProtocol.Line.STREAM_CODEC, (payload, context) -> { });
        registrar.playToServer(TabsProtocol.Ready.TYPE, TabsProtocol.Ready.STREAM_CODEC, ChatTabs::onReady);
        registrar.playToServer(TabsProtocol.Scope.TYPE, TabsProtocol.Scope.STREAM_CODEC, ChatTabs::onScope);
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
    public static boolean send(ServerPlayer player, String channel, String origin, Component message) {
        if (!supports(player)) {
            return false;
        }
        PacketDistributor.sendToPlayer(player, new TabsProtocol.Line(channel, origin, message));
        return true;
    }

    /** Envoie une ligne a un joueur : etiquetee s'il a ArcadiaChat, en message systeme sinon. */
    public static void deliver(ServerPlayer player, String channel, String origin, Component message) {
        if (!send(player, channel, origin, message)) {
            player.sendSystemMessage(message);
        }
    }

    /** Le client s'annonce : on lui repond que ce serveur gere les onglets. */
    private static void onReady(TabsProtocol.Ready payload, IPayloadContext context) {
        Bridge bridge = Bridge.get();
        if (bridge == null || !(context.player() instanceof ServerPlayer player)) {
            return;
        }
        READY.add(player.getUUID());
        String name = BridgeConfig.SERVER_NAME.get();
        boolean crossServer = CrossServerChat.isEnabled();
        PacketDistributor.sendToPlayer(player, new TabsProtocol.Hello(name.isBlank() ? Bridge.serverId() : name,
                crossServer, crossServer && bridge.crossChat().isGlobal(player.getUUID())));
    }

    public static void onLogout(ServerPlayer player) {
        READY.remove(player.getUUID());
    }

    /** Le joueur change d'onglet d'ecriture : global ou serveur seul. */
    private static void onScope(TabsProtocol.Scope payload, IPayloadContext context) {
        Bridge bridge = Bridge.get();
        if (bridge != null && context.player() instanceof ServerPlayer player && CrossServerChat.isEnabled()
                && bridge.crossChat().isGlobal(player.getUUID()) != payload.global()) {
            bridge.crossChat().setGlobal(player.getUUID(), payload.global());
        }
    }
}
