package com.arcadia.bridge.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.compat.LuckPermsHook;
import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.data.MessageStore;
import com.arcadia.bridge.discord.DiscordRooms;
import com.arcadia.bridge.i18n.Lang;
import com.arcadia.bridge.minecraft.ChatGuard;
import com.arcadia.bridge.permission.Permissions;
import com.arcadia.bridge.share.Shares;
import com.arcadia.bridge.tabs.ChatTabs;
import com.arcadia.bridge.tabs.TabsProtocol;
import com.arcadia.bridge.util.Text;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

/**
 * Cote serveur de l'ecran facon Discord d'ArcadiaChat. Chaque message est conserve en base avec un identifiant, puis
 * envoye aux joueurs qui peuvent le voir, sur ce serveur et (par un releve de la base) sur les autres.
 *
 * <p>Le circuit habituel du chat ne change pas : ces messages s'y ajoutent, ils ne le remplacent pas.
 */
public final class MessageHistory {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Canal interne du relais : un message a ete modifie, supprime, ou sa reaction a change. */
    public static final String RELAY = "message_update";
    private static final int PAGE_SIZE = 50;
    private static final int FETCH_LIMIT = 100;
    /** Un paquet vers le client ne doit pas approcher 1 Mo : les fiches d'objet peuvent etre lourdes. */
    private static final int MAX_PAGE_BYTES = 500_000;
    private static final long REPLY_VALID_MS = 5000;
    private static final long REQUEST_MIN_INTERVAL_MS = 100;
    private static final long ACTION_MIN_INTERVAL_MS = 250;
    private static final Set<String> EMOJIS = Set.of("like", "laugh", "love", "wow", "sad");

    private record PendingReply(long id, long at) {
    }

    private final Bridge bridge;
    private final MessageTranslator translator = new MessageTranslator();

    public MessageTranslator translator() {
        return translator;
    }
    private final Map<UUID, PendingReply> replies = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRequest = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAction = new ConcurrentHashMap<>();
    private final AtomicBoolean polling = new AtomicBoolean();
    private static final int LONG_MESSAGE = 120;
    private static final int CATCH_UP_EVERY = 10;
    private static final int CATCH_UP_BATCH = 10;
    private static final long CATCH_UP_WINDOW_MS = 60 * 60 * 1000;
    private int pollsSinceCatchUp;
    private CompletableFuture<Void> queue = CompletableFuture.completedFuture(null);
    private volatile long lastId = -1;

    public MessageHistory(Bridge bridge) {
        this.bridge = bridge;
    }

    public static boolean enabled() {
        return BridgeConfig.HISTORY_ENABLED.get();
    }

    private MessageStore store() {
        return enabled() ? bridge.messages() : null;
    }

    public void start() {
        MessageStore store = store();
        if (store == null) {
            return;
        }
        store.maxId().thenAccept(id -> lastId = id).exceptionally(error -> {
            LOGGER.warn("[ArcadiaBridge] Historique des messages : lecture initiale impossible ({}).", error.toString());
            return null;
        });
    }

    public void onLeave(ServerPlayer player) {
        replies.remove(player.getUUID());
        lastRequest.remove(player.getUUID());
        lastAction.remove(player.getUUID());
    }

    // ---------------------------------------------------------------- enregistrement

    /** Chat public : global si le joueur y participe, sinon seulement sur ce serveur. */
    public void recordPublic(ServerPlayer player, String raw) {
        boolean global = CrossServerChat.isEnabled() && bridge.crossChat().isGlobal(player.getUUID());
        recordFrom(player, global ? TabsProtocol.CHANNEL_GLOBAL : TabsProtocol.CHANNEL_LOCAL, "", raw);
    }

    /** Message d'un joueur dans le chat staff ou un canal ({@code channel} : "staff" ou "#nom"). */
    public void recordChannel(ServerPlayer player, String channel, String raw) {
        recordFrom(player, channel, "", raw);
    }

    /** Message prive, dans le sens de l'expediteur (une seule ligne par message, quel que soit le serveur du destinataire). */
    public void recordPrivate(ServerPlayer player, String target, String raw) {
        recordFrom(player, privateKey(player.getGameProfile().getName(), target), target, raw);
    }

    /** Alerte pour le staff. */
    public void recordAlert(String message) {
        insert(null, TabsProtocol.CHANNEL_STAFF, "", "", "", false, Text.stripFormatting(message),
                Component.literal(Text.stripFormatting(message)));
    }

    /** Message venu de Discord : dans son salon ("discord:nom"), ou dans le salon de ce serveur ("local"). */
    public void recordDiscord(String channel, String author, String content) {
        String text = Text.forMinecraft(content, BridgeConfig.CHAT_MAX_LENGTH.get());
        insert(null, channel, Text.forMinecraft(author, 32), "", "", true, text, Component.literal(text));
    }

    private void recordFrom(ServerPlayer player, String channel, String recipient, String raw) {
        String text = Text.forMinecraft(raw, BridgeConfig.CHAT_MAX_LENGTH.get());
        insert(player, channel, player.getGameProfile().getName(), recipient, prefixOf(player), false, text,
                Shares.apply(Component.literal(text), player));
    }

    private static String prefixOf(ServerPlayer player) {
        return Text.colorize(LuckPermsHook.prefix(player.getUUID()).replaceAll("&#[0-9a-fA-F]{6}", "")).trim();
    }

    private void insert(ServerPlayer author, String channel, String sender, String recipient, String prefix, boolean discord,
                        String raw, Component body) {
        MessageStore store = store();
        if (store == null) {
            return;
        }
        long replyTo = author == null ? 0 : takeReply(author);
        String json = CrossServerChat.toJson(body, bridge.server().registryAccess());
        // Les messages prives ne quittent jamais le serveur.
        // Un long message ne fait pas attendre les autres : il part tout de suite, sa traduction le rejoint apres.
        boolean later = !channel.startsWith("@") && raw.length() > LONG_MESSAGE;
        CompletableFuture<MessageTranslator.Result> translation = channel.startsWith("@") || later
                ? CompletableFuture.completedFuture(MessageTranslator.Result.NONE) : translator.translate(raw, MessageTranslator.FAST);
        // Le message n'est diffuse que traduit : personne ne le voit changer sous ses yeux. La file garde l'ordre d'ecriture,
        // et le traducteur repond vite ou pas du tout (voir MessageTranslator).
        synchronized (this) {
            queue = queue.thenCombine(translation, (previous, result) -> result)
                    .thenCompose(result -> CompletableFuture.supplyAsync(() -> translatedJson(result, author), bridge.server())
                            .thenCompose(translatedJson -> store.insert(channel, sender, recipient, prefix, Bridge.serverId(), discord, raw,
                                    json, replyTo, result == MessageTranslator.Result.NONE ? null : result.lang(),
                                    result.text().isEmpty() ? null : result.text(), translatedJson)))
                    .<Void>thenAccept(row -> bridge.runOnMain(() -> {
                        deliver(row);
                        if (later) {
                            translate(row.id(), channel, raw, author);
                        }
                    }))
                    .exceptionally(error -> {
                        LOGGER.warn("[ArcadiaBridge] Message non conserve ({}) : {}", channel, error.toString());
                        return null;
                    });
        }
    }

    private String translatedJson(MessageTranslator.Result result, ServerPlayer author) {
        return result.text().isEmpty() ? null
                : CrossServerChat.toJson(Shares.apply(Component.literal(result.text()), author), bridge.server().registryAccess());
    }

    /** Traduction apres coup (long message, modification, rattrapage) : elle met le message a jour une fois prete. */
    private void translate(long id, String channel, String raw, ServerPlayer author) {
        MessageStore store = store();
        if (store == null || channel.startsWith("@") || !translator.enabled()) {
            return;
        }
        translator.translate(raw, MessageTranslator.SLOW).thenAccept(result -> {
            if (result == MessageTranslator.Result.NONE) {
                return;
            }
            bridge.runOnMain(() -> {
                store.setTranslation(id, result.lang(), result.text(), translatedJson(result, author)).thenRun(() -> refresh(id));
            });
        });
    }

    private long takeReply(ServerPlayer player) {
        PendingReply pending = replies.remove(player.getUUID());
        return pending != null && System.currentTimeMillis() - pending.at() <= REPLY_VALID_MS ? pending.id() : 0;
    }

    // ---------------------------------------------------------------- envoi aux joueurs

    /** Releve des messages ecrits sur les autres serveurs. */
    public void poll() {
        MessageStore store = store();
        if (store == null || lastId < 0 || !polling.compareAndSet(false, true)) {
            return;
        }
        catchUpTranslations(store);
        store.fetchAfter(lastId, Bridge.serverId(), FETCH_LIMIT).whenComplete((rows, error) -> {
            polling.set(false);
            if (error != null || rows == null || rows.isEmpty()) {
                return;
            }
            lastId = rows.get(rows.size() - 1).id();
            bridge.runOnMain(() -> rows.forEach(this::deliver));
            if (rows.size() >= FETCH_LIMIT) {
                poll();
            }
        });
    }

    /** Rattrape les messages ecrits pendant une panne du traducteur : la derniere heure seulement, quelques-uns a la fois. */
    private void catchUpTranslations(MessageStore store) {
        if (!translator.enabled() || !translator.available() || ++pollsSinceCatchUp < CATCH_UP_EVERY) {
            return;
        }
        pollsSinceCatchUp = 0;
        store.untranslated(Bridge.serverId(), System.currentTimeMillis() - CATCH_UP_WINDOW_MS, CATCH_UP_BATCH).thenAccept(rows ->
                rows.forEach(row -> translate(row.id(), row.channel(), row.raw(), null)));
    }

    /** Envoie un message aux joueurs d'ici qui peuvent le voir. Thread principal. */
    private void deliver(MessageStore.Row row) {
        if (row == null) {
            return;
        }
        for (ServerPlayer player : bridge.server().getPlayerList().getPlayers()) {
            if (ChatTabs.supportsRooms(player) && canSee(player, row) && !hidden(player, row)) {
                PacketDistributor.sendToPlayer(player, new TabsProtocol.MessagePush(toPayload(player, row)));
            }
        }
    }

    /** Ce message a change : relu en base, renvoye ici et signale aux autres serveurs. */
    private void refresh(long id) {
        MessageStore store = store();
        if (store == null) {
            return;
        }
        store.find(id).thenAccept(row -> bridge.runOnMain(() -> deliver(row)));
        if (bridge.repository() != null) {
            bridge.repository().publishChat(Bridge.serverId(), "", "", Long.toString(id), null, RELAY);
        }
    }

    /** Un autre serveur signale qu'un message a change. */
    public void onRemoteUpdate(String id) {
        MessageStore store = store();
        if (store == null) {
            return;
        }
        try {
            store.find(Long.parseLong(id)).thenAccept(row -> bridge.runOnMain(() -> deliver(row)));
        } catch (NumberFormatException ignored) {
            // Ligne de relais mal formee : rien a relire.
        }
    }

    private static boolean hidden(ServerPlayer player, MessageStore.Row row) {
        return !TabsProtocol.CHANNEL_STAFF.equals(row.channel()) && Ignores.ignores(player, row.sender());
    }

    private boolean canSee(ServerPlayer player, MessageStore.Row row) {
        String channel = row.channel();
        if (TabsProtocol.CHANNEL_GLOBAL.equals(channel)) {
            return CrossServerChat.isEnabled() || row.serverId().equals(Bridge.serverId());
        }
        if (TabsProtocol.CHANNEL_LOCAL.equals(channel)) {
            return row.serverId().equals(Bridge.serverId());
        }
        return canAccessKey(player, channel);
    }

    /** Le joueur a-t-il acces a ce salon ? Les conversations privees ne sont ouvertes qu'a leurs deux participants. */
    private boolean canAccessKey(ServerPlayer player, String key) {
        if (TabsProtocol.CHANNEL_GLOBAL.equals(key)) {
            return CrossServerChat.isEnabled();
        }
        if (key.startsWith("server:")) {
            return key.equals("server:" + Bridge.serverId());
        }
        if (TabsProtocol.CHANNEL_STAFF.equals(key)) {
            return bridge.channels().isStaff(player);
        }
        if (key.startsWith(TabsProtocol.CHANNEL_PREFIX)) {
            return ChatChannels.channelsEnabled()
                    && bridge.channels().isMember(player.getUUID(), key.substring(TabsProtocol.CHANNEL_PREFIX.length()));
        }
        if (key.startsWith(DiscordRooms.PREFIX)) {
            DiscordRooms.Room room = DiscordRooms.byName(key.substring(DiscordRooms.PREFIX.length()));
            return room != null && (!room.staffOnly() || bridge.channels().isStaff(player));
        }
        if (key.startsWith("@")) {
            String self = player.getGameProfile().getName().toLowerCase(Locale.ROOT);
            String[] pair = key.substring(1).split("\\|");
            return pair.length == 2 && (pair[0].equals(self) || pair[1].equals(self));
        }
        return false;
    }

    /** Un message tel que ce joueur le recoit : une conversation privee porte le nom de son correspondant. */
    private TabsProtocol.StoredMessage toPayload(ServerPlayer viewer, MessageStore.Row row) {
        String channel = row.channel();
        if (channel.startsWith("@")) {
            boolean mine = row.sender().equalsIgnoreCase(viewer.getGameProfile().getName());
            channel = "@" + (mine ? row.recipient() : row.sender());
        }
        Component body = row.deleted() ? Component.empty() : bodyOf(row);
        List<TabsProtocol.ReactionEntry> reactions = new ArrayList<>();
        row.reactions().forEach((emoji, names) -> reactions.add(new TabsProtocol.ReactionEntry(emoji, List.copyOf(names))));
        return new TabsProtocol.StoredMessage(row.id(), channel, row.sender(), row.prefix(), row.serverId(), row.discord(),
                row.deleted() ? "" : row.raw(), body, row.replyId(), row.replySender(), row.replyText(), row.createdAt(),
                row.editedAt(), row.deleted(), reactions, row.lang(), row.deleted() ? Component.empty() : translatedOf(row));
    }

    private Component translatedOf(MessageStore.Row row) {
        Component parsed = CrossServerChat.parse(row.translatedFormatted(), bridge.server().registryAccess());
        return parsed != null ? parsed : Component.empty();
    }

    private Component bodyOf(MessageStore.Row row) {
        Component parsed = CrossServerChat.parse(row.formatted(), bridge.server().registryAccess());
        return parsed != null ? parsed : Component.literal(row.raw());
    }

    private static String privateKey(String a, String b) {
        String first = a.toLowerCase(Locale.ROOT);
        String second = b.toLowerCase(Locale.ROOT);
        return first.compareTo(second) <= 0 ? "@" + first + "|" + second : "@" + second + "|" + first;
    }

    /** Clef en base d'un salon demande par un client ("server", "@correspondant"...). */
    private static String keyOf(ServerPlayer player, String channel) {
        if ("server".equals(channel)) {
            return "server:" + Bridge.serverId();
        }
        if (channel.startsWith("@")) {
            return privateKey(player.getGameProfile().getName(), channel.substring(1));
        }
        return channel;
    }

    // ---------------------------------------------------------------- salons du joueur

    /** A la connexion et quand ses canaux changent : ses salons et ses non-lus. */
    public void sendRooms(ServerPlayer player) {
        MessageStore store = store();
        if (store == null || !ChatTabs.supportsRooms(player)) {
            return;
        }
        List<String> keys = new ArrayList<>();
        if (CrossServerChat.isEnabled()) {
            keys.add(TabsProtocol.CHANNEL_GLOBAL);
        }
        keys.add("server:" + Bridge.serverId());
        if (bridge.channels().isStaff(player)) {
            keys.add(TabsProtocol.CHANNEL_STAFF);
        }
        bridge.channels().channelsOf(player.getUUID()).forEach(name -> keys.add(TabsProtocol.CHANNEL_PREFIX + name));
        for (DiscordRooms.Room room : DiscordRooms.all()) {
            if (!room.staffOnly() || bridge.channels().isStaff(player)) {
                keys.add(DiscordRooms.PREFIX + room.name());
            }
        }
        store.roomStates(player.getGameProfile().getName().toLowerCase(Locale.ROOT), keys).thenAccept(states -> bridge.runOnMain(() -> {
            if (player.hasDisconnected()) {
                return;
            }
            List<TabsProtocol.RoomEntry> rooms = new ArrayList<>();
            for (MessageStore.RoomState state : states) {
                rooms.add(toEntry(state));
            }
            PacketDistributor.sendToPlayer(player, new TabsProtocol.Rooms(rooms));
        })).exceptionally(error -> {
            LOGGER.warn("[ArcadiaBridge] Salons de {} illisibles : {}", player.getGameProfile().getName(), error.toString());
            return null;
        });
    }

    private static TabsProtocol.RoomEntry toEntry(MessageStore.RoomState state) {
        String key = state.key();
        if (key.startsWith("server:")) {
            return new TabsProtocol.RoomEntry("server", "", false, state.unread(), state.firstUnread());
        }
        if (key.startsWith("@")) {
            return new TabsProtocol.RoomEntry("@" + state.label(), state.label(), false, state.unread(), state.firstUnread());
        }
        if (key.startsWith(DiscordRooms.PREFIX)) {
            DiscordRooms.Room room = DiscordRooms.byName(key.substring(DiscordRooms.PREFIX.length()));
            return new TabsProtocol.RoomEntry(key, room == null ? key : room.name(), room != null && room.readOnly(),
                    state.unread(), state.firstUnread());
        }
        return new TabsProtocol.RoomEntry(key, "", false, state.unread(), state.firstUnread());
    }

    // ---------------------------------------------------------------- demandes du client

    public void onHistoryRequest(TabsProtocol.HistoryRequest payload, IPayloadContext context) {
        MessageStore store = store();
        if (store == null || !(context.player() instanceof ServerPlayer player) || tooFast(lastRequest, player, REQUEST_MIN_INTERVAL_MS)) {
            return;
        }
        String key = keyOf(player, payload.channel());
        if (!canAccessKey(player, key)) {
            return;
        }
        store.page(key, payload.beforeId(), PAGE_SIZE, payload.query()).thenAccept(page -> bridge.runOnMain(() -> {
            if (player.hasDisconnected()) {
                return;
            }
            // Les plus recents d'abord : si la page est trop lourde, ce sont les plus anciens qui attendront.
            List<TabsProtocol.StoredMessage> messages = new ArrayList<>();
            int bytes = 0;
            boolean more = page.more();
            for (int i = page.rows().size() - 1; i >= 0; i--) {
                MessageStore.Row row = page.rows().get(i);
                bytes += row.raw().length() + (row.formatted() == null ? 0 : row.formatted().length());
                if (bytes > MAX_PAGE_BYTES && !messages.isEmpty()) {
                    more = true;
                    break;
                }
                if (!hidden(player, row)) {
                    messages.add(0, toPayload(player, row));
                }
            }
            PacketDistributor.sendToPlayer(player, new TabsProtocol.HistoryPage(payload.channel(), payload.beforeId(), payload.query(),
                    messages, more));
        })).exceptionally(error -> {
            LOGGER.warn("[ArcadiaBridge] Historique de {} illisible : {}", payload.channel(), error.toString());
            return null;
        });
    }

    public void onRoomAction(TabsProtocol.RoomAction payload, IPayloadContext context) {
        MessageStore store = store();
        if (store == null || !(context.player() instanceof ServerPlayer player)) {
            return;
        }
        String name = player.getGameProfile().getName();
        switch (payload.kind()) {
            case "reply" -> replies.put(player.getUUID(), new PendingReply(payload.id(), System.currentTimeMillis()));
            case "read" -> {
                String key = keyOf(player, payload.channel());
                if (canAccessKey(player, key)) {
                    store.markRead(name.toLowerCase(Locale.ROOT), key, payload.id());
                }
            }
            case "close" -> {
                if (payload.channel().startsWith("@")) {
                    store.closeConversation(name.toLowerCase(Locale.ROOT), keyOf(player, payload.channel()));
                }
            }
            case "edit", "delete", "react", "unreact" -> {
                if (!tooFast(lastAction, player, ACTION_MIN_INTERVAL_MS)) {
                    store.find(payload.id()).thenAccept(row -> bridge.runOnMain(() -> {
                        if (row != null && !row.deleted() && !player.hasDisconnected() && canSee(player, row)) {
                            act(store, player, row, payload);
                        }
                    }));
                }
            }
            default -> { }
        }
    }

    private void act(MessageStore store, ServerPlayer player, MessageStore.Row row, TabsProtocol.RoomAction payload) {
        String name = player.getGameProfile().getName();
        boolean author = !row.discord() && row.sender().equalsIgnoreCase(name);
        switch (payload.kind()) {
            case "edit" -> {
                String text = Text.forMinecraft(payload.text(), BridgeConfig.CHAT_MAX_LENGTH.get());
                if (!author || text.isBlank() || ChatGuard.shouldBlock(player)) {
                    return;
                }
                Component refused = bridge.moderation().checkWords(player, text);
                if (refused != null) {
                    player.sendSystemMessage(refused);
                    return;
                }
                String cleaned = bridge.moderation().clean(player, text);
                String json = CrossServerChat.toJson(Shares.apply(Component.literal(cleaned), player), bridge.server().registryAccess());
                store.edit(row.id(), name, cleaned, json).thenRun(() -> {
                    refresh(row.id());
                    bridge.runOnMain(() -> translate(row.id(), row.channel(), cleaned, player));
                });
            }
            case "delete" -> {
                // Le staff ne peut supprimer que dans les salons auxquels il a acces : jamais dans les conversations des autres.
                if (author || Permissions.has(player, Permissions.CHAT_MODERATE)) {
                    LOGGER.info("[Chat] {} supprime le message {} de {} ({}).", name, row.id(), row.sender(), row.channel());
                    store.delete(row.id(), name).thenRun(() -> refresh(row.id()));
                }
            }
            default -> {
                if (EMOJIS.contains(payload.text())) {
                    store.setReaction(row.id(), payload.text(), name, "react".equals(payload.kind())).thenRun(() -> refresh(row.id()));
                }
            }
        }
    }

    private static boolean tooFast(Map<UUID, Long> last, ServerPlayer player, long interval) {
        long now = System.currentTimeMillis();
        Long previous = last.put(player.getUUID(), now);
        return previous != null && now - previous < interval;
    }

    // ---------------------------------------------------------------- ecrire dans un salon Discord

    /** Message ecrit en jeu dans un salon Discord relie : poste par le bot, puis conserve. */
    public void sendToDiscord(ServerPlayer player, String channel, String text) {
        DiscordRooms.Room room = DiscordRooms.byName(channel.substring(DiscordRooms.PREFIX.length()));
        if (room == null || !canAccessKey(player, channel)) {
            return;
        }
        if (room.readOnly()) {
            player.sendSystemMessage(Lang.mc("history.read_only", "room", room.name()));
            return;
        }
        if (!bridge.discord().isReady()) {
            player.sendSystemMessage(Lang.mc("history.discord_unavailable"));
            return;
        }
        if (!bridge.channels().acceptMessage(player, text)) {
            return;
        }
        String cleaned = bridge.moderation().clean(player, text);
        String prefix = Text.forDiscord(LuckPermsHook.prefix(player.getUUID()));
        String serverName = BridgeConfig.SERVER_NAME.get();
        bridge.discord().sendTo(room.channelId(), Text.fill(BridgeConfig.CHAT_DISCORD_FORMAT.get(),
                "prefix", prefix.isBlank() ? "" : prefix.trim() + " ",
                "name", Text.forDiscord(player.getGameProfile().getName()),
                "server", serverName.isBlank() ? "" : Text.forDiscord(serverName),
                "message", Text.forDiscord(Shares.forDiscord(cleaned, player))));
        recordFrom(player, channel, "", cleaned);
    }
}
