package com.arcadia.bridge.permission;

import com.arcadia.bridge.ArcadiaBridge;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

/**
 * Noeuds de permission du mod. Ils passent par l'API de permissions NeoForge, que LuckPerms prend en
 * charge : on peut donc les donner a un grade (ex : arcadiabridge.command.mute aux modos).
 * Sans LuckPerms, les commandes staff sont reservees aux operateurs (niveau 2).
 */
public final class Permissions {

    public static final PermissionNode<Boolean> BAN = staff("command.ban");
    public static final PermissionNode<Boolean> MUTE = staff("command.mute");
    public static final PermissionNode<Boolean> WARN = staff("command.warn");
    public static final PermissionNode<Boolean> HISTORY = staff("command.history");
    public static final PermissionNode<Boolean> UNLINK = staff("command.unlink");
    public static final PermissionNode<Boolean> ADMIN = new PermissionNode<>(ArcadiaBridge.MOD_ID, "command.admin",
            PermissionTypes.BOOLEAN, (player, uuid, ctx) -> player != null && player.hasPermissions(3));
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
        event.addNodes(BAN, MUTE, WARN, HISTORY, UNLINK, ADMIN, VERIFICATION_BYPASS);
    }

    public static boolean has(ServerPlayer player, PermissionNode<Boolean> node) {
        return PermissionAPI.getPermission(player, node);
    }

    /** Pour Brigadier : la console et les blocs de commande (niveau 2+) sont toujours autorises. */
    public static boolean has(CommandSourceStack source, PermissionNode<Boolean> node) {
        ServerPlayer player = source.getPlayer();
        return player == null ? source.hasPermission(2) : has(player, node);
    }
}
