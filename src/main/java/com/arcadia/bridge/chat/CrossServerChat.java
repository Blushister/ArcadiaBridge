package com.arcadia.bridge.chat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.data.Models.ChatLine;
import com.arcadia.bridge.data.Repository;
import com.arcadia.bridge.moderation.ChatModeration;
import com.arcadia.bridge.tabs.ChatTabs;
import com.arcadia.bridge.tabs.TabsProtocol;
import com.arcadia.bridge.util.Text;
import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
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
    /** Au-dela, la ligne mise en forme est ignoree et le format par defaut s'applique. */
    private static final int MAX_FORMATTED_JSON = 32768;

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

    /** Le relais par la base sert aussi au chat staff et aux canaux thematiques. */
    public static boolean relayActive() {
        return isEnabled() || ChatChannels.staffEnabled() || ChatChannels.channelsEnabled();
    }

    /** Au demarrage, on part du dernier message connu : pas de rejeu de l'historique. */
    public void start() {
        Repository repo = bridge.repository();
        if (repo == null || !relayActive()) {
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

    /**
     * Publie un message local vers les autres serveurs.
     *
     * @param formatted la ligne telle qu'affichee sur ce serveur (mise en forme LPC), si elle existe
     */
    public void publish(ServerPlayer player, String prefix, String message, Optional<Component> formatted) {
        Repository repo = bridge.repository();
        if (repo == null || !isEnabled() || !isGlobal(player.getUUID())) {
            return;
        }
        String json = formatted.map(line -> toJson(line, player.registryAccess())).orElse(null);
        repo.publishChat(Bridge.serverId(), player.getGameProfile().getName(), Text.stripFormatting(prefix),
                        Text.stripFormatting(message), json)
                .exceptionally(error -> {
                    LOGGER.warn("[ArcadiaBridge] Publication du chat inter-serveurs impossible : {}", error.toString());
                    return null;
                });
    }

    /** Releve les messages des autres serveurs et les affiche aux joueurs en mode global. */
    public void poll() {
        Repository repo = bridge.repository();
        if (repo == null || !relayActive() || lastId < 0 || !polling.compareAndSet(false, true)) {
            return;
        }
        repo.fetchChat(lastId, Bridge.serverId()).whenComplete((lines, error) -> {
            polling.set(false);
            if (error != null || lines == null || lines.isEmpty()) {
                return;
            }
            lastId = lines.get(lines.size() - 1).id();
            bridge.runOnMain(() -> {
                HolderLookup.Provider registries = bridge.server().registryAccess();
                // Chat staff, alertes et canaux : la ligne a ete mise en forme par le serveur d'origine.
                for (ChatLine line : lines) {
                    if (ChatChannels.TYPING.equals(line.channel())) {
                        ChatTabs.remoteTyping(line.playerName(), "1".equals(line.message()));
                    } else if (ChatChannels.REACTION.equals(line.channel())) {
                        String[] parts = line.message().split("\\|");
                        if (parts.length == 3) {
                            ChatTabs.deliverReaction(parts[0], parts[1], line.playerName(), "1".equals(parts[2]));
                        }
                    } else if (ChatModeration.RELAY.equals(line.channel())) {
                        bridge.moderation().reload();
                    } else if (ChatChannels.MEMBERSHIP.equals(line.channel())) {
                        bridge.channels().onMembershipEvent(line.message());
                    } else if (!TabsProtocol.CHANNEL_GLOBAL.equals(line.channel())) {
                        Component component = parse(line.formatted(), registries);
                        if (component != null) {
                            bridge.channels().deliverRemote(line, component);
                        }
                    }
                }
                if (!isEnabled()) {
                    return;
                }
                List<ChatLine> global = lines.stream()
                        .filter(line -> TabsProtocol.CHANNEL_GLOBAL.equals(line.channel())).toList();
                List<Component> components = global.stream().map(line -> format(line, registries)).toList();
                for (ServerPlayer player : bridge.server().getPlayerList().getPlayers()) {
                    if (ChatTabs.supports(player)) {
                        // Avec ArcadiaChat, le joueur recoit tout : c'est son onglet qui filtre.
                        for (int i = 0; i < components.size(); i++) {
                            ChatTabs.send(player, TabsProtocol.CHANNEL_GLOBAL, global.get(i).serverId(),
                                    global.get(i).playerName(), components.get(i));
                        }
                    } else if (isGlobal(player.getUUID())) {
                        for (int i = 0; i < components.size(); i++) {
                            if (!Ignores.ignores(player, global.get(i).playerName())) {
                                player.sendSystemMessage(components.get(i));
                            }
                        }
                    }
                }
            });
            // Lot complet : d'autres lignes attendent, on les lit sans attendre le prochain passage.
            if (lines.size() >= Repository.CHAT_FETCH_LIMIT) {
                poll();
            }
        });
    }

    private static Component format(ChatLine line, HolderLookup.Provider registries) {
        Component formatted = parse(line.formatted(), registries);
        if (formatted != null) {
            String tag = Text.fill(Text.colorize(BridgeConfig.CHAT_CROSS_SERVER_TAG.get()),
                    "server", Text.stripFormatting(line.serverId()));
            return Component.literal(tag).append(formatted);
        }
        return defaultFormat(line);
    }

    private static Component defaultFormat(ChatLine line) {
        String prefix = line.prefix() == null ? "" : line.prefix().trim();
        String text = Text.fill(Text.colorize(BridgeConfig.CHAT_CROSS_SERVER_FORMAT.get()),
                "server", Text.stripFormatting(line.serverId()),
                "prefix", prefix.isEmpty() ? "" : prefix + " ",
                "name", Text.stripFormatting(line.playerName()),
                "message", Text.forMinecraft(line.message(), BridgeConfig.CHAT_MAX_LENGTH.get()));
        return Component.literal(text);
    }

    public static String toJson(Component line, HolderLookup.Provider registries) {
        try {
            String json = Component.Serializer.toJson(line, registries);
            return json.length() <= MAX_FORMATTED_JSON ? json : null;
        } catch (RuntimeException e) {
            LOGGER.debug("[ArcadiaBridge] Ligne de chat non serialisable : {}", e.toString());
            return null;
        }
    }

    /**
     * Relit la ligne mise en forme par l'autre serveur. Seuls le texte et son apparence sont gardes :
     * aucun clic, survol ou insertion ne passe d'un serveur a l'autre.
     */
    /** Seuls les clics des partages et des sondages traversent les serveurs. */
    private static ClickEvent safeClick(ClickEvent click) {
        if (click == null || click.getAction() != ClickEvent.Action.RUN_COMMAND) {
            return null;
        }
        String value = click.getValue();
        return value.startsWith("/partage ") || value.startsWith("/sondage voter ") ? click : null;
    }

    /** Survols acceptes : texte et fiche d'objet. */
    private static HoverEvent safeHover(HoverEvent hover) {
        if (hover == null) {
            return null;
        }
        var action = hover.getAction();
        return action == HoverEvent.Action.SHOW_TEXT || action == HoverEvent.Action.SHOW_ITEM ? hover : null;
    }

    public static Component parse(String json, HolderLookup.Provider registries) {
        if (json == null || json.isEmpty() || json.length() > MAX_FORMATTED_JSON) {
            return null;
        }
        try {
            Component parsed = Component.Serializer.fromJson(json, registries);
            if (parsed == null) {
                return null;
            }
            MutableComponent safe = Component.empty();
            parsed.visit((style, text) -> {
                safe.append(Component.literal(text).withStyle(style.withClickEvent(safeClick(style.getClickEvent()))
                        .withHoverEvent(safeHover(style.getHoverEvent())).withInsertion(null)));
                return Optional.empty();
            }, Style.EMPTY);
            int max = BridgeConfig.CHAT_MAX_LENGTH.get() + 256;
            return safe.getString().length() <= max ? safe : null;
        } catch (RuntimeException e) {
            LOGGER.debug("[ArcadiaBridge] Ligne de chat illisible, format par defaut : {}", e.toString());
            return null;
        }
    }
}
