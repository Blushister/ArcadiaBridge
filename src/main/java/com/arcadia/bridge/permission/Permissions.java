package com.arcadia.bridge.permission;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.arcadia.bridge.ArcadiaBridge;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import org.slf4j.Logger;

/**
 * Noeuds de permission du mod. Ils passent par l'API de permissions NeoForge, que LuckPerms prend en
 * charge : on peut donc les donner a un grade (ex : arcadiabridge.command.mute aux modos).
 * Sans LuckPerms, les commandes staff sont reservees aux operateurs (niveau 2).
 */
public final class Permissions {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Un seul message par noeud : ce repli est normal a chaque connexion. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    public static final PermissionNode<Boolean> BAN = staff("command.ban");
    public static final PermissionNode<Boolean> MUTE = staff("command.mute");
    public static final PermissionNode<Boolean> WARN = staff("command.warn");
    public static final PermissionNode<Boolean> HISTORY = staff("command.history");
    public static final PermissionNode<Boolean> UNLINK = staff("command.unlink");
    public static final PermissionNode<Boolean> ADMIN = new PermissionNode<>(ArcadiaBridge.MOD_ID, "command.admin",
            PermissionTypes.BOOLEAN, (player, uuid, ctx) -> player != null && player.hasPermissions(3));
    /** Chat du staff et alertes en jeu. */
    public static final PermissionNode<Boolean> STAFF_CHAT = staff("chat.staff");
    /** Creer et supprimer les canaux thematiques. */
    public static final PermissionNode<Boolean> CHANNELS_MANAGE = staff("chat.channels.manage");
    /** Dispense de verification Discord (personne par defaut). */
    public static final PermissionNode<Boolean> VERIFICATION_BYPASS = new PermissionNode<>(ArcadiaBridge.MOD_ID,
            "verification.bypass", PermissionTypes.BOOLEAN, (player, uuid, ctx) -> false);

    private Permissions() {
    }

    private static PermissionNode<Boolean> staff(String name) {
        return new PermissionNode<>(ArcadiaBridge.MOD_ID, name, PermissionTypes.BOOLEAN,
                (player, uuid, ctx) -> player != null && player.hasPermissions(2));
    }

    public static void register(PermissionGatherEvent.Nodes event) {
        event.addNodes(BAN, MUTE, WARN, HISTORY, UNLINK, ADMIN, STAFF_CHAT, CHANNELS_MANAGE, VERIFICATION_BYPASS);
    }

    /**
     * Interroge le gestionnaire de permissions, sans jamais laisser remonter d'exception.
     *
     * <p>A la connexion, le serveur construit l'arbre des commandes a envoyer au client et evalue donc
     * nos conditions d'acces. A cet instant, LuckPerms n'a pas encore charge les donnees du joueur et
     * leve {@code IllegalStateException} : laisser passer cette erreur faisait echouer la connexion
     * entiere ("Invalid player data"). On retombe alors sur le niveau d'operateur, et l'arbre des
     * commandes est de toute facon renvoye au client une fois les permissions chargees.
     */
    public static boolean has(ServerPlayer player, PermissionNode<Boolean> node) {
        try {
            return PermissionAPI.getPermission(player, node);
        } catch (RuntimeException e) {
            if (WARNED.add(node.getNodeName())) {
                LOGGER.debug("[ArcadiaBridge] Permission {} evaluee avant le chargement des donnees de {} : "
                        + "repli sur le niveau d'operateur.", node.getNodeName(), player.getGameProfile().getName());
            }
            return player.hasPermissions(2);
        }
    }

    /** Pour Brigadier : la console et les blocs de commande (niveau 2+) sont toujours autorises. */
    public static boolean has(CommandSourceStack source, PermissionNode<Boolean> node) {
        ServerPlayer player = source.getPlayer();
        return player == null ? source.hasPermission(2) : has(player, node);
    }
}
