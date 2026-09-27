package com.arcadia.bridge.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Optional;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Mods de mise en forme du chat qui diffusent eux-memes le message formate puis annulent l'evenement
 * d'origine. En leur presence, un message annule n'est pas un message bloque : il a deja ete diffuse.
 */
public final class ChatFormatterCompat {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** LPC (LuckPerms Chat) : formate avec le prefixe LuckPerms, en priorite HIGHEST. */
    private static final String LPC = "lpcmod";
    private static final List<String> REBROADCASTING_MODS = List.of(LPC);

    private static Boolean present;
    /** LPCEvents.onServerChat(ServerPlayer, Component) : Pair<Boolean, Component> ; null si indisponible. */
    private static MethodHandle lpcFormat;
    private static boolean lpcLookedUp;

    private ChatFormatterCompat() {
    }

    public static boolean rebroadcastsChat() {
        if (present == null) {
            present = REBROADCASTING_MODS.stream().anyMatch(ModList.get()::isLoaded);
        }
        return present;
    }

    /**
     * La ligne de chat telle que LPC l'affiche sur ce serveur (prefixe, couleurs, format), pour la
     * reproduire a l'identique sur les autres serveurs. Vide si LPC est absent ou ne formate pas.
     */
    @SuppressWarnings("unchecked")
    public static Optional<Component> format(ServerPlayer player, Component message) {
        MethodHandle handle = lpcHandle();
        if (handle == null) {
            return Optional.empty();
        }
        try {
            // Meme entree que LPC lui-meme : "<pseudo> " suivi du message. LPC retire tout ce qui precede
            // le premier "> " : sans l'espace, le "<pseudo>" resterait colle au message.
            Component input = Component.literal("<" + player.getName().getString() + "> ").append(message);
            Pair<Boolean, Component> result = (Pair<Boolean, Component>) handle.invoke(player, input);
            if (result != null && Boolean.TRUE.equals(result.getFirst()) && result.getSecond() != null) {
                return Optional.of(result.getSecond());
            }
        } catch (Throwable e) {
            LOGGER.warn("[ArcadiaBridge] Mise en forme LPC indisponible, format par defaut utilise : {}", e.toString());
            lpcFormat = null;
        }
        return Optional.empty();
    }

    private static synchronized MethodHandle lpcHandle() {
        if (!lpcLookedUp) {
            lpcLookedUp = true;
            if (ModList.get().isLoaded(LPC)) {
                try {
                    Class<?> events = Class.forName("com.lpcneoforge.lpcmod.server.LPCEvents", true,
                            ChatFormatterCompat.class.getClassLoader());
                    lpcFormat = MethodHandles.publicLookup().findStatic(events, "onServerChat",
                            MethodType.methodType(Pair.class, ServerPlayer.class, Component.class));
                } catch (ReflectiveOperationException | LinkageError e) {
                    LOGGER.warn("[ArcadiaBridge] Version de LPC non reconnue, chat inter-serveurs sans ses couleurs : {}",
                            e.toString());
                }
            }
        }
        return lpcFormat;
    }
}
