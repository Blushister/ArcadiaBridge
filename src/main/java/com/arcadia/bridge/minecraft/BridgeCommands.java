package com.arcadia.bridge.minecraft;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.chat.ChatChannels;
import com.arcadia.bridge.chat.CrossServerChat;
import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.data.Models.PlayerRef;
import com.arcadia.bridge.data.Models.Sanction;
import com.arcadia.bridge.data.Models.SanctionType;
import com.arcadia.bridge.i18n.Lang;
import com.arcadia.bridge.permission.Permissions;
import com.arcadia.bridge.report.ReportService;
import com.arcadia.bridge.sanction.SanctionService;
import com.arcadia.bridge.sanction.SanctionService.Request;
import com.arcadia.bridge.util.Durations;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import org.slf4j.Logger;

/** Commandes en jeu. Les noms reprennent ceux de l'ancien mod pour ne pas changer les habitudes du staff. */
public final class BridgeCommands {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BridgeCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(staff("arcadia_ban", Permissions.BAN)
                .then(player()
                        .executes(ctx -> sanction(ctx, SanctionType.BAN, null, null))
                        .then(reason().executes(ctx -> sanction(ctx, SanctionType.BAN, null, arg(ctx, "reason"))))));

        d.register(staff("arcadia_tempban", Permissions.BAN)
                .then(player().then(duration()
                        .executes(ctx -> sanction(ctx, SanctionType.BAN, arg(ctx, "duration"), null))
                        .then(reason().executes(ctx -> sanction(ctx, SanctionType.BAN, arg(ctx, "duration"), arg(ctx, "reason")))))));

        d.register(staff("arcadia_unban", Permissions.BAN)
                .then(player().executes(ctx -> revoke(ctx, SanctionType.BAN))));

        d.register(staff("arcadia_mute", Permissions.MUTE)
                .then(player()
                        .executes(ctx -> sanction(ctx, SanctionType.MUTE, null, null))
                        .then(reason().executes(ctx -> sanction(ctx, SanctionType.MUTE, null, arg(ctx, "reason"))))));

        d.register(staff("arcadia_tempmute", Permissions.MUTE)
                .then(player().then(duration()
                        .executes(ctx -> sanction(ctx, SanctionType.MUTE, arg(ctx, "duration"), null))
                        .then(reason().executes(ctx -> sanction(ctx, SanctionType.MUTE, arg(ctx, "duration"), arg(ctx, "reason")))))));

        d.register(staff("arcadia_unmute", Permissions.MUTE)
                .then(player().executes(ctx -> revoke(ctx, SanctionType.MUTE))));

        d.register(staff("arcadia_warn", Permissions.WARN)
                .then(player().then(reason().executes(ctx -> sanction(ctx, SanctionType.WARN, null, arg(ctx, "reason"))))));

        d.register(staff("arcadia_history", Permissions.HISTORY)
                .then(player().executes(BridgeCommands::history)));

        d.register(staff("arcadia_unlink", Permissions.UNLINK)
                .then(player().executes(BridgeCommands::unlink)));

        d.register(Commands.literal("arcadia_verify").executes(BridgeCommands::verify));

        d.register(Commands.literal("report")
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                ctx.getSource().getOnlinePlayerNames(), builder))
                        .then(reason().executes(BridgeCommands::report))));

        d.register(Commands.literal("chat")
                .executes(ctx -> chatMode(ctx, null))
                .then(Commands.literal("global").executes(ctx -> chatMode(ctx, true)))
                .then(Commands.literal("local").executes(ctx -> chatMode(ctx, false))));

        d.register(Commands.literal("sc")
                .requires(src -> Permissions.has(src, Permissions.STAFF_CHAT))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(BridgeCommands::staffChat)));

        d.register(Commands.literal("canal")
                .then(Commands.literal("list").executes(BridgeCommands::channelList))
                .then(Commands.literal("join").then(channelArgument(false).executes(BridgeCommands::channelJoin)))
                .then(Commands.literal("leave").then(channelArgument(true).executes(BridgeCommands::channelLeave)))
                .then(Commands.literal("say").then(channelArgument(true)
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(BridgeCommands::channelSay))))
                .then(Commands.literal("create")
                        .requires(src -> Permissions.has(src, Permissions.CHANNELS_MANAGE))
                        .then(channelArgument(false).executes(BridgeCommands::channelCreate)))
                .then(Commands.literal("delete")
                        .requires(src -> Permissions.has(src, Permissions.CHANNELS_MANAGE))
                        .then(channelArgument(false).executes(BridgeCommands::channelDelete))));

        d.register(staff("arcadia_bridge", Permissions.ADMIN)
                .then(Commands.literal("status").executes(BridgeCommands::status))
                .then(Commands.literal("reloadlang").executes(BridgeCommands::reloadLang)));
    }

    // ---------------------------------------------------------------- construction

    private static LiteralArgumentBuilder<CommandSourceStack> staff(String name, PermissionNode<Boolean> node) {
        return Commands.literal(name).requires(src -> Permissions.has(src, node));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> player() {
        return Commands.argument("player", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ctx.getSource().getOnlinePlayerNames(), builder));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> duration() {
        return Commands.argument("duration", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(new String[]{"30m", "1h", "12h", "1d", "7d", "30d"}, builder));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> reason() {
        return Commands.argument("reason", StringArgumentType.greedyString());
    }

    private static String arg(CommandContext<CommandSourceStack> ctx, String name) {
        return StringArgumentType.getString(ctx, name);
    }

    // ---------------------------------------------------------------- actions

    private static int sanction(CommandContext<CommandSourceStack> ctx, SanctionType type, String durationText, String reasonText) {
        CommandSourceStack source = ctx.getSource();
        Long duration = null;
        if (durationText != null) {
            long parsed = Durations.parse(durationText);
            if (parsed <= 0) {
                source.sendFailure(Lang.mc("cmd.bad_duration"));
                return 0;
            }
            duration = parsed;
        }
        String reason = reasonText == null || reasonText.isBlank() ? Lang.get("sanction.no_reason") : reasonText;
        if (reason.length() > 500) {
            reason = reason.substring(0, 500);
        }
        Long finalDuration = duration;
        String finalReason = reason;
        String actor = source.getTextName();

        withTarget(ctx, target -> {
            if (type == SanctionType.BAN && isSelf(source, target)) {
                return CompletableFuture.completedFuture(Lang.mc("cmd.not_yourself"));
            }
            CompletableFuture<Sanction> applied = Bridge.get().sanctions().apply(new Request(type, target, finalDuration,
                    finalReason, actor, SanctionService.ORIGIN_MINECRAFT, true));
            if (type == SanctionType.WARN) {
                return applied.thenCompose(s -> Bridge.get().repository().countWarns(target.uuid()))
                        .thenApply(count -> (Component) Lang.mc("cmd.warn.done", "name", target.name(), "count", count,
                                "max", BridgeConfig.SANCTIONS_MAX_WARNS.get()));
            }
            return applied.thenApply(s -> (Component) Lang.mc(s.type() == SanctionType.BAN ? "cmd.ban.done" : "cmd.mute.done",
                    "name", target.name(), "reason", s.reason(),
                    "duration", s.isPermanent() ? Lang.get("common.permanent") : Durations.format(s.expiresAt() - s.createdAt())));
        });
        return 1;
    }

    private static int revoke(CommandContext<CommandSourceStack> ctx, SanctionType type) {
        String actor = ctx.getSource().getTextName();
        withTarget(ctx, target -> Bridge.get().sanctions().revoke(type, target, actor, true)
                .thenApply(revoked -> (Component) Lang.mc(revoked.isEmpty() ? "cmd.revoke.none" : "cmd.revoke.done",
                        "name", target.name())));
        return 1;
    }

    private static int history(CommandContext<CommandSourceStack> ctx) {
        withTarget(ctx, target -> Bridge.get().repository().history(target.uuid(), 10).thenApply(list -> {
            var out = Lang.mc("cmd.history.header", "name", target.name());
            if (list.isEmpty()) {
                out.append("\n").append(Lang.mc("cmd.history.empty"));
            }
            long now = System.currentTimeMillis();
            for (Sanction s : list) {
                String state = s.type() == SanctionType.WARN ? "" : s.isInEffect(now) ? Lang.get("cmd.history.active") : Lang.get("cmd.history.ended");
                out.append("\n").append(Lang.mc("cmd.history.line", "type", Lang.get("type." + s.type().name()), "reason", s.reason(),
                        "actor", s.actor(), "date", new java.text.SimpleDateFormat("dd/MM/yyyy").format(new java.util.Date(s.createdAt())),
                        "state", state));
            }
            return (Component) out;
        }));
        return 1;
    }

    private static int unlink(CommandContext<CommandSourceStack> ctx) {
        String actor = ctx.getSource().getTextName();
        withTarget(ctx, target -> Bridge.get().verification().unlink(target.uuid(), actor)
                .thenApply(removed -> (Component) Lang.mc(removed.isPresent() ? "cmd.unlink.done" : "cmd.unlink.none",
                        "name", target.name())));
        return 1;
    }

    private static int verify(CommandContext<CommandSourceStack> ctx) {
        Bridge bridge = Bridge.get();
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Lang.mc("cmd.players_only"));
            return 0;
        }
        if (bridge == null) {
            ctx.getSource().sendFailure(Lang.mc("common.unavailable"));
            return 0;
        }
        bridge.verification().showStatus(player);
        return 1;
    }

    /** /report &lt;joueur&gt; &lt;raison&gt; : transmis au staff sur Discord. */
    private static int report(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Bridge bridge = Bridge.get();
        ServerPlayer reporter = source.getPlayer();
        if (reporter == null) {
            source.sendFailure(Lang.mc("cmd.players_only"));
            return 0;
        }
        if (bridge == null || bridge.repository() == null || !ReportService.isEnabled()) {
            source.sendFailure(Lang.mc("common.unavailable"));
            return 0;
        }
        long wait = bridge.reports().cooldownLeft(reporter.getUUID());
        if (wait > 0) {
            source.sendFailure(Lang.mc("cmd.report.cooldown", "seconds", wait));
            return 0;
        }
        String reason = arg(ctx, "reason").trim();
        if (reason.length() > 500) {
            reason = reason.substring(0, 500);
        }
        String finalReason = reason;
        String targetName = arg(ctx, "player");
        if (targetName.equalsIgnoreCase(reporter.getGameProfile().getName())) {
            source.sendFailure(Lang.mc("cmd.report.not_yourself"));
            return 0;
        }
        bridge.resolvePlayer(targetName)
                .thenCompose(target -> target == null
                        ? CompletableFuture.completedFuture((Component) Lang.mc("cmd.player_unknown", "name", targetName))
                        : bridge.reports().submit(reporter, target, finalReason)
                                .thenApply(r -> (Component) Lang.mc("cmd.report.done", "name", target.name())))
                .whenComplete((message, error) -> bridge.runOnMain(() -> {
                    if (error != null) {
                        LOGGER.error("[ArcadiaBridge] Signalement echoue", error);
                        source.sendFailure(Lang.mc("common.error"));
                    } else {
                        source.sendSuccess(() -> message, false);
                    }
                }));
        return 1;
    }

    /** /chat [global|local] : participer ou non au chat commun a tous les serveurs. */
    // ---------------------------------------------------------------- chat staff et canaux

    /** Nom de canal ; {@code mine} : suggere les canaux du joueur. */
    private static RequiredArgumentBuilder<CommandSourceStack, String> channelArgument(boolean mine) {
        return Commands.argument("channel", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    Bridge bridge = Bridge.get();
                    ServerPlayer player = ctx.getSource().getPlayer();
                    if (mine && bridge != null && player != null) {
                        return SharedSuggestionProvider.suggest(bridge.channels().channelsOf(player.getUUID()), builder);
                    }
                    return builder.buildFuture();
                });
    }

    private static int staffChat(CommandContext<CommandSourceStack> ctx) {
        Bridge bridge = Bridge.get();
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Lang.mc("cmd.players_only"));
            return 0;
        }
        if (bridge == null || !ChatChannels.staffEnabled()) {
            ctx.getSource().sendFailure(Lang.mc("cmd.staff.disabled"));
            return 0;
        }
        bridge.channels().sendStaff(player, arg(ctx, "message"));
        return 1;
    }

    /** Verifie que les canaux sont actifs et que le nom est valide ; renvoie le nom normalise ou null. */
    private static String channelName(CommandContext<CommandSourceStack> ctx) {
        Bridge bridge = Bridge.get();
        if (bridge == null || bridge.repository() == null || !ChatChannels.channelsEnabled()) {
            ctx.getSource().sendFailure(Lang.mc("cmd.channel.disabled"));
            return null;
        }
        String name = ChatChannels.normalize(arg(ctx, "channel"));
        if (!ChatChannels.isValidName(name)) {
            ctx.getSource().sendFailure(Lang.mc("cmd.channel.invalid"));
            return null;
        }
        return name;
    }

    private static int channelList(CommandContext<CommandSourceStack> ctx) {
        Bridge bridge = Bridge.get();
        CommandSourceStack source = ctx.getSource();
        if (bridge == null || bridge.repository() == null || !ChatChannels.channelsEnabled()) {
            source.sendFailure(Lang.mc("cmd.channel.disabled"));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        List<String> mine = player == null ? List.of() : bridge.channels().channelsOf(player.getUUID());
        bridge.channels().list().whenComplete((all, error) -> bridge.runOnMain(() -> {
            if (error != null) {
                source.sendFailure(Lang.mc("common.error"));
                return;
            }
            String none = Lang.get("cmd.channel.none");
            source.sendSuccess(() -> Lang.mc("cmd.channel.list",
                    "all", all.isEmpty() ? none : "#" + String.join(", #", all),
                    "mine", mine.isEmpty() ? none : "#" + String.join(", #", mine)), false);
        }));
        return 1;
    }

    private static int channelJoin(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        String name = channelName(ctx);
        if (name == null) {
            return 0;
        }
        if (player == null) {
            ctx.getSource().sendFailure(Lang.mc("cmd.players_only"));
            return 0;
        }
        Bridge bridge = Bridge.get();
        reply(ctx, bridge.channels().join(player, name)
                .thenApply(exists -> Lang.mc(exists ? "cmd.channel.joined" : "cmd.channel.unknown", "channel", name)));
        return 1;
    }

    private static int channelLeave(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        String name = channelName(ctx);
        if (name == null) {
            return 0;
        }
        if (player == null) {
            ctx.getSource().sendFailure(Lang.mc("cmd.players_only"));
            return 0;
        }
        reply(ctx, Bridge.get().channels().leave(player, name)
                .thenApply(v -> Lang.mc("cmd.channel.left", "channel", name)));
        return 1;
    }

    private static int channelSay(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        String name = channelName(ctx);
        if (name == null) {
            return 0;
        }
        if (player == null) {
            ctx.getSource().sendFailure(Lang.mc("cmd.players_only"));
            return 0;
        }
        Bridge.get().channels().sendChannel(player, name, arg(ctx, "message"));
        return 1;
    }

    private static int channelCreate(CommandContext<CommandSourceStack> ctx) {
        String name = channelName(ctx);
        if (name == null) {
            return 0;
        }
        String by = ctx.getSource().getPlayer() == null ? "console" : ctx.getSource().getPlayer().getGameProfile().getName();
        reply(ctx, Bridge.get().channels().create(name, by)
                .thenApply(created -> Lang.mc(created ? "cmd.channel.created" : "cmd.channel.exists", "channel", name)));
        return 1;
    }

    private static int channelDelete(CommandContext<CommandSourceStack> ctx) {
        String name = channelName(ctx);
        if (name == null) {
            return 0;
        }
        reply(ctx, Bridge.get().channels().delete(name)
                .thenApply(existed -> Lang.mc(existed ? "cmd.channel.deleted" : "cmd.channel.unknown", "channel", name)));
        return 1;
    }

    /** Repond au joueur une fois l'operation en base terminee. */
    private static void reply(CommandContext<CommandSourceStack> ctx, CompletableFuture<? extends Component> future) {
        CommandSourceStack source = ctx.getSource();
        future.whenComplete((message, error) -> Bridge.get().runOnMain(() -> {
            if (error != null) {
                LOGGER.error("[ArcadiaBridge] Commande /canal echouee", error);
                source.sendFailure(Lang.mc("common.error"));
            } else {
                source.sendSuccess(() -> message, false);
            }
        }));
    }

    private static int chatMode(CommandContext<CommandSourceStack> ctx, Boolean global) {
        CommandSourceStack source = ctx.getSource();
        Bridge bridge = Bridge.get();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Lang.mc("cmd.players_only"));
            return 0;
        }
        if (bridge == null || !CrossServerChat.isEnabled()) {
            source.sendFailure(Lang.mc("cmd.chat.disabled"));
            return 0;
        }
        if (global != null) {
            bridge.crossChat().setGlobal(player.getUUID(), global);
        }
        boolean current = bridge.crossChat().isGlobal(player.getUUID());
        source.sendSuccess(() -> Lang.mc(current ? "cmd.chat.global" : "cmd.chat.local"), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Bridge bridge = Bridge.get();
        boolean db = bridge != null && bridge.isDatabaseReady();
        boolean discord = bridge != null && bridge.discord().isReady();
        var cache = bridge == null ? null : bridge.cache();
        ctx.getSource().sendSuccess(() -> Lang.mc("cmd.status",
                "db", Lang.get(db ? "common.ok" : "common.ko"),
                "discord", Lang.get(discord ? "common.ok" : "common.ko"),
                "server", Bridge.serverId(),
                "primary", Lang.get(Bridge.isPrimary() ? "common.yes" : "common.no"),
                "links", cache == null ? 0 : cache.linkCount(),
                "bans", cache == null ? 0 : cache.banCount(),
                "mutes", cache == null ? 0 : cache.muteCount()), false);
        return 1;
    }

    private static int reloadLang(CommandContext<CommandSourceStack> ctx) {
        com.arcadia.bridge.i18n.Lang.load(net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("arcadiabridge"),
                BridgeConfig.LANGUAGE.get());
        ctx.getSource().sendSuccess(() -> Lang.mc("cmd.reloaded"), true);
        return 1;
    }

    // ---------------------------------------------------------------- utilitaires

    /**
     * Resout le joueur vise (connecte ou non) puis execute l'action ; la reponse est envoyee sur le
     * thread principal quand l'action (asynchrone) se termine.
     */
    private static void withTarget(CommandContext<CommandSourceStack> ctx,
                                   Function<PlayerRef, CompletableFuture<Component>> action) {
        CommandSourceStack source = ctx.getSource();
        Bridge bridge = Bridge.get();
        if (bridge == null || bridge.repository() == null) {
            source.sendFailure(Lang.mc("common.unavailable"));
            return;
        }
        String name = arg(ctx, "player");
        bridge.resolvePlayer(name)
                .thenCompose(target -> target == null
                        ? CompletableFuture.completedFuture((Component) Lang.mc("cmd.player_unknown", "name", name))
                        : action.apply(target))
                .whenComplete((message, error) -> bridge.runOnMain(() -> {
                    if (error != null) {
                        LOGGER.error("[ArcadiaBridge] Commande echouee", error);
                        source.sendFailure(Lang.mc("common.error"));
                    } else {
                        source.sendSuccess(() -> message, true);
                    }
                }));
    }

    private static boolean isSelf(CommandSourceStack source, PlayerRef target) {
        ServerPlayer player = source.getPlayer();
        return player != null && player.getUUID().equals(target.uuid());
    }
}
