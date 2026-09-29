package com.arcadia.bridge.tabs;

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
    public static final String VERSION = "1";

    /** Canaux d'un message. Le client decide dans quels onglets il apparait. */
    public static final String CHANNEL_GLOBAL = "global";
    public static final String CHANNEL_LOCAL = "local";
    public static final String CHANNEL_DISCORD = "discord";
    public static final String CHANNEL_PRIVATE = "private";

    private TabsProtocol() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, path);
    }

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
    public record Hello(String serverName, boolean crossServer, boolean writesGlobal) implements CustomPacketPayload {
        public static final Type<Hello> TYPE = new Type<>(id("hello"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Hello> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Hello::serverName,
                ByteBufCodecs.BOOL, Hello::crossServer,
                ByteBufCodecs.BOOL, Hello::writesGlobal,
                Hello::new);

        @Override
        public Type<Hello> type() {
            return TYPE;
        }
    }

    /** Serveur -> client : une ligne de chat et son canal ({@code origin} : serveur d'origine, ou vide). */
    public record Line(String channel, String origin, Component message) implements CustomPacketPayload {
        public static final Type<Line> TYPE = new Type<>(id("line"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Line> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Line::channel,
                ByteBufCodecs.STRING_UTF8, Line::origin,
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
}
