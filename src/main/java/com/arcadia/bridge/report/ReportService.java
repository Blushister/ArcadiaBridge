package com.arcadia.bridge.report;

import java.awt.Color;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.data.Models.PlayerRef;
import com.arcadia.bridge.data.Models.Report;
import com.arcadia.bridge.data.Models.SanctionType;
import com.arcadia.bridge.data.Repository;
import com.arcadia.bridge.discord.DiscordBot;
import com.arcadia.bridge.i18n.Lang;
import com.arcadia.bridge.sanction.SanctionService;
import com.arcadia.bridge.sanction.SanctionService.Request;
import com.arcadia.bridge.util.Durations;
import com.arcadia.bridge.util.Text;
import com.mojang.logging.LogUtils;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * Signalements faits en jeu avec {@code /report}, transmis au staff sur Discord avec des boutons
 * d'action. Un moderateur peut donc traiter depuis son telephone, sans lancer le jeu.
 */
public final class ReportService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Color ORANGE = new Color(0xE8, 0x8A, 0x2E);

    /** Identifiant des boutons : ab:rep:&lt;id du signalement&gt;:&lt;action&gt;. */
    public static final String BUTTON_PREFIX = "ab:rep:";
    public static final String ACTION_MUTE = "mute";
    public static final String ACTION_BAN = "ban";
    public static final String ACTION_IGNORE = "ignore";

    private final Bridge bridge;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public ReportService(Bridge bridge) {
        this.bridge = bridge;
    }

    public static boolean isEnabled() {
        return BridgeConfig.REPORTS_ENABLED.get();
    }

    /** @return secondes restantes avant que ce joueur puisse signaler de nouveau, 0 s'il peut y aller. */
    public long cooldownLeft(UUID uuid) {
        long until = cooldowns.getOrDefault(uuid, 0L);
        return Math.max(0, (until - System.currentTimeMillis() + 999) / 1000);
    }

    /**
     * Enregistre un signalement et previent le staff. Les signalements repetes sur un meme joueur sont
     * regroupes tant que personne n'a tranche.
     */
    public CompletableFuture<Report> submit(ServerPlayer reporter, PlayerRef target, String reason) {
        Repository repo = bridge.repository();
        if (repo == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("base indisponible"));
        }
        cooldowns.put(reporter.getUUID(),
                System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(BridgeConfig.REPORTS_COOLDOWN_SECONDS.get()));

        BlockPos pos = reporter.blockPosition();
        String where = reporter.level().dimension().location() + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        Report draft = new Report(0, reporter.getUUID(), reporter.getGameProfile().getName(), target.uuid(),
                target.name(), reason, Bridge.serverId(), System.currentTimeMillis(), 1, "OPEN", null);

        return repo.addReport(draft).thenApply(report -> {
            notifyStaff(report, where);
            bridge.channels().alert(Lang.get("alert.report", "id", report.id(), "target", report.targetName(),
                    "reporter", report.reporterName(), "reason", report.reason()));
            LOGGER.info("[ArcadiaBridge] Signalement #{} : {} signale {} ({})", report.id(),
                    report.reporterName(), report.targetName(), report.reason());
            return report;
        });
    }

    private void notifyStaff(Report report, String where) {
        DiscordBot discord = bridge.discord();
        if (!discord.isReady()) {
            return;
        }
        boolean targetOnline = bridge.onlineNames().stream().anyMatch(n -> n.equalsIgnoreCase(report.targetName()));
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(ORANGE)
                .setTitle(Lang.get("report.title", "id", report.id()))
                .setTimestamp(Instant.now())
                .addField(Lang.get("report.field.target"), escape(report.targetName())
                        + (targetOnline ? " " + Lang.get("report.online") : ""), true)
                .addField(Lang.get("report.field.reporter"), escape(report.reporterName()), true)
                .addField(Lang.get("report.field.server"), escape(report.serverId()), true)
                .addField(Lang.get("report.field.reason"), escape(report.reason()), false)
                .addField(Lang.get("report.field.where"), escape(where), false);
        if (report.count() > 1) {
            eb.addField(Lang.get("report.field.count"), String.valueOf(report.count()), true);
        }
        MessageEmbed embed = eb.build();

        if (report.messageId() != null) {
            // Signalement deja ouvert : on met a jour le message existant au lieu d'en empiler un nouveau.
            discord.editReportMessage(report.messageId(), embed, report.id());
            return;
        }
        discord.sendReport(embed, report.id(), messageId -> bridge.repository().setReportMessage(report.id(), messageId));
    }

    // ---------------------------------------------------------------- traitement par le staff

    public record Outcome(boolean handled, String message) {
    }

    /**
     * Applique l'action choisie par un moderateur. La permission est verifiee par l'appelant : un
     * message Discord reste cliquable longtemps apres, y compris par quelqu'un qui a perdu son role.
     */
    public CompletableFuture<Outcome> handleAction(long reportId, String action, String moderator) {
        Repository repo = bridge.repository();
        if (repo == null) {
            return CompletableFuture.completedFuture(new Outcome(false, Lang.get("discord.unavailable")));
        }
        return repo.findReport(reportId).thenCompose(found -> {
            if (found.isEmpty()) {
                return CompletableFuture.completedFuture(new Outcome(false, Lang.get("report.unknown")));
            }
            Report report = found.get();
            String status = switch (action) {
                case ACTION_MUTE -> "MUTED";
                case ACTION_BAN -> "BANNED";
                default -> "IGNORED";
            };
            return repo.closeReport(reportId, status, moderator).thenCompose(closed -> {
                if (!closed) {
                    return CompletableFuture.completedFuture(
                            new Outcome(false, Lang.get("report.already_handled", "name", escape(report.targetName()))));
                }
                if (ACTION_IGNORE.equals(action)) {
                    return CompletableFuture.completedFuture(
                            new Outcome(true, Lang.get("report.done.ignored", "name", escape(report.targetName()))));
                }
                boolean ban = ACTION_BAN.equals(action);
                String durationText = ban ? BridgeConfig.REPORTS_BAN_DURATION.get() : BridgeConfig.REPORTS_MUTE_DURATION.get();
                long duration = Durations.parse(durationText);
                PlayerRef target = new PlayerRef(report.targetUuid(), report.targetName());
                String reason = Lang.get("report.sanction_reason", "id", report.id(), "reason", report.reason());
                return bridge.sanctions().apply(new Request(ban ? SanctionType.BAN : SanctionType.MUTE, target,
                                duration > 0 ? duration : null, Text.stripFormatting(reason), moderator,
                                SanctionService.ORIGIN_DISCORD, true))
                        .thenApply(sanction -> new Outcome(true, Lang.get(ban ? "report.done.banned" : "report.done.muted",
                                "name", escape(report.targetName()),
                                "duration", sanction.isPermanent() ? Lang.get("common.permanent")
                                        : Durations.format(sanction.expiresAt() - sanction.createdAt()))));
            });
        });
    }

    /** Liste des joueurs connectes, pour l'autocompletion de /report. */
    public List<String> suggestions() {
        return bridge.onlineNames();
    }

    private static String escape(String text) {
        return MarkdownSanitizer.escape(text == null ? "" : text);
    }
}
