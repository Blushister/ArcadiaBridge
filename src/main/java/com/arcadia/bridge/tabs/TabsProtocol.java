package com.arcadia.bridge.tabs;

import java.util.List;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Protocole partage avec le mod client ArcadiaChat (chat a onglets). Les deux mods declarent les memes
 * paquets ; ils sont optionnels : un client sans ArcadiaChat se connecte normalement et recoit le chat
 * habituel.
 *
 * <p>Toute modification doit etre reportee a l'identique dans ArcadiaChat (meme identifiants, meme
 * ordre des champs) et s'accompagner d'un changement de {@link #VERSION}.
 */
public final class TabsProtocol {

    public static final String NAMESPACE = "arcadiachat";
    public static final String VERSION = "3";

    /** Canaux d'un message. Le client decide dans quels onglets il apparait. */
    public static final String CHANNEL_GLOBAL = "global";
    public static final String CHANNEL_LOCAL = "local";
    public static final String CHANNEL_DISCORD = "discord";
    /** {@code origin} : le correspondant (celui qui ecrit, ou celui a qui on ecrit). */
    public static final String CHANNEL_PRIVATE = "private";
    public static final String CHANNEL_STAFF = "staff";
    /** Alertes pour le staff (signalements, contournement de ban). */
    public static final String CHANNEL_ALERT = "alert";
    /** Chat d'equipe (FTB Teams). */
    public static final String CHANNEL_TEAM = "team";
    /** Prefixe des canaux thematiques : "#commerce". */
    public static final String CHANNEL_PREFIX = "#";

    private TabsProtocol() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, path);
    }

    private static final StreamCodec<ByteBuf, List<String>> STRINGS =
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list());

    /**
     * Client -> serveur, a la connexion : "j'ai ArcadiaChat". Seuls les joueurs qui l'ont envoye recoivent le
     * chat etiquete ; la simple presence des canaux ne suffit pas (un autre mod peut les declarer).
     */
    public record Ready(int protocol) implements CustomPacketPayload {
        public static final Type<Ready> TYPE = new Type<>(id("ready"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ready> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Ready::protocol,
                Ready::new);

        @Override
        public Type<Ready> type() {
            return TYPE;
        }
    }

    /** Serveur -> client, en reponse a {@link Ready} : le serveur gere les onglets. */
    public record Hello(String serverId, String serverName, boolean crossServer, boolean writesGlobal, boolean staff,
                        boolean typing, boolean team) implements CustomPacketPayload {
        public static final Type<Hello> TYPE = new Type<>(id("hello"));
        // Ecrit a la main : StreamCodec.composite s'arrete a six champs.
        public static final StreamCodec<RegistryFriendlyByteBuf, Hello> STREAM_CODEC = StreamCodec.of(
                (buf, hello) -> {
                    buf.writeUtf(hello.serverId());
                    buf.writeUtf(hello.serverName());
                    buf.writeBoolean(hello.crossServer());
                    buf.writeBoolean(hello.writesGlobal());
                    buf.writeBoolean(hello.staff());
                    buf.writeBoolean(hello.typing());
                    buf.writeBoolean(hello.team());
                },
                buf -> new Hello(buf.readUtf(), buf.readUtf(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                        buf.readBoolean(), buf.readBoolean()));

        @Override
        public Type<Hello> type() {
            return TYPE;
        }
    }

    /** Un serveur Arcadia en ligne ({@code address} vide : on ne peut pas le rejoindre depuis le chat). */
    public record ServerInfo(String id, String name, String address) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ServerInfo> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ServerInfo::id,
                ByteBufCodecs.STRING_UTF8, ServerInfo::name,
                ByteBufCodecs.STRING_UTF8, ServerInfo::address,
                ServerInfo::new);
    }

    /** Serveur -> client : les serveurs Arcadia en ligne (envoye a la connexion puis a chaque changement). */
    public record Servers(List<ServerInfo> servers) implements CustomPacketPayload {
        public static final Type<Servers> TYPE = new Type<>(id("servers"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Servers> STREAM_CODEC = StreamCodec.composite(
                ServerInfo.STREAM_CODEC.apply(ByteBufCodecs.list()), Servers::servers,
                Servers::new);

        @Override
        public Type<Servers> type() {
            return TYPE;
        }
    }

    /** Serveur -> client : les canaux thematiques rejoints par le joueur. */
    public record Channels(List<String> channels) implements CustomPacketPayload {
        public static final Type<Channels> TYPE = new Type<>(id("channels"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Channels> STREAM_CODEC = StreamCodec.composite(
                STRINGS, Channels::channels,
                Channels::new);

        @Override
        public Type<Channels> type() {
            return TYPE;
        }
    }

    /**
     * Serveur -> client : une ligne de chat et son canal. {@code origin} : voir les canaux ; {@code sender} :
     * le joueur qui a ecrit (vide pour une annonce, une alerte ou un message venu de Discord).
     */
    public record Line(String channel, String origin, String sender, Component message) implements CustomPacketPayload {
        public static final Type<Line> TYPE = new Type<>(id("line"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Line> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Line::channel,
                ByteBufCodecs.STRING_UTF8, Line::origin,
                ByteBufCodecs.STRING_UTF8, Line::sender,
                ComponentSerialization.STREAM_CODEC, Line::message,
                Line::new);

        @Override
        public Type<Line> type() {
            return TYPE;
        }
    }

    /** Client -> serveur : le joueur ecrit dans le chat global (true) ou seulement sur ce serveur (false). */
    public record Scope(boolean global) implements CustomPacketPayload {
        public static final Type<Scope> TYPE = new Type<>(id("scope"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Scope> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Scope::global,
                Scope::new);

        @Override
        public Type<Scope> type() {
            return TYPE;
        }
    }

    /** Client -> serveur : message ecrit dans l'onglet Staff ("staff") ou dans un canal ("#nom"). */
    public record Send(String channel, String text) implements CustomPacketPayload {
        public static final Type<Send> TYPE = new Type<>(id("send"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Send> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(32), Send::channel,
                ByteBufCodecs.stringUtf8(256), Send::text,
                Send::new);

        @Override
        public Type<Send> type() {
            return TYPE;
        }
    }

    /** Client -> serveur : le joueur ecrit (true, renvoye regulierement) ou a arrete (false). */
    public record Typing(boolean typing) implements CustomPacketPayload {
        public static final Type<Typing> TYPE = new Type<>(id("typing"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Typing> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Typing::typing,
                Typing::new);

        @Override
        public Type<Typing> type() {
            return TYPE;
        }
    }

    /**
     * Serveur -> client : un autre joueur ecrit, ou a arrete. {@code remote} : il est sur un autre serveur et
     * ecrit dans le chat global.
     */
    public record TypingState(String name, boolean typing, boolean remote) implements CustomPacketPayload {
        public static final Type<TypingState> TYPE = new Type<>(id("typing_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TypingState> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, TypingState::name,
                ByteBufCodecs.BOOL, TypingState::typing,
                ByteBufCodecs.BOOL, TypingState::remote,
                TypingState::new);

        @Override
        public Type<TypingState> type() {
            return TYPE;
        }
    }

    /** Client -> serveur : demande la liste des canaux (ecran Canaux d'ArcadiaChat). */
    public record ChannelsRequest() implements CustomPacketPayload {
        public static final ChannelsRequest INSTANCE = new ChannelsRequest();
        public static final Type<ChannelsRequest> TYPE = new Type<>(id("channels_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ChannelsRequest> STREAM_CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<ChannelsRequest> type() {
            return TYPE;
        }
    }

    /** Un canal tel que le joueur le voit : prive ou non, et s'il en est membre. */
    public record ChannelEntry(String name, boolean isPrivate, boolean member) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ChannelEntry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ChannelEntry::name,
                ByteBufCodecs.BOOL, ChannelEntry::isPrivate,
                ByteBufCodecs.BOOL, ChannelEntry::member,
                ChannelEntry::new);
    }

    /**
     * Serveur -> client : les canaux visibles par le joueur (publics, et prives dont il est membre ; tous pour
     * le staff), et s'il peut les gerer (creer, ajouter des joueurs, supprimer).
     */
    public record ChannelDirectory(List<ChannelEntry> channels, boolean manager) implements CustomPacketPayload {
        public static final Type<ChannelDirectory> TYPE = new Type<>(id("channel_directory"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ChannelDirectory> STREAM_CODEC = StreamCodec.composite(
                ChannelEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), ChannelDirectory::channels,
                ByteBufCodecs.BOOL, ChannelDirectory::manager,
                ChannelDirectory::new);

        @Override
        public Type<ChannelDirectory> type() {
            return TYPE;
        }
    }

    /** Serveur -> client : les joueurs connectes aux autres serveurs (completion des pseudos). */
    public record RemotePlayers(List<String> names) implements CustomPacketPayload {
        public static final Type<RemotePlayers> TYPE = new Type<>(id("remote_players"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RemotePlayers> STREAM_CODEC = StreamCodec.composite(
                STRINGS, RemotePlayers::names,
                RemotePlayers::new);

        @Override
        public Type<RemotePlayers> type() {
            return TYPE;
        }
    }

    /**
     * Client -> serveur : reaction a un message ({@code key} : empreinte du message calculee par le client,
     * {@code emoji} : "like", "laugh", "love", "wow" ou "sad" ; {@code add} : ajout ou retrait).
     */
    public record React(String key, String emoji, boolean add) implements CustomPacketPayload {
        public static final Type<React> TYPE = new Type<>(id("react"));
        public static final StreamCodec<RegistryFriendlyByteBuf, React> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), React::key,
                ByteBufCodecs.stringUtf8(16), React::emoji,
                ByteBufCodecs.BOOL, React::add,
                React::new);

        @Override
        public Type<React> type() {
            return TYPE;
        }
    }

    /** Serveur -> client : un joueur (de n'importe quel serveur) a reagi a un message, ou a retire sa reaction. */
    public record Reaction(String key, String emoji, String reactor, boolean add) implements CustomPacketPayload {
        public static final Type<Reaction> TYPE = new Type<>(id("reaction"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Reaction> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Reaction::key,
                ByteBufCodecs.STRING_UTF8, Reaction::emoji,
                ByteBufCodecs.STRING_UTF8, Reaction::reactor,
                ByteBufCodecs.BOOL, Reaction::add,
                Reaction::new);

        @Override
        public Type<Reaction> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- panel staff

    /** Client -> serveur : ouverture (ou relecture) du panel staff ; le serveur repond par {@link PanelData}. */
    public record PanelRequest() implements CustomPacketPayload {
        public static final PanelRequest INSTANCE = new PanelRequest();
        public static final Type<PanelRequest> TYPE = new Type<>(id("panel_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PanelRequest> STREAM_CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<PanelRequest> type() {
            return TYPE;
        }
    }

    /** Mode lent (secondes, 0 = non) et verrou d'un serveur, ou de tous ({@code scope} = "*"). */
    public record RuleEntry(String scope, int slowSeconds, boolean locked) {
        public static final StreamCodec<RegistryFriendlyByteBuf, RuleEntry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, RuleEntry::scope,
                ByteBufCodecs.VAR_INT, RuleEntry::slowSeconds,
                ByteBufCodecs.BOOL, RuleEntry::locked,
                RuleEntry::new);
    }

    /** Annonce automatique ({@code servers} : "*" ou identifiants separes par des virgules). */
    public record AnnouncementEntry(long id, String message, int intervalMinutes, String servers, boolean discord,
                                    boolean enabled) {
        public static final StreamCodec<RegistryFriendlyByteBuf, AnnouncementEntry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, AnnouncementEntry::id,
                ByteBufCodecs.STRING_UTF8, AnnouncementEntry::message,
                ByteBufCodecs.VAR_INT, AnnouncementEntry::intervalMinutes,
                ByteBufCodecs.STRING_UTF8, AnnouncementEntry::servers,
                ByteBufCodecs.BOOL, AnnouncementEntry::discord,
                ByteBufCodecs.BOOL, AnnouncementEntry::enabled,
                AnnouncementEntry::new);
    }

    /** Mot filtre : masque (etoiles) ou message bloque. */
    public record FilterEntry(String word, boolean block) {
        public static final StreamCodec<RegistryFriendlyByteBuf, FilterEntry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, FilterEntry::word,
                ByteBufCodecs.BOOL, FilterEntry::block,
                FilterEntry::new);
    }

    /** Message epingle d'une portee ("*" ou identifiant de serveur). */
    public record PinEntry(String scope, String message) {
        public static final StreamCodec<RegistryFriendlyByteBuf, PinEntry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, PinEntry::scope,
                ByteBufCodecs.STRING_UTF8, PinEntry::message,
                PinEntry::new);
    }

    /** Signalement en attente ({@code count} : nombre de signalements regroupes). */
    public record ReportEntry(long id, String target, String reporter, String reason, String serverId, long createdAt,
                              int count) {
        // Ecrit a la main : StreamCodec.composite s'arrete a six champs.
        public static final StreamCodec<RegistryFriendlyByteBuf, ReportEntry> STREAM_CODEC = StreamCodec.of(
                (buf, r) -> {
                    buf.writeVarLong(r.id());
                    buf.writeUtf(r.target());
                    buf.writeUtf(r.reporter());
                    buf.writeUtf(r.reason());
                    buf.writeUtf(r.serverId());
                    buf.writeVarLong(r.createdAt());
                    buf.writeVarInt(r.count());
                },
                buf -> new ReportEntry(buf.readVarLong(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                        buf.readVarLong(), buf.readVarInt()));
    }

    /**
     * Serveur -> client : contenu du panel staff. {@code access} : sections permises au joueur ({@link #PANEL_RULES},
     * {@link #PANEL_ANNOUNCEMENTS}, {@link #PANEL_FILTERS}, {@link #PANEL_PINS}, {@link #PANEL_REPORTS}).
     */
    public record PanelData(int access, List<ServerInfo> servers, List<RuleEntry> rules,
                            List<AnnouncementEntry> announcements, List<FilterEntry> filters, List<PinEntry> pins,
                            List<ReportEntry> reports) implements CustomPacketPayload {
        public static final Type<PanelData> TYPE = new Type<>(id("panel_data"));
        // Ecrit a la main : StreamCodec.composite s'arrete a six champs.
        public static final StreamCodec<RegistryFriendlyByteBuf, PanelData> STREAM_CODEC = StreamCodec.of(
                (buf, d) -> {
                    buf.writeVarInt(d.access());
                    ServerInfo.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, d.servers());
                    RuleEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, d.rules());
                    AnnouncementEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, d.announcements());
                    FilterEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, d.filters());
                    PinEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, d.pins());
                    ReportEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, d.reports());
                },
                buf -> new PanelData(buf.readVarInt(),
                        ServerInfo.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
                        RuleEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
                        AnnouncementEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
                        FilterEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
                        PinEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
                        ReportEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf)));

        @Override
        public Type<PanelData> type() {
            return TYPE;
        }
    }

    public static final int PANEL_RULES = 1;
    public static final int PANEL_ANNOUNCEMENTS = 2;
    public static final int PANEL_FILTERS = 4;
    public static final int PANEL_PINS = 8;
    public static final int PANEL_REPORTS = 16;

    /**
     * Client -> serveur : action du panel staff. {@code kind} et {@code args} :
     * "rule" [portee, secondes, verrou], "announce_add" [message, minutes, serveurs, discord],
     * "announce_toggle" [id, actif], "announce_delete" [id], "filter_set" [mot, bloquer], "filter_delete" [mot],
     * "pin_set" [portee, message], "pin_delete" [portee], "report" [id, "ignore" | "mute" | "ban"].
     */
    public record PanelAction(String kind, List<String> args) implements CustomPacketPayload {
        public static final Type<PanelAction> TYPE = new Type<>(id("panel_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PanelAction> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(32), PanelAction::kind,
                ByteBufCodecs.stringUtf8(512).apply(ByteBufCodecs.list(8)), PanelAction::args,
                PanelAction::new);

        @Override
        public Type<PanelAction> type() {
            return TYPE;
        }
    }

    /** Serveur -> client : messages epingles en haut du chat (liste vide : aucun). */
    public record Pinned(List<Component> lines) implements CustomPacketPayload {
        public static final Type<Pinned> TYPE = new Type<>(id("pinned"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Pinned> STREAM_CODEC = StreamCodec.composite(
                ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list(4)), Pinned::lines,
                Pinned::new);

        @Override
        public Type<Pinned> type() {
            return TYPE;
        }
    }

    // ---------------------------------------------------------------- ecran facon Discord

    /** Une reaction a un message conserve, et les joueurs qui l'ont mise. */
    public record ReactionEntry(String emoji, List<String> names) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ReactionEntry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ReactionEntry::emoji,
                STRINGS, ReactionEntry::names,
                ReactionEntry::new);
    }

    /**
     * Message conserve en base. {@code channel} : "global", "local", "staff", "#canal", "discord:salon" ou "@correspondant"
     * (vu par celui qui le recoit). {@code origin} : identifiant du serveur d'origine. {@code lang} : langue detectee
     * ("fr", "en", ou vide) ; {@code translated} : le message dans l'autre langue (vide s'il n'y en a pas).
     */
    public record StoredMessage(long id, String channel, String sender, String prefix, String origin, boolean discord,
                                String raw, Component body, long replyId, String replySender, String replyText,
                                long createdAt, long editedAt, boolean deleted, List<ReactionEntry> reactions, String lang,
                                Component translated) {
        public static final StreamCodec<RegistryFriendlyByteBuf, StoredMessage> STREAM_CODEC = StreamCodec.of(
                (buf, m) -> {
                    buf.writeVarLong(m.id());
                    buf.writeUtf(m.channel());
                    buf.writeUtf(m.sender());
                    buf.writeUtf(m.prefix());
                    buf.writeUtf(m.origin());
                    buf.writeBoolean(m.discord());
                    buf.writeUtf(m.raw());
                    ComponentSerialization.STREAM_CODEC.encode(buf, m.body());
                    buf.writeVarLong(m.replyId());
                    buf.writeUtf(m.replySender());
                    buf.writeUtf(m.replyText());
                    buf.writeVarLong(m.createdAt());
                    buf.writeVarLong(m.editedAt());
                    buf.writeBoolean(m.deleted());
                    ReactionEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, m.reactions());
                    buf.writeUtf(m.lang());
                    ComponentSerialization.STREAM_CODEC.encode(buf, m.translated());
                },
                buf -> new StoredMessage(buf.readVarLong(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                        buf.readBoolean(), buf.readUtf(), ComponentSerialization.STREAM_CODEC.decode(buf),
                        buf.readVarLong(), buf.readUtf(), buf.readUtf(), buf.readVarLong(), buf.readVarLong(),
                        buf.readBoolean(), ReactionEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf), buf.readUtf(),
                        ComponentSerialization.STREAM_CODEC.decode(buf)));
    }

    /** Un salon du joueur : non-lus, et premier message non lu ({@code firstUnread} : 0 s'il n'y en a pas). */
    public record RoomEntry(String channel, String label, boolean readOnly, int unread, long firstUnread) {
        public static final StreamCodec<RegistryFriendlyByteBuf, RoomEntry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, RoomEntry::channel,
                ByteBufCodecs.STRING_UTF8, RoomEntry::label,
                ByteBufCodecs.BOOL, RoomEntry::readOnly,
                ByteBufCodecs.VAR_INT, RoomEntry::unread,
                ByteBufCodecs.VAR_LONG, RoomEntry::firstUnread,
                RoomEntry::new);
    }

    /** Serveur -> client : les salons conserves du joueur (a la connexion et quand ils changent). */
    public record Rooms(List<RoomEntry> rooms) implements CustomPacketPayload {
        public static final Type<Rooms> TYPE = new Type<>(id("rooms"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Rooms> STREAM_CODEC = StreamCodec.composite(
                RoomEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), Rooms::rooms,
                Rooms::new);

        @Override
        public Type<Rooms> type() {
            return TYPE;
        }
    }

    /** Serveur -> client : un message nouveau, modifie ou supprime. */
    public record MessagePush(StoredMessage message) implements CustomPacketPayload {
        public static final Type<MessagePush> TYPE = new Type<>(id("message_push"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MessagePush> STREAM_CODEC = StreamCodec.composite(
                StoredMessage.STREAM_CODEC, MessagePush::message,
                MessagePush::new);

        @Override
        public Type<MessagePush> type() {
            return TYPE;
        }
    }

    /** Client -> serveur : messages d'un salon avant {@code beforeId} (0 : les plus recents), filtres par {@code query}. */
    public record HistoryRequest(String channel, long beforeId, String query) implements CustomPacketPayload {
        public static final Type<HistoryRequest> TYPE = new Type<>(id("history_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, HistoryRequest> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), HistoryRequest::channel,
                ByteBufCodecs.VAR_LONG, HistoryRequest::beforeId,
                ByteBufCodecs.stringUtf8(64), HistoryRequest::query,
                HistoryRequest::new);

        @Override
        public Type<HistoryRequest> type() {
            return TYPE;
        }
    }

    /** Serveur -> client : une page de messages, du plus ancien au plus recent ; {@code more} : il en reste avant. */
    public record HistoryPage(String channel, long beforeId, String query, List<StoredMessage> messages, boolean more)
            implements CustomPacketPayload {
        public static final Type<HistoryPage> TYPE = new Type<>(id("history_page"));
        public static final StreamCodec<RegistryFriendlyByteBuf, HistoryPage> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, HistoryPage::channel,
                ByteBufCodecs.VAR_LONG, HistoryPage::beforeId,
                ByteBufCodecs.STRING_UTF8, HistoryPage::query,
                StoredMessage.STREAM_CODEC.apply(ByteBufCodecs.list()), HistoryPage::messages,
                ByteBufCodecs.BOOL, HistoryPage::more,
                HistoryPage::new);

        @Override
        public Type<HistoryPage> type() {
            return TYPE;
        }
    }

    /**
     * Client -> serveur : action sur un salon ou un message. {@code kind} : "reply" (le prochain message du joueur
     * repond a {@code id}), "edit" ({@code text} : le nouveau texte), "delete", "react" et "unreact" ({@code text} : la
     * reaction), "read" (lu jusqu'a {@code id} dans {@code channel}), "close" (ferme la conversation {@code channel}).
     */
    public record RoomAction(String kind, String channel, long id, String text) implements CustomPacketPayload {
        public static final Type<RoomAction> TYPE = new Type<>(TabsProtocol.id("room_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RoomAction> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(16), RoomAction::kind,
                ByteBufCodecs.stringUtf8(64), RoomAction::channel,
                ByteBufCodecs.VAR_LONG, RoomAction::id,
                ByteBufCodecs.stringUtf8(512), RoomAction::text,
                RoomAction::new);

        @Override
        public Type<RoomAction> type() {
            return TYPE;
        }
    }

    /** Les joueurs connectes a un serveur. */
    public record ServerPlayers(String id, String name, List<String> names) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ServerPlayers> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ServerPlayers::id,
                ByteBufCodecs.STRING_UTF8, ServerPlayers::name,
                STRINGS, ServerPlayers::names,
                ServerPlayers::new);
    }

    /** Serveur -> client : qui est connecte, serveur par serveur (envoye quand ca change). */
    public record Presence(List<ServerPlayers> servers) implements CustomPacketPayload {
        public static final Type<Presence> TYPE = new Type<>(id("presence"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Presence> STREAM_CODEC = StreamCodec.composite(
                ServerPlayers.STREAM_CODEC.apply(ByteBufCodecs.list()), Presence::servers,
                Presence::new);

        @Override
        public Type<Presence> type() {
            return TYPE;
        }
    }

    /** Un salon vocal : prive ou non, et les joueurs qui s'y trouvent ({@code memberIds} : leurs UUID, dans le meme ordre). */
    /** Une sanction de l'historique d'un joueur : BAN, MUTE ou WARN ; {@code expiresAt} vaut 0 si elle est definitive. */
    public record HistoryEntry(String type, String reason, String actor, long createdAt, long expiresAt, boolean inEffect) {
        public static final StreamCodec<RegistryFriendlyByteBuf, HistoryEntry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, HistoryEntry::type,
                ByteBufCodecs.STRING_UTF8, HistoryEntry::reason,
                ByteBufCodecs.STRING_UTF8, HistoryEntry::actor,
                ByteBufCodecs.VAR_LONG, HistoryEntry::createdAt,
                ByteBufCodecs.VAR_LONG, HistoryEntry::expiresAt,
                ByteBufCodecs.BOOL, HistoryEntry::inEffect,
                HistoryEntry::new);
    }

    /** Client -> serveur : l'historique de ce joueur (reserve au staff). */
    public record PlayerHistoryRequest(String name) implements CustomPacketPayload {
        public static final Type<PlayerHistoryRequest> TYPE = new Type<>(id("player_history_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PlayerHistoryRequest> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(16), PlayerHistoryRequest::name,
                PlayerHistoryRequest::new);

        @Override
        public Type<PlayerHistoryRequest> type() {
            return TYPE;
        }
    }

    /** Serveur -> client : les sanctions du joueur, les plus recentes d'abord ({@code found} faux s'il est inconnu). */
    public record PlayerHistory(String name, boolean found, List<HistoryEntry> entries) implements CustomPacketPayload {
        public static final Type<PlayerHistory> TYPE = new Type<>(id("player_history"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PlayerHistory> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, PlayerHistory::name,
                ByteBufCodecs.BOOL, PlayerHistory::found,
                HistoryEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), PlayerHistory::entries,
                PlayerHistory::new);

        @Override
        public Type<PlayerHistory> type() {
            return TYPE;
        }
    }

    /** Etat des services : 0 sans le service sur ce serveur, 1 en marche, 2 injoignable. */
    public record ServiceStatus(int translator, int relay) implements CustomPacketPayload {
        public static final Type<ServiceStatus> TYPE = new Type<>(id("service_status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ServiceStatus> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ServiceStatus::translator,
                ByteBufCodecs.VAR_INT, ServiceStatus::relay,
                ServiceStatus::new);

        @Override
        public Type<ServiceStatus> type() {
            return TYPE;
        }
    }

    public record VoiceRoomEntry(String name, boolean isPrivate, List<String> members, List<String> memberIds, boolean temporary) {
        public static final StreamCodec<RegistryFriendlyByteBuf, VoiceRoomEntry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, VoiceRoomEntry::name,
                ByteBufCodecs.BOOL, VoiceRoomEntry::isPrivate,
                STRINGS, VoiceRoomEntry::members,
                STRINGS, VoiceRoomEntry::memberIds,
                ByteBufCodecs.BOOL, VoiceRoomEntry::temporary,
                VoiceRoomEntry::new);
    }

    /** Un joueur connecte : son prefixe de grade, le poids de ce grade (pour l'ordre) et s'il est absent. */
    public record RosterEntry(String name, String prefix, int weight, boolean afk) {
        public static final StreamCodec<RegistryFriendlyByteBuf, RosterEntry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, RosterEntry::name,
                ByteBufCodecs.STRING_UTF8, RosterEntry::prefix,
                ByteBufCodecs.VAR_INT, RosterEntry::weight,
                ByteBufCodecs.BOOL, RosterEntry::afk,
                RosterEntry::new);
    }

    /** Serveur -> client : les joueurs connectes sur tous les serveurs, avec leur grade. */
    public record Roster(List<RosterEntry> players) implements CustomPacketPayload {
        public static final Type<Roster> TYPE = new Type<>(id("roster"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Roster> STREAM_CODEC = StreamCodec.composite(
                RosterEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), Roster::players,
                Roster::new);

        @Override
        public Type<Roster> type() {
            return TYPE;
        }
    }

    /**
     * Serveur -> client : les salons vocaux que le joueur peut voir, et celui ou il est. {@code crossServer} : on y entend aussi
     * les joueurs des autres serveurs. Jamais envoye sans le mod de vocal.
     */
    public record VoiceState(boolean manager, boolean crossServer, String current, List<VoiceRoomEntry> rooms) implements CustomPacketPayload {
        public static final Type<VoiceState> TYPE = new Type<>(TabsProtocol.id("voice_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, VoiceState> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, VoiceState::manager,
                ByteBufCodecs.BOOL, VoiceState::crossServer,
                ByteBufCodecs.STRING_UTF8, VoiceState::current,
                VoiceRoomEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), VoiceState::rooms,
                VoiceState::new);

        @Override
        public Type<VoiceState> type() {
            return TYPE;
        }
    }
}
