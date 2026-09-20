package com.arcadia.bridge.chat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.data.Models.ChatLine;
import com.arcadia.bridge.data.Repository;
import com.arcadia.bridge.util.Text;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * Chat partage entre les serveurs Arcadia, via la base commune.
 *
 * <p>Cote Discord, rien ne change : chaque serveur garde son salon. Les messages venus d'un autre
 * serveur ne sont donc pas renvoyes vers Discord, sinon ils apparaitraient en double.
 *
 * <p>Chaque joueur choisit avec {@code /chat} s'il participe au chat global ou s'il reste sur son
 * serveur ; le choix est conserve d'une session a l'autre.
 */
public final class CrossServerChat {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Bridge bridge;
    /** Joueurs ayant choisi le mode local (les autres suivent le reglage par defaut). */
    private final Map<UUID, Boolean> modes = new ConcurrentHashMap<>();
    private final AtomicBoolean polling = new AtomicBoolean();
    private volatile long lastId = -1;

    public CrossServerChat(Bridge bridge) {
        this.bridge = bridge;
    }

    public static boolean isEnabled() {
        return BridgeConfig.CHAT_CROSS_SERVER.get();
    }

    /** Au demarrage, on part du dernier message connu : pas de rejeu de l'historique. */
    public void start() {
        Repository repo = bridge.repository();
        if (repo == null || !isEnabled()) {
            return;
        }
        repo.lastChatId().thenAccept(id -> lastId = id).exceptionally(error -> {
            LOGGER.warn("[ArcadiaBridge] Chat inter-serveurs : lecture initiale impossible ({}).", error.toString());
            return null;
        });
    }

    // ---------------------------------------------------------------- mode par joueur

    public boolean isGlobal(UUID uuid) {
        return modes.getOrDefault(uuid, BridgeConfig.CHAT_CROSS_SERVER_DEFAULT.get());
    }

    public void setGlobal(UUID uuid, boolean global) {
        modes.put(uuid, global);
        Repository repo = bridge.repository();
        if (repo != null) {
            repo.setChatGlobal(uuid, global);
        }
    }

    /** Charge le choix du joueur a sa connexion. */
    public void onJoin(ServerPlayer player) {
        Repository repo = bridge.repository();
        if (repo == null || !isEnabled()) {
            return;
        }
        UUID uuid = player.getUUID();
        repo.getChatGlobal(uuid).thenAccept(stored -> stored.ifPresent(value -> modes.put(uuid, value)));
    }

    public void onLeave(ServerPlayer player) {
        modes.remove(player.getUUID());
    }

    // ---------------------------------------------------------------- envoi et reception

    /** Publie un message local vers les autres serveurs. */
    public void publish(ServerPlayer player, String prefix, String message) {
        Repository repo = bridge.repository();
        if (repo == null || !isEnabled() || !isGlobal(player.getUUID())) {
            return;
        }
        repo.publishChat(Bridge.serverId(), player.getGameProfile().getName(), Text.stripFormatting(prefix),
                        Text.stripFormatting(message))
                .exceptionally(error -> {
                    LOGGER.warn("[ArcadiaBridge] Publication du chat inter-serveurs impossible : {}", error.toString());
                    return null;
                });
    }

    /** Releve les messages des autres serveurs et les affiche aux joueurs en mode global. */
    public void poll() {
        Repository repo = bridge.repository();
        if (repo == null || !isEnabled() || lastId < 0 || !polling.compareAndSet(false, true)) {
            return;
        }
        repo.fetchChat(lastId, Bridge.serverId()).whenComplete((lines, error) -> {
            polling.set(false);
            if (error != null || lines == null || lines.isEmpty()) {
                return;
            }
            lastId = lines.get(lines.size() - 1).id();
            List<Component> components = lines.stream().map(CrossServerChat::format).toList();
            bridge.runOnMain(() -> {
                for (ServerPlayer player : bridge.server().getPlayerList().getPlayers()) {
                    if (isGlobal(player.getUUID())) {
                        components.forEach(player::sendSystemMessage);
                    }
                }
            });
        });
    }

    private static Component format(ChatLine line) {
        String prefix = line.prefix() == null ? "" : line.prefix().trim();
        String text = Text.fill(Text.colorize(BridgeConfig.CHAT_CROSS_SERVER_FORMAT.get()),
                "server", Text.stripFormatting(line.serverId()),
                "prefix", prefix.isEmpty() ? "" : prefix + " ",
                "name", Text.stripFormatting(line.playerName()),
                "message", Text.forMinecraft(line.message(), BridgeConfig.CHAT_MAX_LENGTH.get()));
        return Component.literal(text);
    }
}
