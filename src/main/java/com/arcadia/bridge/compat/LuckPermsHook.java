package com.arcadia.bridge.compat;

import java.util.UUID;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.neoforged.fml.ModList;

/** Prefixe LuckPerms d'un joueur. Ne touche aux classes LuckPerms que si le mod est installe. */
public final class LuckPermsHook {

    private static Boolean available;

    private LuckPermsHook() {
    }

    public static String prefix(UUID uuid) {
        if (available == null) {
            available = ModList.get().isLoaded("luckperms");
        }
        if (!available) {
            return "";
        }
        try {
            return Internal.prefix(uuid);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Classe separee : chargee uniquement quand LuckPerms est present. */
    private static final class Internal {
        static String prefix(UUID uuid) {
            User user = LuckPermsProvider.get().getUserManager().getUser(uuid);
            if (user == null) {
                return "";
            }
            String prefix = user.getCachedData().getMetaData().getPrefix();
            return prefix == null ? "" : prefix;
        }
    }
}
