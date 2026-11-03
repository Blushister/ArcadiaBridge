package com.arcadia.bridge.teleport;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.data.Models.PendingTeleport;
import com.arcadia.bridge.data.Models.ServerStatus;
import com.arcadia.bridge.data.Repository;
import com.arcadia.bridge.i18n.Lang;
import com.mojang.logging.LogUtils;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;

/**
 * /tp vers un joueur d'un autre serveur : le joueur change de serveur (paquet de transfert de Minecraft, il faut
 * accepts-transfers=true sur la destination), puis arrive sur le joueur vise. S'il etait spectateur, il le reste.
 *
 * <p>Aucune permission propre : la commande ne passe ici que si le /tp du joueur a ete accepte par Minecraft, donc par
 * LuckPerms. Les serveurs exclus (event, test...) ne servent ni de depart ni d'arrivee.
 */
public final class CrossServerTeleport {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long EXPIRES_MS = TimeUnit.MINUTES.toMillis(1);
    private static final long MIN_INTERVAL_MS = 3000;
    private static final int DEFAULT_PORT = 25565;
    /** Le temps que le client finisse d'arriver avant de le deplacer. */
    private static final long ARRIVAL_DELAY_MS = 1000;
    private static final Map<UUID, Long> LAST_REQUEST = new ConcurrentHashMap<>();

    private CrossServerTeleport() {
    }

    public static boolean enabled() {
        return BridgeConfig.TELEPORT_ENABLED.get();
    }

    public static boolean excluded(String serverId) {
        return BridgeConfig.TELEPORT_EXCLUDED.get().stream().anyMatch(id -> id.equalsIgnoreCase(serverId));
    }

    /**
     * Pseudo vise par un /tp que ce service doit prendre en charge : "tp Pseudo" ou "tp MonPseudo Pseudo", vers un
     * joueur absent d'ici mais connecte ailleurs. Sinon null : le /tp de Minecraft suit son cours.
     */
    public static String remoteTarget(Bridge bridge, ServerPlayer player, String command) {
        String[] parts = command.trim().split(" +");
        if (parts.length < 2 || parts.length > 3 || !isTeleportCommand(parts[0])) {
            return null;
        }
        if (parts.length == 3 && !parts[1].equalsIgnoreCase(player.getGameProfile().getName())) {
            return null;
        }
        String name = parts[parts.length - 1];
        if (!name.matches("[A-Za-z0-9_]{1,16}") || bridge.server().getPlayerList().getPlayerByName(name) != null) {
            return null;
        }
        return bridge.channels().remotePlayer(name);
    }

    public static boolean isTeleportCommand(String root) {
        String name = root.toLowerCase(Locale.ROOT);
        return name.equals("tp") || name.equals("teleport");
    }

    /** Cherche le serveur du joueur vise, enregistre l'arrivee, puis envoie le joueur la-bas. */
    public static void request(Bridge bridge, ServerPlayer player, String target) {
        Repository repo = bridge.repository();
        long now = System.currentTimeMillis();
        Long last = LAST_REQUEST.put(player.getUUID(), now);
        if (repo == null || (last != null && now - last < MIN_INTERVAL_MS)) {
            return;
        }
        boolean spectator = player.isSpectator();
        repo.loadServers().thenAccept(servers -> {
            ServerStatus destination = serverOf(servers, target, now);
            String refused = refusal(destination, target);
            if (refused != null) {
                bridge.runOnMain(() -> player.sendSystemMessage(Lang.mc(refused, "name", target,
                        "server", destination == null ? "" : destination.label())));
                return;
            }
            repo.savePendingTeleport(new PendingTeleport(player.getUUID(), target, spectator, now + EXPIRES_MS))
                    .thenRun(() -> bridge.runOnMain(() -> transfer(player, target, destination)));
        }).exceptionally(error -> {
            LOGGER.warn("[ArcadiaBridge] Teleportation inter-serveurs impossible : {}", error.toString());
            bridge.runOnMain(() -> player.sendSystemMessage(Lang.mc("common.error")));
            return null;
        });
    }

    private static ServerStatus serverOf(List<ServerStatus> servers, String target, long now) {
        return servers.stream()
                .filter(status -> status.isOnline(now) && !status.serverId().equals(Bridge.serverId()))
                .filter(status -> status.names().stream().anyMatch(target::equalsIgnoreCase))
                .findFirst().orElse(null);
    }

    /** Cle du message de refus, ou null si le transfert peut avoir lieu. */
    private static String refusal(ServerStatus destination, String target) {
        if (destination == null) {
            return "teleport.gone";
        }
        if (excluded(destination.serverId())) {
            return "teleport.excluded";
        }
        return destination.address() == null || destination.address().isBlank() ? "teleport.no_address" : null;
    }

    private static void transfer(ServerPlayer player, String target, ServerStatus destination) {
        if (player.hasDisconnected()) {
            return;
        }
        String address = destination.address().trim();
        int colon = address.lastIndexOf(':');
        String host = colon < 0 ? address : address.substring(0, colon);
        int port = DEFAULT_PORT;
        if (colon >= 0) {
            try {
                port = Integer.parseInt(address.substring(colon + 1));
            } catch (NumberFormatException e) {
                player.sendSystemMessage(Lang.mc("teleport.no_address", "server", destination.label()));
                return;
            }
        }
        LOGGER.info("[Teleportation] {} -> {} sur {}", player.getGameProfile().getName(), target, destination.serverId());
        player.sendSystemMessage(Lang.mc("teleport.transfer", "name", target, "server", destination.label()));
        player.connection.send(new ClientboundTransferPacket(host, port));
    }

    /** A la connexion : si le joueur arrive d'un /tp, il est amene au joueur vise. */
    public static void onJoin(Bridge bridge, ServerPlayer player) {
        Repository repo = bridge.repository();
        if (repo == null || !enabled()) {
            return;
        }
        repo.takePendingTeleport(player.getUUID(), System.currentTimeMillis()).thenAccept(pending -> {
            if (pending != null) {
                bridge.runOnMainLater(() -> arrive(bridge, player, pending), ARRIVAL_DELAY_MS);
            }
        }).exceptionally(error -> {
            LOGGER.warn("[ArcadiaBridge] Arrivee d'un /tp illisible : {}", error.toString());
            return null;
        });
    }

    private static void arrive(Bridge bridge, ServerPlayer player, PendingTeleport pending) {
        if (player.hasDisconnected()) {
            return;
        }
        if (pending.spectator()) {
            player.setGameMode(GameType.SPECTATOR);
        }
        ServerPlayer target = bridge.server().getPlayerList().getPlayerByName(pending.target());
        if (target == null) {
            player.sendSystemMessage(Lang.mc("teleport.arrived_gone", "name", pending.target()));
            return;
        }
        player.teleportTo(target.serverLevel(), target.getX(), target.getY(), target.getZ(), target.getYRot(), target.getXRot());
    }

    public static void onLeave(ServerPlayer player) {
        LAST_REQUEST.remove(player.getUUID());
    }
}
