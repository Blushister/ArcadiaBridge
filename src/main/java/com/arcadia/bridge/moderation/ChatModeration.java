package com.arcadia.bridge.moderation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.data.Models.Announcement;
import com.arcadia.bridge.data.Models.ChatFilter;
import com.arcadia.bridge.data.Models.ChatRule;
import com.arcadia.bridge.data.Repository;
import com.arcadia.bridge.data.Repository.ModerationData;
import com.arcadia.bridge.i18n.Lang;
import com.arcadia.bridge.permission.Permissions;
import com.arcadia.bridge.tabs.ChatTabs;
import com.arcadia.bridge.tabs.TabsProtocol;
import com.arcadia.bridge.util.Text;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * Moderation du chat reglee depuis le panel staff (ou par commande) et commune a tous les serveurs :
 * mode lent et verrou du chat public (pour un serveur ou pour tous), annonces automatiques et mots filtres.
 * Mentions de groupe : @staff previent le staff de tous les serveurs, @everyone est reserve au staff.
 *
 * <p>Les reglages vivent en base ; chaque serveur les relit a chaque synchronisation, et tout de suite quand un
 * autre serveur les change (message {@link #RELAY} du relais).
 */
public final class ChatModeration {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Canal interne du relais : les reglages ont change, a relire. */
    public static final String RELAY = "moderation";
    /** Portee d'une regle valable pour tous les serveurs. */
    public static final String ALL = "*";
    private static final Pattern EVERYONE = Pattern.compile("(?i)@(everyone|tous|here)(?![\\p{L}\\p{N}_])");
    private static final Pattern STAFF_CALL = Pattern.compile("(?i)@staff(?![\\p{L}\\p{N}_])");
    private static final long STAFF_CALL_COOLDOWN_MS = TimeUnit.SECONDS.toMillis(60);

    private final Bridge bridge;
    private volatile Map<String, ChatRule> rules = Map.of();
    private volatile List<Announcement> announcements = List.of();
    private volatile List<ChatFilter> filters = List.of();
    /** Messages epingles, par portee ("*" ou identifiant de serveur). */
    private volatile Map<String, String> pins = Map.of();
    /** Mots a masquer et mots qui bloquent le message ; null si aucun. */
    private volatile Pattern maskPattern;
    private volatile Pattern blockPattern;
    private volatile boolean loaded;

    private final Map<UUID, Long> lastPublic = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastStaffCall = new ConcurrentHashMap<>();
    /** Prochain envoi de chaque annonce en jeu, et sur Discord (serveur primaire). Thread principal. */
    private final Map<Long, Long> nextInGame = new HashMap<>();
    private final Map<Long, Long> nextDiscord = new HashMap<>();

    public ChatModeration(Bridge bridge) {
        this.bridge = bridge;
    }

    // ---------------------------------------------------------------- reglages

    /** Relit les reglages en base ; previent les joueurs d'ici si le mode lent ou le verrou change. */
    public CompletableFuture<Void> reload() {
        Repository repo = bridge.repository();
        if (repo == null) {
            return CompletableFuture.completedFuture(null);
        }
        return repo.loadModeration().thenAccept(this::apply).exceptionally(error -> {
            LOGGER.warn("[ArcadiaBridge] Reglages de moderation illisibles : {}", error.toString());
            return null;
        });
    }

    private void apply(ModerationData data) {
        boolean wasLocked = isLocked();
        int oldSlow = slowSeconds();
        List<String> oldPins = pinnedTexts();
        Map<String, String> byPin = new HashMap<>();
        data.pins().forEach(pin -> byPin.put(pin.scope(), pin.message()));
        pins = Map.copyOf(byPin);
        if (loaded && !oldPins.equals(pinnedTexts())) {
            bridge.runOnMain(() -> bridge.server().getPlayerList().getPlayers().forEach(this::sendPins));
        }
        Map<String, ChatRule> byScope = new HashMap<>();
        data.rules().forEach(rule -> byScope.put(rule.scope(), rule));
        rules = Map.copyOf(byScope);
        announcements = List.copyOf(data.announcements());
        filters = List.copyOf(data.filters());
        maskPattern = compile(data.filters().stream().filter(f -> !f.block()).map(ChatFilter::word).toList());
        blockPattern = compile(data.filters().stream().filter(ChatFilter::block).map(ChatFilter::word).toList());
        boolean first = !loaded;
        loaded = true;
        boolean locked = isLocked();
        int slow = slowSeconds();
        if (first || (locked == wasLocked && slow == oldSlow)) {
            return;
        }
        bridge.runOnMain(() -> {
            Component notice = locked != wasLocked
                    ? Lang.mc(locked ? "moderation.locked.on" : "moderation.locked.off")
                    : slow > 0 ? Lang.mc("moderation.slow.on", "seconds", slow) : Lang.mc("moderation.slow.off");
            for (ServerPlayer player : bridge.server().getPlayerList().getPlayers()) {
                ChatTabs.deliver(player, TabsProtocol.CHANNEL_LOCAL, Bridge.serverId(), "", notice);
            }
        });
    }

    /** Apres un changement fait ici : relecture immediate ici, et sur les autres serveurs par le relais. */
    public CompletableFuture<Void> changed() {
        Repository repo = bridge.repository();
        if (repo != null) {
            repo.publishChat(Bridge.serverId(), "", "", "reload", null, RELAY);
        }
        return reload();
    }

    public Map<String, ChatRule> rules() {
        return rules;
    }

    public List<Announcement> announcements() {
        return announcements;
    }

    public List<ChatFilter> filters() {
        return filters;
    }

    public ChatRule rule(String scope) {
        return rules.getOrDefault(scope, new ChatRule(scope, 0, false));
    }

    /** Chat public verrouille ici (regle de ce serveur ou de tous). */
    public boolean isLocked() {
        return rule(ALL).locked() || rule(Bridge.serverId()).locked();
    }

    /** Mode lent ici, en secondes (le plus strict des deux regles). */
    public int slowSeconds() {
        return Math.max(rule(ALL).slowSeconds(), rule(Bridge.serverId()).slowSeconds());
    }

    /**
     * Portee d'une regle d'apres l'argument d'une commande : "ici" (ou rien) pour ce serveur, "tous" pour tous,
     * sinon l'identifiant d'un serveur en ligne. Null si inconnu.
     */
    public String resolveScope(String arg) {
        if (arg == null || arg.isBlank() || arg.equalsIgnoreCase("ici")) {
            return Bridge.serverId();
        }
        if (arg.equalsIgnoreCase("tous") || arg.equalsIgnoreCase("all") || ALL.equals(arg)) {
            return ALL;
        }
        boolean known = arg.equals(Bridge.serverId())
                || ChatTabs.servers().stream().anyMatch(server -> server.id().equals(arg));
        return known ? arg : null;
    }

    /** Nom affiche d'une portee : "tous les serveurs" ou le nom du serveur. */
    public static String scopeLabel(String scope) {
        if (ALL.equals(scope)) {
            return Lang.get("moderation.scope.all");
        }
        return ChatTabs.servers().stream().filter(server -> server.id().equals(scope))
                .map(TabsProtocol.ServerInfo::name).findFirst().orElse(scope);
    }

    /**
     * Change le mode lent ({@code slowSeconds}) ou le verrou ({@code locked}) d'une portee ; l'autre reglage est
     * garde. Les joueurs concernes sont prevenus a la relecture.
     */
    public CompletableFuture<Void> setRule(String scope, Integer slowSeconds, Boolean locked, String by) {
        Repository repo = bridge.repository();
        if (repo == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("base indisponible"));
        }
        ChatRule current = rule(scope);
        int slow = slowSeconds != null ? Math.max(0, Math.min(3600, slowSeconds)) : current.slowSeconds();
        boolean lock = locked != null ? locked : current.locked();
        LOGGER.info("[Moderation] {} : {} -> mode lent {} s, verrou {}", by, scope, slow, lock);
        return repo.setChatRule(scope, slow, lock, by).thenCompose(v -> changed());
    }

    // ---------------------------------------------------------------- messages epingles

    public Map<String, String> pins() {
        return pins;
    }

    /** Messages epingles visibles ici : celui de tous les serveurs, puis celui de ce serveur. */
    private List<String> pinnedTexts() {
        List<String> list = new ArrayList<>();
        String all = pins.get(ALL);
        String here = pins.get(Bridge.serverId());
        if (all != null) {
            list.add(all);
        }
        if (here != null) {
            list.add(here);
        }
        return list;
    }

    public List<Component> pinnedLines() {
        return pinnedTexts().stream()
                .map(text -> (Component) Component.literal(Text.colorize(Lang.get("pin.line", "message", text))))
                .toList();
    }

    /** Avec ArcadiaChat : bandeau en haut du chat (vide s'il n'y a rien) ; sans : les messages dans le chat. */
    public void sendPins(ServerPlayer player) {
        List<Component> lines = pinnedLines();
        if (ChatTabs.supports(player)) {
            ChatTabs.sendPinned(player, lines);
        } else {
            lines.forEach(player::sendSystemMessage);
        }
    }

    // ---------------------------------------------------------------- controle des messages

    /**
     * Message du chat public : verrou, mode lent puis mots bloques.
     *
     * @return la raison du refus, a montrer au joueur, ou null si le message passe
     */
    public Component checkPublic(ServerPlayer player, String text) {
        if (!Permissions.has(player, Permissions.CHAT_MODERATE)) {
            if (isLocked()) {
                return Lang.mc("moderation.locked.refused");
            }
            int slow = slowSeconds();
            if (slow > 0) {
                long now = System.currentTimeMillis();
                Long last = lastPublic.get(player.getUUID());
                long wait = last == null ? 0 : last + slow * 1000L - now;
                if (wait > 0) {
                    return Lang.mc("moderation.slow.refused", "seconds", (wait + 999) / 1000);
                }
            }
        }
        Component blocked = checkWords(player, text);
        if (blocked == null) {
            lastPublic.put(player.getUUID(), System.currentTimeMillis());
        }
        return blocked;
    }

    /**
     * Mots bloques (tous les chats : public, prives, canaux, equipe). Le staff est prevenu.
     *
     * @return la raison du refus, ou null si le message passe
     */
    public Component checkWords(ServerPlayer player, String text) {
        Pattern pattern = blockPattern;
        if (pattern == null || text == null || !pattern.matcher(Text.stripFormatting(text)).find()) {
            return null;
        }
        String name = player.getGameProfile().getName();
        LOGGER.info("[Filtre] Message bloque de {} : {}", name, Text.stripFormatting(text));
        bridge.channels().alert(Lang.get("alert.filter", "name", name, "message", Text.stripFormatting(text)));
        return Lang.mc("moderation.filter.refused");
    }

    public void onLeave(ServerPlayer player) {
        lastPublic.remove(player.getUUID());
        lastStaffCall.remove(player.getUUID());
    }

    /** @staff dans le chat public : une alerte pour le staff de tous les serveurs (une par minute et par joueur). */
    public void onPublicChat(ServerPlayer player, String text) {
        if (text == null || !STAFF_CALL.matcher(text).find() || bridge.channels().isStaff(player)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastStaffCall.get(player.getUUID());
        if (last != null && now - last < STAFF_CALL_COOLDOWN_MS) {
            return;
        }
        lastStaffCall.put(player.getUUID(), now);
        bridge.channels().alert(Lang.get("alert.staff_call", "name", player.getGameProfile().getName(),
                "message", Text.stripFormatting(text)));
    }

    // ---------------------------------------------------------------- texte affiche

    /** Texte d'un joueur : mots masques, et @everyone desamorce s'il n'a pas le droit de l'utiliser. */
    public String clean(ServerPlayer sender, String text) {
        if (text == null) {
            return null;
        }
        String out = mask(text);
        return mayMentionEveryone(sender) ? out : EVERYONE.matcher(out).replaceAll("@​$1");
    }

    /** Meme traitement sur une ligne deja mise en forme : le style de chaque morceau est garde. */
    public Component clean(ServerPlayer sender, Component line) {
        if (line == null) {
            return null;
        }
        Pattern mask = maskPattern;
        boolean neutralize = !mayMentionEveryone(sender);
        String plain = line.getString();
        if ((mask == null || !mask.matcher(plain).find()) && (!neutralize || !EVERYONE.matcher(plain).find())) {
            return line;
        }
        MutableComponent out = Component.empty();
        line.visit((style, text) -> {
            out.append(Component.literal(clean(sender, text)).withStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    private boolean mayMentionEveryone(ServerPlayer sender) {
        return sender != null && Permissions.has(sender, Permissions.MENTION_EVERYONE);
    }

    private String mask(String text) {
        Pattern pattern = maskPattern;
        if (pattern == null) {
            return text;
        }
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, "*".repeat(matcher.group().length()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Un mot entier, sans tenir compte des majuscules ; "mot*" vise aussi tous les mots qui commencent ainsi.
     */
    private static Pattern compile(List<String> words) {
        List<String> parts = new ArrayList<>();
        for (String word : words) {
            String w = word.trim().toLowerCase(Locale.ROOT);
            if (w.isEmpty()) {
                continue;
            }
            boolean prefix = w.endsWith("*");
            String core = Pattern.quote(prefix ? w.substring(0, w.length() - 1) : w);
            parts.add(core + (prefix ? "[\\p{L}\\p{N}_]*" : ""));
        }
        if (parts.isEmpty()) {
            return null;
        }
        return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])(?:" + String.join("|", parts) + ")(?![\\p{L}\\p{N}_])");
    }

    // ---------------------------------------------------------------- annonces automatiques

    /** Chaque seconde, sur le thread principal : envoie les annonces arrivees a echeance. */
    public void tick() {
        if (!loaded) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Announcement> list = announcements;
        nextInGame.keySet().removeIf(id -> list.stream().noneMatch(a -> a.id() == id));
        nextDiscord.keySet().removeIf(id -> list.stream().noneMatch(a -> a.id() == id));
        boolean discord = Bridge.isPrimary() && bridge.discord().isReady();
        for (Announcement announcement : list) {
            if (!announcement.enabled() || announcement.intervalMinutes() <= 0) {
                nextInGame.remove(announcement.id());
                nextDiscord.remove(announcement.id());
                continue;
            }
            long interval = TimeUnit.MINUTES.toMillis(announcement.intervalMinutes());
            if (announcement.targets(Bridge.serverId()) && due(nextInGame, announcement.id(), now, interval)) {
                Component line = Component.literal(Text.colorize(Lang.get("announce.line", "message", announcement.message())));
                for (ServerPlayer player : bridge.server().getPlayerList().getPlayers()) {
                    ChatTabs.deliver(player, TabsProtocol.CHANNEL_LOCAL, Bridge.serverId(), "", line);
                }
            }
            // Sur Discord, une seule fois pour tout le reseau : par le serveur primaire.
            if (announcement.discord() && discord && due(nextDiscord, announcement.id(), now, interval)) {
                bridge.discord().sendChat(Lang.get("announce.discord", "message",
                        Text.forDiscord(announcement.message())));
            }
        }
    }

    /** Premiere echeance un intervalle apres le chargement (pas de rafale au demarrage). */
    private static boolean due(Map<Long, Long> next, long id, long now, long interval) {
        Long at = next.get(id);
        if (at == null) {
            next.put(id, now + interval);
            return false;
        }
        if (now < at) {
            return false;
        }
        next.put(id, now + interval);
        return true;
    }
}
