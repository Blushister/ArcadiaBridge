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
}
