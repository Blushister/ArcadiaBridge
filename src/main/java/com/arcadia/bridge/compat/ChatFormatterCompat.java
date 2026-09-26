package com.arcadia.bridge.compat;

import java.util.List;

import net.neoforged.fml.ModList;

/**
 * Mods de mise en forme du chat qui diffusent eux-memes le message formate puis annulent l'evenement
 * d'origine. En leur presence, un message annule n'est pas un message bloque : il a deja ete diffuse.
 */
public final class ChatFormatterCompat {

    /** LPC (LuckPerms Chat) : formate avec le prefixe LuckPerms, en priorite HIGHEST. */
    private static final List<String> REBROADCASTING_MODS = List.of("lpcmod");

    private static Boolean present;

    private ChatFormatterCompat() {
    }

    public static boolean rebroadcastsChat() {
        if (present == null) {
            present = REBROADCASTING_MODS.stream().anyMatch(ModList.get()::isLoaded);
        }
        return present;
    }
}
