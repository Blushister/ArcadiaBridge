package com.arcadia.bridge.discord;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.data.Models.PlayerRef;
import com.arcadia.bridge.data.Models.Sanction;
import com.arcadia.bridge.data.Models.ServerStatus;
import com.arcadia.bridge.data.Models.SanctionType;
import com.arcadia.bridge.i18n.Lang;
import com.arcadia.bridge.sanction.SanctionService;
import com.arcadia.bridge.sanction.SanctionService.Request;
import com.arcadia.bridge.util.Durations;
import com.mojang.logging.LogUtils;
import java.awt.Color;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.slf4j.Logger;

/** Commandes slash : /verify pour tous, /mc pour le staff (sanctions sur les comptes Minecraft). */
final class SlashCommands {

    static final String VERIFY = "verify";
    static final String MC = "mc";
    static final String ONLINE = "online";
    private static final Logger LOGGER = LogUtils.getLogger();

    private SlashCommands() {
    }

    static void register(Guild guild) {
        guild.updateCommands().addCommands(
                Commands.slash(VERIFY, Lang.get("discord.cmd.verify"))
                        .addOption(OptionType.STRING, "code", Lang.get("discord.cmd.verify.code"), true),
                Commands.slash(ONLINE, Lang.get("discord.cmd.online")),
                Commands.slash(MC, Lang.get("discord.cmd.mc"))
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.BAN_MEMBERS))
                        .addSubcommands(
                                sub("ban").addOption(OptionType.STRING, "raison", Lang.get("discord.cmd.opt.reason"), false),
                                sub("tempban")
                                        .addOption(OptionType.STRING, "duree", Lang.get("discord.cmd.opt.duration"), true)
                                        .addOption(OptionType.STRING, "raison", Lang.get("discord.cmd.opt.reason"), false),
                                sub("unban"),
                                sub("mute")
                                        .addOption(OptionType.STRING, "duree", Lang.get("discord.cmd.opt.duration_optional"), false)
                                        .addOption(OptionType.STRING, "raison", Lang.get("discord.cmd.opt.reason"), false),
                                sub("unmute"),
                                sub("warn").addOption(OptionType.STRING, "raison", Lang.get("discord.cmd.opt.reason"), true),
                                sub("info"),
                                sub("unlink"))
        ).queue(ok -> LOGGER.info("[ArcadiaBridge] Commandes slash Discord enregistrees."),
                error -> LOGGER.error("[ArcadiaBridge] Enregistrement des commandes slash impossible : {}", error.toString()));
    }

    private static SubcommandData sub(String name) {
        return new SubcommandData(name, Lang.get("discord.cmd.mc." + name))
                .addOption(OptionType.STRING, "joueur", Lang.get("discord.cmd.opt.player"), true);
    }

    /** /online : accessible a tout le monde, liste les serveurs et leurs joueurs. */
    static void handleOnline(Bridge bridge, SlashCommandInteractionEvent event) {
        if (bridge.repository() == null) {
            event.reply(Lang.get("discord.unavailable")).setEphemeral(true).queue();
            return;
        }
        event.deferReply().queue();
        bridge.repository().loadServers().thenAccept(servers -> {
            long now = System.currentTimeMillis();
            List<ServerStatus> online = servers.stream().filter(s -> s.isOnline(now)).toList();
            int total = online.stream().mapToInt(ServerStatus::players).sum();
            EmbedBuilder eb = new EmbedBuilder()
                    .setColor(new Color(0x3B, 0xA5, 0x5D))
                    .setTitle(Lang.get("discord.online.title", "total", total));
            if (online.isEmpty()) {
                eb.setDescription(Lang.get("discord.online.none"));
            }
            for (ServerStatus s : online) {
                String names = s.names().isEmpty() ? Lang.get("discord.online.empty")
                        : escape(String.join(", ", s.names()));
                eb.addField(escape(s.label()) + " — " + s.players() + "/" + s.maxPlayers(),
                        DiscordBot.truncate(names, MessageEmbed.VALUE_MAX_LENGTH), false);
            }
            // Un serveur muet depuis plus d'une minute est signale comme hors ligne.
            String offline = servers.stream().filter(s -> !s.isOnline(now)).map(s -> escape(s.label()))
                    .collect(java.util.stream.Collectors.joining(", "));
            if (!offline.isEmpty()) {
                eb.addField(Lang.get("discord.online.offline"), offline, false);
            }
            event.getHook().editOriginalEmbeds(eb.build()).queue();
        }).exceptionally(error -> {
            LOGGER.error("[ArcadiaBridge] /online a echoue", error);
            event.getHook().editOriginal(Lang.get("discord.error")).queue();
            return null;
        });
    }

    static void handleStaff(Bridge bridge, SlashCommandInteractionEvent event) {
        if (!isStaff(event.getMember())) {
            event.reply(Lang.get("discord.no_permission")).setEphemeral(true).queue();
            return;
        }
        String sub = event.getSubcommandName();
        String playerName = string(event, "joueur");
        if (sub == null || playerName == null) {
            return;
        }
        event.deferReply(true).queue();
        String actor = event.getUser().getName() + " (Discord)";

        bridge.resolvePlayer(playerName).thenCompose(target -> {
            if (target == null) {
                return CompletableFuture.completedFuture(Lang.get("discord.player_unknown", "name", escape(playerName)));
            }
            return run(bridge, sub, target, event, actor);
        }).thenAccept(text -> event.getHook().editOriginal(text).queue()).exceptionally(error -> {
            LOGGER.error("[ArcadiaBridge] /mc {} a echoue", sub, error);
            event.getHook().editOriginal(Lang.get("discord.error")).queue();
            return null;
        });
    }

    private static CompletableFuture<String> run(Bridge bridge, String sub, PlayerRef target,
                                                 SlashCommandInteractionEvent event, String actor) {
        SanctionService sanctions = bridge.sanctions();
        String name = escape(target.name());
        String reason = orDefault(string(event, "raison"));
        switch (sub) {
            case "ban", "tempban", "mute" -> {
                SanctionType type = sub.equals("mute") ? SanctionType.MUTE : SanctionType.BAN;
                String durationText = string(event, "duree");
                Long duration = null;
                if (durationText != null) {
                    long parsed = Durations.parse(durationText);
                    if (parsed <= 0) {
                        return CompletableFuture.completedFuture(Lang.get("discord.bad_duration"));
                    }
                    duration = parsed;
                }
                return sanctions.apply(new Request(type, target, duration, reason, actor,
                                SanctionService.ORIGIN_DISCORD, true))
                        .thenApply(s -> Lang.get("discord.sanction.done", "type", Lang.get("type." + s.type().name()), "name", name,
                                "duration", s.isPermanent() ? Lang.get("common.permanent") : Durations.format(s.expiresAt() - s.createdAt())));
            }
            case "warn" -> {
                return sanctions.apply(new Request(SanctionType.WARN, target, null, reason, actor,
                                SanctionService.ORIGIN_DISCORD, true))
                        .thenCompose(s -> bridge.repository().countWarns(target.uuid()))
                        .thenApply(count -> Lang.get("discord.warn.done", "name", name, "count", count,
                                "max", BridgeConfig.SANCTIONS_MAX_WARNS.get()));
            }
            case "unban", "unmute" -> {
                SanctionType type = sub.equals("unban") ? SanctionType.BAN : SanctionType.MUTE;
                return sanctions.revoke(type, target, actor, true).thenApply(revoked -> revoked.isEmpty()
                        ? Lang.get("discord.revoke.none", "name", name)
                        : Lang.get("discord.revoke.done", "name", name));
            }
            case "unlink" -> {
                return bridge.verification().unlink(target.uuid(), actor).thenApply(removed -> removed.isPresent()
                        ? Lang.get("discord.unlink.done", "name", name)
                        : Lang.get("discord.unlink.none", "name", name));
            }
            case "info" -> {
                return info(bridge, target);
            }
            default -> {
                return CompletableFuture.completedFuture(Lang.get("discord.error"));
            }
        }
    }

    private static CompletableFuture<String> info(Bridge bridge, PlayerRef target) {
        return bridge.repository().history(target.uuid(), 8).thenCombine(bridge.repository().countWarns(target.uuid()),
                (List<Sanction> history, Integer warns) -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("**").append(escape(target.name())).append("** (`").append(target.uuid()).append("`)\n");
                    sb.append(bridge.cache().link(target.uuid())
                            .map(l -> Lang.get("discord.info.linked", "discord", "<@" + l.discordId() + ">"))
                            .orElse(Lang.get("discord.info.not_linked"))).append('\n');
                    bridge.cache().activeBan(target.uuid()).ifPresent(b ->
                            sb.append(Lang.get("discord.info.banned", "reason", escape(b.reason()))).append('\n'));
                    bridge.cache().activeMute(target.uuid()).ifPresent(m ->
                            sb.append(Lang.get("discord.info.muted", "reason", escape(m.reason()))).append('\n'));
                    sb.append(Lang.get("discord.info.warns", "count", warns, "max", BridgeConfig.SANCTIONS_MAX_WARNS.get()));
                    if (!history.isEmpty()) {
                        sb.append("\n\n").append(Lang.get("discord.info.history")).append('\n');
                        long now = System.currentTimeMillis();
                        for (Sanction s : history) {
                            sb.append("- <t:").append(s.createdAt() / 1000).append(":d> **").append(Lang.get("type." + s.type().name())).append("** ")
                                    .append(escape(s.reason())).append(" — ").append(escape(s.actor()))
                                    .append(s.isInEffect(now) && s.type() != SanctionType.WARN ? " ⏳" : "").append('\n');
                        }
                    }
                    return DiscordBot.truncate(sb.toString(), 1900);
                });
    }

    private static boolean isStaff(Member member) {
        if (member == null) {
            return false;
        }
        if (member.hasPermission(Permission.BAN_MEMBERS)) {
            return true;
        }
        List<? extends String> staffRoles = BridgeConfig.DISCORD_STAFF_ROLE_IDS.get();
        return member.getUnsortedRoles().stream().anyMatch(role -> staffRoles.contains(role.getId()));
    }

    private static String string(SlashCommandInteractionEvent event, String option) {
        OptionMapping mapping = event.getOption(option);
        return mapping == null ? null : mapping.getAsString().trim();
    }

    private static String orDefault(String reason) {
        return reason == null || reason.isBlank() ? Lang.get("sanction.no_reason") : DiscordBot.truncate(reason, 500);
    }

    private static String escape(String text) {
        return MarkdownSanitizer.escape(text == null ? "" : text);
    }
}
