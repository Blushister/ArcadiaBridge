package com.arcadia.bridge.compat;

import java.util.UUID;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
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

    /** Poids du groupe principal (les grades les plus hauts d'abord), 0 sans LuckPerms. */
    public static int weight(UUID uuid) {
        if (available == null) {
            available = ModList.get().isLoaded("luckperms");
        }
        if (!available) {
            return 0;
        }
        try {
            return Internal.weight(uuid);
        } catch (Throwable t) {
            return 0;
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

        static int weight(UUID uuid) {
            User user = LuckPermsProvider.get().getUserManager().getUser(uuid);
            if (user == null) {
                return 0;
            }
            Group group = LuckPermsProvider.get().getGroupManager().getGroup(user.getPrimaryGroup());
            return group == null ? 0 : group.getWeight().orElse(0);
        }
    }
}
