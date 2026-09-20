package com.arcadia.bridge.minecraft;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.compat.LuckPermsHook;
import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.discord.ChatWebhook;
import com.arcadia.bridge.i18n.Lang;
import com.arcadia.bridge.permission.Permissions;
import com.arcadia.bridge.sanction.SanctionService;
import com.arcadia.bridge.util.Text;
import com.mojang.brigadier.context.ParsedCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;

/** Evenements du jeu (bus NeoForge). */
public final class GameEvents {

    /** Joueurs dont l'arrivee a ete annoncee sur Discord (pour annoncer leur depart de facon coherente). */
    private static final Set<UUID> ANNOUNCED = ConcurrentHashMap.newKeySet();
    private static int tickCounter;

    private GameEvents() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        BridgeCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onPermissionNodes(PermissionGatherEvent.Nodes event) {
        Permissions.register(event);
    }

    // ---------------------------------------------------------------- connexion

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        Bridge bridge = Bridge.get();
        if (bridge == null || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // Filet de securite : normalement bloque avant l'entree en jeu (PlayerListMixin).
        var ban = bridge.cache().activeBan(player.getUUID());
        if (ban.isPresent()) {
            player.connection.disconnect(SanctionService.banScreen(ban.get()));
            return;
        }
        if (bridge.repository() != null) {
            bridge.repository().upsertPlayer(player.getUUID(), player.getGameProfile().getName(), Bridge.serverId());
        }
        bridge.cache().activeMute(player.getUUID())
                .ifPresent(mute -> player.sendSystemMessage(SanctionService.muteMessage(mute)));

        boolean mustVerify = BridgeConfig.VERIFICATION_ENABLED.get() && !bridge.cache().isLinked(player.getUUID());
        // En mode "kick", un joueur non verifie repart aussitot : inutile d'annoncer son arrivee.
        if (BridgeConfig.STATUS_JOIN_LEAVE.get() && !(mustVerify && BridgeConfig.isKickMode())) {
            ANNOUNCED.add(player.getUUID());
            bridge.discord().sendChat(Lang.get("discord.status.join", "name", Text.forDiscord(player.getGameProfile().getName())));
        }
        bridge.verification().onJoin(player);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Bridge bridge = Bridge.get();
        if (bridge == null || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        bridge.verification().onLeave(player);
        if (ANNOUNCED.remove(player.getUUID()) && BridgeConfig.STATUS_JOIN_LEAVE.get() && !bridge.isStopping()) {
            bridge.discord().sendChat(Lang.get("discord.status.leave", "name", Text.forDiscord(player.getGameProfile().getName())));
        }
    }

    // ---------------------------------------------------------------- chat

    /** Blocage (mute, non verifie) : priorite haute pour passer avant les autres mods de chat. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onChatBlock(ServerChatEvent event) {
        Bridge bridge = Bridge.get();
        if (bridge == null) {
            return;
        }
        ServerPlayer player = event.getPlayer();
        var mute = bridge.cache().activeMute(player.getUUID());
        if (mute.isPresent()) {
            event.setCanceled(true);
            player.sendSystemMessage(SanctionService.muteMessage(mute.get()));
            return;
        }
        if (BridgeConfig.VERIFICATION_BLOCK_CHAT.get() && bridge.verification().isPending(player.getUUID())) {
            event.setCanceled(true);
            player.sendSystemMessage(Lang.mc("verify.chat_blocked"));
        }
    }

    /** Relais vers Discord : en dernier, et seulement si aucun mod n'a annule le message. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onChatRelay(ServerChatEvent event) {
        Bridge bridge = Bridge.get();
        if (bridge == null || !BridgeConfig.CHAT_TO_DISCORD.get() || !bridge.discord().isReady()) {
            return;
        }
        ServerPlayer player = event.getPlayer();
        String rawPrefix = BridgeConfig.CHAT_LUCKPERMS_PREFIX.get() ? LuckPermsHook.prefix(player.getUUID()) : "";
        String prefix = Text.forDiscord(rawPrefix);
        String name = Text.forDiscord(player.getGameProfile().getName());
        String message = Text.forDiscord(event.getRawText());

        if (ChatWebhook.isConfigured()) {
            // Avec un webhook, le pseudo et la tete du joueur portent l'information : pas de mise en forme.
            String display = (Text.stripFormatting(rawPrefix).trim() + " " + player.getGameProfile().getName()).trim();
            if (ChatWebhook.send(player.getUUID(), display, message)) {
                return;
            }
        }
        String serverName = BridgeConfig.SERVER_NAME.get();
        String line = Text.fill(BridgeConfig.CHAT_DISCORD_FORMAT.get(),
                "prefix", prefix.isBlank() ? "" : prefix.trim() + " ",
                "name", name,
                "server", serverName.isBlank() ? "" : Text.forDiscord(serverName),
                "message", message);
        bridge.discord().sendChat(line);
    }

    /** Commandes de message privees interdites aux joueurs mutes (/msg, /tell, /me...). */
    @SubscribeEvent
    public static void onCommand(CommandEvent event) {
        Bridge bridge = Bridge.get();
        if (bridge == null) {
            return;
        }
        CommandSourceStack source = event.getParseResults().getContext().getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return;
        }
        var mute = bridge.cache().activeMute(player.getUUID());
        if (mute.isEmpty()) {
            return;
        }
        List<ParsedCommandNode<CommandSourceStack>> nodes = event.getParseResults().getContext().getNodes();
        if (nodes.isEmpty()) {
            return;
        }
        String root = nodes.get(0).getNode().getName().toLowerCase(Locale.ROOT);
        if (BridgeConfig.SANCTIONS_MUTE_BLOCKED_COMMANDS.get().contains(root)) {
            event.setCanceled(true);
            player.sendSystemMessage(SanctionService.muteMessage(mute.get()));
        }
    }

    // ---------------------------------------------------------------- divers

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        Bridge bridge = Bridge.get();
        if (bridge == null || !BridgeConfig.STATUS_DEATHS.get() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        String message = event.getSource().getLocalizedDeathMessage(player).getString();
        bridge.discord().sendChat(Lang.get("discord.status.death", "message", Text.forDiscord(message)));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Bridge bridge = Bridge.get();
        if (bridge == null || ++tickCounter < 20) {
            return;
        }
        tickCounter = 0;
        bridge.verification().tick();
        bridge.updateOnlineSnapshot();
    }
}
