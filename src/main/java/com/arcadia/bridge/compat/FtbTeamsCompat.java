package com.arcadia.bridge.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;
import java.util.UUID;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Chat d'equipe par FTB Teams (facultatif), via son API publique et sans dependance de compilation : sans
 * FTB Teams, ou si son API change, le chat d'equipe est simplement indisponible.
 */
public final class FtbTeamsCompat {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean lookedUp;
    private static MethodHandle api;
    private static MethodHandle getManager;
    private static MethodHandle getTeamForPlayer;
    private static MethodHandle isPartyTeam;
    private static MethodHandle sendMessage;

    private FtbTeamsCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("ftbteams") && handles();
    }

    /**
     * Envoie un message a l'equipe (groupe) du joueur, comme /ftbteams msg : FTB Teams le diffuse aux membres
     * connectes et le garde dans son historique.
     *
     * @return false si le joueur n'est dans aucune equipe de groupe
     */
    @SuppressWarnings("unchecked")
    public static boolean sendToTeam(ServerPlayer player, Component text) {
        if (!isLoaded()) {
            return false;
        }
        try {
            Object manager = getManager.invoke(api.invoke());
            Optional<Object> team = (Optional<Object>) getTeamForPlayer.invoke(manager, player);
            if (team.isEmpty() || !(boolean) isPartyTeam.invoke(team.get())) {
                return false;
            }
            sendMessage.invoke(team.get(), player.getUUID(), text);
            return true;
        } catch (Throwable e) {
            LOGGER.warn("[ArcadiaBridge] Chat d'equipe FTB Teams indisponible : {}", e.toString());
            return false;
        }
    }

    private static synchronized boolean handles() {
        if (!lookedUp) {
            lookedUp = true;
            try {
                ClassLoader loader = FtbTeamsCompat.class.getClassLoader();
                Class<?> apiClass = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI", true, loader);
                Class<?> apiIface = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI$API", true, loader);
                Class<?> managerIface = Class.forName("dev.ftb.mods.ftbteams.api.TeamManager", true, loader);
                Class<?> teamIface = Class.forName("dev.ftb.mods.ftbteams.api.Team", true, loader);
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                api = lookup.findStatic(apiClass, "api", MethodType.methodType(apiIface));
                getManager = lookup.findVirtual(apiIface, "getManager", MethodType.methodType(managerIface));
                getTeamForPlayer = lookup.findVirtual(managerIface, "getTeamForPlayer",
                        MethodType.methodType(Optional.class, ServerPlayer.class));
                isPartyTeam = lookup.findVirtual(teamIface, "isPartyTeam", MethodType.methodType(boolean.class));
                sendMessage = lookup.findVirtual(teamIface, "sendMessage",
                        MethodType.methodType(void.class, UUID.class, Component.class));
            } catch (ReflectiveOperationException | LinkageError e) {
                LOGGER.warn("[ArcadiaBridge] Version de FTB Teams non reconnue, pas de chat d'equipe : {}", e.toString());
                api = null;
            }
        }
        return api != null;
    }
}
