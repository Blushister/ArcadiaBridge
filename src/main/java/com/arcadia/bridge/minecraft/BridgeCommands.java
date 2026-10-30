package com.arcadia.bridge.minecraft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.chat.ChatChannels;
import com.arcadia.bridge.chat.CrossServerChat;
import com.arcadia.bridge.chat.PollService;
import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.data.Models.JoinResult;
import com.arcadia.bridge.data.Models.PlayerRef;
import com.arcadia.bridge.data.Models.Sanction;
import com.arcadia.bridge.data.Models.SanctionType;
import com.arcadia.bridge.i18n.Lang;
import com.arcadia.bridge.permission.Permissions;
import com.arcadia.bridge.report.ReportService;
import com.arcadia.bridge.chat.Ignores;
import com.arcadia.bridge.chat.Replies;
import com.arcadia.bridge.moderation.ChatModeration;
import com.arcadia.bridge.sanction.SanctionService;
import com.arcadia.bridge.sanction.SanctionService.Request;
import com.arcadia.bridge.tabs.ChatTabs;
import com.arcadia.bridge.util.Durations;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
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
                .then(Commands.literal("local").executes(ctx -> chatMode(ctx, false)))
                // Staff : mode lent et verrou du chat public, ici, partout ("tous") ou sur un serveur donne.
                .then(Commands.literal("lent")
                        .requires(src -> Permissions.has(src, Permissions.CHAT_MODERATE))
                        .then(Commands.literal("off").executes(ctx -> chatRule(ctx, 0, null))
                                .then(scopeArgument().executes(ctx -> chatRule(ctx, 0, null))))
                        .then(Commands.argument("secondes", IntegerArgumentType.integer(0, 3600))
                                .executes(ctx -> chatRule(ctx, IntegerArgumentType.getInteger(ctx, "secondes"), null))
                                .then(scopeArgument().executes(ctx ->
                                        chatRule(ctx, IntegerArgumentType.getInteger(ctx, "secondes"), null)))))
                .then(Commands.literal("verrouiller")
                        .requires(src -> Permissions.has(src, Permissions.CHAT_MODERATE))
                        .executes(ctx -> chatRule(ctx, null, true))
                        .then(scopeArgument().executes(ctx -> chatRule(ctx, null, true))))
                .then(Commands.literal("deverrouiller")
                        .requires(src -> Permissions.has(src, Permissions.CHAT_MODERATE))
                        .executes(ctx -> chatRule(ctx, null, false))
                        .then(scopeArgument().executes(ctx -> chatRule(ctx, null, false)))));

        d.register(Commands.literal("ignore")
                .executes(BridgeCommands::ignoreList)
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            List<String> names = new ArrayList<>(ctx.getSource().getOnlinePlayerNames());
                            names.addAll(ChatTabs.remotePlayerNames());
                            return SharedSuggestionProvider.suggest(names, builder);
                        })
                        .executes(ctx -> ignore(ctx, false))));
        // /r (et /reply) : repond au dernier correspondant en MP, sur n'importe quel serveur, via /msg.
        for (String name : List.of("r", "reply")) {
            d.register(Commands.literal(name)
                    .then(Commands.argument("message", StringArgumentType.greedyString()).executes(BridgeCommands::reply)));
        }
        d.register(Commands.literal("unignore")
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> ctx.getSource().getPlayer() == null ? builder.buildFuture()
                                : SharedSuggestionProvider.suggest(Ignores.names(ctx.getSource().getPlayer()), builder))
                        .executes(ctx -> ignore(ctx, true))));

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
                        .then(channelArgument(false).executes(ctx -> channelCreate(ctx, false))
                                .then(visibilityArgument().executes(ctx -> channelCreate(ctx, isPrivateArg(ctx))))))
                .then(Commands.literal("delete")
                        .requires(src -> Permissions.has(src, Permissions.CHANNELS_MANAGE))
                        .then(channelArgument(false).executes(BridgeCommands::channelDelete)))
                .then(Commands.literal("mode")
                        .requires(src -> Permissions.has(src, Permissions.CHANNELS_MANAGE))
                        .then(channelArgument(false).then(visibilityArgument().executes(BridgeCommands::channelMode))))
                .then(Commands.literal("add")
                        .requires(src -> Permissions.has(src, Permissions.CHANNELS_MANAGE))
                        .then(channelArgument(false).then(Commands.argument("players", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        ctx.getSource().getOnlinePlayerNames(), builder))
                                .executes(BridgeCommands::channelAdd))))
                .then(Commands.literal("kick")
                        .requires(src -> Permissions.has(src, Permissions.CHANNELS_MANAGE))
                        .then(channelArgument(false).then(player().executes(BridgeCommands::channelKick))))
                .then(Commands.literal("members")
                        .requires(src -> Permissions.has(src, Permissions.CHANNELS_MANAGE))
                        .then(channelArgument(false).executes(BridgeCommands::channelMembers))));

        d.register(Commands.literal("sondage")
                .then(Commands.literal("creer")
                        .requires(src -> Permissions.has(src, Permissions.POLLS))
                        .then(Commands.argument("texte", StringArgumentType.greedyString()).executes(BridgeCommands::pollCreate)))
                .then(Commands.literal("voter")
                        .then(Commands.argument("id", LongArgumentType.longArg(1))
                                .then(Commands.argument("choix", IntegerArgumentType.integer(1, PollService.MAX_OPTIONS))
                                        .executes(BridgeCommands::pollVote))))
                .then(Commands.literal("resultats")
                        .then(Commands.argument("id", LongArgumentType.longArg(1)).executes(ctx ->
                                pollReply(ctx, Bridge.get().polls().results(LongArgumentType.getLong(ctx, "id"))))))
                .then(Commands.literal("fin")
                        .requires(src -> Permissions.has(src, Permissions.POLLS))
                        .then(Commands.argument("id", LongArgumentType.longArg(1)).executes(ctx ->
                                pollReply(ctx, Bridge.get().polls().close(LongArgumentType.getLong(ctx, "id")))))));

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
    // ---------------------------------------------------------------- sondages

    private static boolean pollsReady(CommandContext<CommandSourceStack> ctx) {
        Bridge bridge = Bridge.get();
        if (bridge == null || bridge.repository() == null || !PollService.enabled()) {
            ctx.getSource().sendFailure(Lang.mc("poll.disabled"));
            return false;
        }
        return true;
    }

    private static int pollCreate(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Lang.mc("cmd.players_only"));
            return 0;
        }
        if (!pollsReady(ctx)) {
            return 0;
        }
        Component error = Bridge.get().polls().create(player, arg(ctx, "texte"));
        if (error != null) {
            ctx.getSource().sendFailure(error);
            return 0;
        }
        return 1;
    }

    private static int pollVote(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null || !pollsReady(ctx)) {
            return 0;
        }
        return pollReply(ctx, Bridge.get().polls().vote(player, LongArgumentType.getLong(ctx, "id"),
                IntegerArgumentType.getInteger(ctx, "choix")));
    }

    private static int pollReply(CommandContext<CommandSourceStack> ctx, CompletableFuture<Component> future) {
        if (!pollsReady(ctx)) {
            return 0;
        }
        reply(ctx, future);
        return 1;
    }

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
        boolean manager = Permissions.has(source, Permissions.CHANNELS_MANAGE);
        bridge.channels().list().whenComplete((infos, error) -> bridge.runOnMain(() -> {
            if (error != null) {
                source.sendFailure(Lang.mc("common.error"));
                return;
            }
            String tag = Lang.get("cmd.channel.private_tag");
            List<String> visible = infos.stream()
                    .filter(info -> !info.isPrivate() || manager || mine.contains(info.name()))
                    .map(info -> "#" + info.name() + (info.isPrivate() ? " " + tag : ""))
                    .toList();
            String none = Lang.get("cmd.channel.none");
            source.sendSuccess(() -> Lang.mc("cmd.channel.list",
                    "all", visible.isEmpty() ? none : String.join(", ", visible),
                    "mine", mine.isEmpty() ? none : "#" + String.join(", #", mine)), false);
        }));
        return 1;
    }

    /** "public" ou "prive" (prive, privé, private acceptes). */
    private static RequiredArgumentBuilder<CommandSourceStack, String> visibilityArgument() {
        return Commands.argument("visibility", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of("public", "prive"), builder));
    }

    private static boolean isPrivateArg(CommandContext<CommandSourceStack> ctx) {
        String value = arg(ctx, "visibility").toLowerCase(Locale.ROOT);
        return value.startsWith("priv");
    }

    private static int channelMode(CommandContext<CommandSourceStack> ctx) {
        String name = channelName(ctx);
        if (name == null) {
            return 0;
        }
        boolean isPrivate = isPrivateArg(ctx);
        reply(ctx, Bridge.get().channels().setPrivate(name, isPrivate)
                .thenApply(exists -> exists
                        ? Lang.mc(isPrivate ? "cmd.channel.now_private" : "cmd.channel.now_public", "channel", name)
                        : Lang.mc("cmd.channel.unknown", "channel", name)));
        return 1;
    }

    /** Ajoute d'office un ou plusieurs joueurs (separes par des espaces ou des virgules). */
    private static int channelAdd(CommandContext<CommandSourceStack> ctx) {
        String name = channelName(ctx);
        if (name == null) {
            return 0;
        }
        Bridge bridge = Bridge.get();
        List<String> names = Arrays.stream(arg(ctx, "players").split("[\\s,]+"))
                .filter(s -> !s.isBlank()).distinct().limit(50).toList();
        List<String> added = Collections.synchronizedList(new ArrayList<>());
        List<String> unknown = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<?>[] steps = names.stream().map(n -> bridge.resolvePlayer(n).thenCompose(ref -> {
            if (ref == null) {
                unknown.add(n);
                return CompletableFuture.completedFuture(null);
            }
            return bridge.channels().add(ref, name).thenAccept(result -> {
                if (result == JoinResult.JOINED) {
                    added.add(ref.name());
                }
            });
        })).toArray(CompletableFuture[]::new);
        reply(ctx, CompletableFuture.allOf(steps).thenCompose(v -> bridge.repository().listChannels()).thenApply(all -> {
            if (!all.contains(name)) {
                return Lang.mc("cmd.channel.unknown", "channel", name);
            }
            String none = Lang.get("cmd.channel.none");
            return Lang.mc("cmd.channel.added", "channel", name,
                    "added", added.isEmpty() ? none : String.join(", ", added),
                    "unknown", unknown.isEmpty() ? none : String.join(", ", unknown));
        }));
        return 1;
    }

    private static int channelKick(CommandContext<CommandSourceStack> ctx) {
        String name = channelName(ctx);
        if (name == null) {
            return 0;
        }
        Bridge bridge = Bridge.get();
        String target = arg(ctx, "player");
        reply(ctx, bridge.resolvePlayer(target).thenCompose(ref -> ref == null
                ? CompletableFuture.completedFuture((Component) Lang.mc("cmd.player_unknown", "name", target))
                : bridge.channels().kick(ref, name)
                        .thenApply(v -> (Component) Lang.mc("cmd.channel.kicked", "name", ref.name(), "channel", name))));
        return 1;
    }

    private static int channelMembers(CommandContext<CommandSourceStack> ctx) {
        String name = channelName(ctx);
        if (name == null) {
            return 0;
        }
        reply(ctx, Bridge.get().channels().members(name).thenApply(list -> Lang.mc("cmd.channel.members",
                "channel", name, "count", list.size(),
                "members", list.isEmpty() ? Lang.get("cmd.channel.none") : String.join(", ", list))));
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
        reply(ctx, bridge.channels().join(player, name).thenApply(result -> Lang.mc(switch (result) {
            case JOINED -> "cmd.channel.joined";
            case PRIVATE -> "cmd.channel.private_join";
            case UNKNOWN -> "cmd.channel.unknown";
        }, "channel", name)));
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

    private static int channelCreate(CommandContext<CommandSourceStack> ctx, boolean isPrivate) {
        String name = channelName(ctx);
        if (name == null) {
            return 0;
        }
        String by = ctx.getSource().getPlayer() == null ? "console" : ctx.getSource().getPlayer().getGameProfile().getName();
        reply(ctx, Bridge.get().channels().create(name, by, isPrivate)
                .thenApply(created -> Lang.mc(!created ? "cmd.channel.exists"
                        : isPrivate ? "cmd.channel.created_private" : "cmd.channel.created", "channel", name)));
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

    private static int reply(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Lang.mc("cmd.players_only"));
            return 0;
        }
        String partner = Replies.partner(player);
        if (partner == null) {
            ctx.getSource().sendFailure(Lang.mc("reply.none"));
            return 0;
        }
        player.server.getCommands().performPrefixedCommand(player.createCommandSourceStack(),
                "msg " + partner + " " + arg(ctx, "message"));
        return 1;
    }

    /** /ignore pseudo (bascule) et /unignore pseudo. */
    private static int ignore(CommandContext<CommandSourceStack> ctx, boolean remove) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Lang.mc("cmd.players_only"));
            return 0;
        }
        reply(ctx, Ignores.change(player, arg(ctx, "player"), remove));
        return 1;
    }

    private static int ignoreList(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Lang.mc("cmd.players_only"));
            return 0;
        }
        List<String> names = Ignores.names(player);
        ctx.getSource().sendSuccess(() -> names.isEmpty() ? Lang.mc("ignore.none")
                : Lang.mc("ignore.list", "names", String.join(", ", names)), false);
        return 1;
    }

    /** Portee d'une regle du chat : ici, tous, ou l'identifiant d'un serveur en ligne. */
    private static RequiredArgumentBuilder<CommandSourceStack, String> scopeArgument() {
        return Commands.argument("portee", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    List<String> scopes = new ArrayList<>(List.of("ici", "tous"));
                    ChatTabs.servers().forEach(server -> scopes.add(server.id()));
                    return SharedSuggestionProvider.suggest(scopes, builder);
                });
    }

    /** /chat lent | verrouiller | deverrouiller [portee] */
    private static int chatRule(CommandContext<CommandSourceStack> ctx, Integer slow, Boolean locked) {
        Bridge bridge = Bridge.get();
        if (bridge == null || bridge.repository() == null) {
            ctx.getSource().sendFailure(Lang.mc("common.unavailable"));
            return 0;
        }
        String arg;
        try {
            arg = StringArgumentType.getString(ctx, "portee");
        } catch (IllegalArgumentException e) {
            arg = null;
        }
        String scope = bridge.moderation().resolveScope(arg);
        if (scope == null) {
            ctx.getSource().sendFailure(Lang.mc("moderation.scope.unknown", "scope", arg));
            return 0;
        }
        String label = ChatModeration.scopeLabel(scope);
        Component done = locked != null
                ? Lang.mc(locked ? "moderation.done.locked" : "moderation.done.unlocked", "scope", label)
                : slow > 0 ? Lang.mc("moderation.done.slow", "seconds", slow, "scope", label)
                : Lang.mc("moderation.done.slow_off", "scope", label);
        reply(ctx, bridge.moderation().setRule(scope, slow, locked, ctx.getSource().getTextName()).thenApply(v -> done));
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
