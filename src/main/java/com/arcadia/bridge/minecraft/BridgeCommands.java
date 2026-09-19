package com.arcadia.bridge.minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.data.Models.PlayerRef;
import com.arcadia.bridge.data.Models.Sanction;
import com.arcadia.bridge.data.Models.SanctionType;
import com.arcadia.bridge.i18n.Lang;
import com.arcadia.bridge.permission.Permissions;
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

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Bridge bridge = Bridge.get();
        boolean db = bridge != null && bridge.isDatabaseReady();
        boolean discord = bridge != null && bridge.discord().isReady();
        ctx.getSource().sendSuccess(() -> Lang.mc("cmd.status",
                "db", Lang.get(db ? "common.ok" : "common.ko"),
                "discord", Lang.get(discord ? "common.ok" : "common.ko"),
                "server", Bridge.serverId(),
                "primary", Lang.get(Bridge.isPrimary() ? "common.yes" : "common.no")), false);
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
