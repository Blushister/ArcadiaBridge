package com.arcadia.bridge.discord;

import java.time.Instant;
import java.util.List;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.config.BridgeConfig;
import com.mojang.logging.LogUtils;
import com.arcadia.bridge.i18n.Lang;
import com.arcadia.bridge.report.ReportService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.Webhook;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.messages.MessageRequest;
import org.slf4j.Logger;

/** Connexion au bot Discord et operations utilisees par le reste du mod. */
public final class DiscordBot {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String WEBHOOK_NAME = "ArcadiaBridge";
    /** Duree maximale d'un timeout Discord (28 jours), avec une petite marge. */
    public static final long MAX_TIMEOUT_MS = TimeUnit.DAYS.toMillis(28) - TimeUnit.MINUTES.toMillis(5);

    private final Bridge bridge;
    private volatile JDA jda;
    private volatile boolean ready;

    public DiscordBot(Bridge bridge) {
        this.bridge = bridge;
    }

    public void start() {
        String token = BridgeConfig.DISCORD_TOKEN.get().trim();
        if (token.isEmpty()) {
            LOGGER.warn("[ArcadiaBridge] Aucun token Discord dans config/arcadiabridge.toml : bot desactive.");
            return;
        }
        // Aucun message du bot ne peut mentionner @everyone, un role ou un membre.
        MessageRequest.setDefaultMentions(EnumSet.noneOf(Message.MentionType.class));
        MessageRequest.setDefaultMentionRepliedUser(false);

        Thread starter = new Thread(() -> {
            try {
                jda = JDABuilder.createLight(token,
                                GatewayIntent.GUILD_MESSAGES,
                                GatewayIntent.MESSAGE_CONTENT,
                                GatewayIntent.GUILD_MODERATION)
                        .addEventListeners(new DiscordListener(bridge))
                        .build();
                jda.awaitReady();
                Guild guild = guild();
                if (guild == null) {
                    LOGGER.error("[ArcadiaBridge] Serveur Discord introuvable (guildId = {}). Le bot y est-il invite ?",
                            BridgeConfig.DISCORD_GUILD_ID.get());
                    return;
                }
                if (Bridge.isPrimary()) {
                    SlashCommands.register(jda, guild);
                }
                ready = true;
                LOGGER.info("[ArcadiaBridge] Bot Discord connecte a '{}'.", guild.getName());
                ensureWebhook();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.error("[ArcadiaBridge] Connexion au bot Discord impossible : {}", e.toString());
            }
        }, "ArcadiaBridge-Discord-Start");
        starter.setDaemon(true);
        starter.start();
    }

    /**
     * Prepare le webhook du salon de chat : on reutilise celui du mod s'il existe, sinon on le cree.
     * Evite de faire circuler une URL de webhook (qui permet de poster sans authentification) dans une
     * configuration ou une conversation. Sans la permission "Gerer les webhooks", on garde les messages
     * classiques du bot.
     */
    private void ensureWebhook() {
        if (!BridgeConfig.isRichPresentation() || !BridgeConfig.CHAT_WEBHOOK_URL.get().isBlank()) {
            return;
        }
        TextChannel channel = channel(BridgeConfig.snowflake(BridgeConfig.DISCORD_CHAT_CHANNEL_ID));
        Guild guild = guild();
        if (channel == null || guild == null
                || !guild.getSelfMember().hasPermission(channel, Permission.MANAGE_WEBHOOKS)) {
            return;
        }
        channel.retrieveWebhooks().queue(hooks -> {
            for (Webhook hook : hooks) {
                if (WEBHOOK_NAME.equals(hook.getName()) && hook.getToken() != null) {
                    ChatWebhook.setRuntimeUrl(hook.getUrl());
                    LOGGER.info("[ArcadiaBridge] Webhook de chat reutilise dans #{}.", channel.getName());
                    return;
                }
            }
            channel.createWebhook(WEBHOOK_NAME).queue(hook -> {
                ChatWebhook.setRuntimeUrl(hook.getUrl());
                LOGGER.info("[ArcadiaBridge] Webhook de chat cree dans #{}.", channel.getName());
            }, error -> LOGGER.warn("[ArcadiaBridge] Creation du webhook impossible : {}", error.toString()));
        }, error -> LOGGER.warn("[ArcadiaBridge] Lecture des webhooks impossible : {}", error.toString()));
    }

    public void shutdown() {
        ready = false;
        JDA current = jda;
        if (current == null) {
            return;
        }
        current.shutdown();
        try {
            if (!current.awaitShutdown(5, TimeUnit.SECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException e) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public boolean isReady() {
        return ready && jda != null;
    }

    public JDA jda() {
        return jda;
    }

    public Guild guild() {
        JDA current = jda;
        long id = BridgeConfig.snowflake(BridgeConfig.DISCORD_GUILD_ID);
        return current == null || id == 0 ? null : current.getGuildById(id);
    }

    private TextChannel channel(long id) {
        Guild guild = guild();
        return guild == null || id == 0 ? null : guild.getTextChannelById(id);
    }

    // ---------------------------------------------------------------- messages

    public void sendChat(String text) {
        TextChannel channel = channel(BridgeConfig.snowflake(BridgeConfig.DISCORD_CHAT_CHANNEL_ID));
        if (isReady() && channel != null && !text.isBlank()) {
            channel.sendMessage(truncate(text, Message.MAX_CONTENT_LENGTH)).queue(null, this::logFailure);
        }
    }

    /** Envoi bloquant (max 5 s), utilise pour l'annonce d'arret du serveur. */
    public void sendChatBlocking(String text) {
        TextChannel channel = channel(BridgeConfig.snowflake(BridgeConfig.DISCORD_CHAT_CHANNEL_ID));
        if (isReady() && channel != null) {
            try {
                channel.sendMessage(truncate(text, Message.MAX_CONTENT_LENGTH)).submit().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                logFailure(e);
            }
        }
    }

    /** Encart poste dans le salon de chat (annonces de connexion, de mort, de demarrage). */
    public void sendChatEmbed(MessageEmbed embed) {
        TextChannel channel = channel(BridgeConfig.snowflake(BridgeConfig.DISCORD_CHAT_CHANNEL_ID));
        if (isReady() && channel != null) {
            channel.sendMessageEmbeds(embed).queue(null, this::logFailure);
        }
    }

    /** Version bloquante (max 5 s) pour l'annonce d'arret du serveur. */
    public void sendChatEmbedBlocking(MessageEmbed embed) {
        TextChannel channel = channel(BridgeConfig.snowflake(BridgeConfig.DISCORD_CHAT_CHANNEL_ID));
        if (isReady() && channel != null) {
            try {
                channel.sendMessageEmbeds(embed).submit().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                logFailure(e);
            }
        }
    }

    public void sendLog(MessageEmbed embed) {
        TextChannel channel = channel(BridgeConfig.snowflake(BridgeConfig.DISCORD_LOGS_CHANNEL_ID));
        if (isReady() && channel != null) {
            channel.sendMessageEmbeds(embed).queue(null, this::logFailure);
        }
    }

    /** Salon des signalements : celui configure, sinon le salon de logs. */
    private TextChannel reportChannel() {
        long id = BridgeConfig.snowflake(BridgeConfig.REPORTS_CHANNEL_ID);
        return channel(id != 0 ? id : BridgeConfig.snowflake(BridgeConfig.DISCORD_LOGS_CHANNEL_ID));
    }

    /** Poste un signalement avec ses boutons d'action, puis transmet l'identifiant du message. */
    public void sendReport(MessageEmbed embed, long reportId, java.util.function.LongConsumer onSent) {
        TextChannel channel = reportChannel();
        if (!isReady() || channel == null) {
            return;
        }
        channel.sendMessageEmbeds(embed).setComponents(ActionRow.of(reportButtons(reportId)))
                .queue(message -> onSent.accept(message.getIdLong()), this::logFailure);
    }

    public void editReportMessage(long messageId, MessageEmbed embed, long reportId) {
        TextChannel channel = reportChannel();
        if (isReady() && channel != null) {
            channel.editMessageEmbedsById(messageId, embed)
                    .setComponents(ActionRow.of(reportButtons(reportId)))
                    .queue(null, this::logFailure);
        }
    }

    private static List<Button> reportButtons(long reportId) {
        String base = ReportService.BUTTON_PREFIX + reportId + ":";
        return List.of(
                Button.danger(base + ReportService.ACTION_BAN, Lang.get("report.button.ban")),
                Button.primary(base + ReportService.ACTION_MUTE, Lang.get("report.button.mute")),
                Button.secondary(base + ReportService.ACTION_IGNORE, Lang.get("report.button.ignore")));
    }

    // ---------------------------------------------------------------- moderation

    public CompletableFuture<Void> ban(long userId, String reason) {
        Guild guild = guild();
        if (!isReady() || guild == null) {
            return CompletableFuture.completedFuture(null);
        }
        return guild.ban(UserSnowflake.fromId(userId), 0, TimeUnit.SECONDS)
                .reason(truncate(reason, 512)).submit();
    }

    public CompletableFuture<Void> unban(long userId) {
        Guild guild = guild();
        if (!isReady() || guild == null) {
            return CompletableFuture.completedFuture(null);
        }
        return guild.unban(UserSnowflake.fromId(userId)).submit();
    }

    /** Pose un timeout Discord jusqu'a 'until' (plafonne a 28 jours) ; renvoie la fin effective. */
    public CompletableFuture<Long> timeout(long userId, long until, String reason) {
        Guild guild = guild();
        if (!isReady() || guild == null) {
            return CompletableFuture.completedFuture(null);
        }
        long capped = Math.min(until, System.currentTimeMillis() + MAX_TIMEOUT_MS);
        return guild.timeoutUntil(UserSnowflake.fromId(userId), Instant.ofEpochMilli(capped))
                .reason(truncate(reason, 512)).submit().thenApply(v -> capped);
    }

    public CompletableFuture<Void> removeTimeout(long userId) {
        Guild guild = guild();
        if (!isReady() || guild == null) {
            return CompletableFuture.completedFuture(null);
        }
        return guild.removeTimeout(UserSnowflake.fromId(userId)).submit();
    }

    public void setVerifiedRole(long userId, boolean give) {
        Guild guild = guild();
        long roleId = BridgeConfig.snowflake(BridgeConfig.VERIFICATION_ROLE_ID);
        if (!isReady() || guild == null || roleId == 0) {
            return;
        }
        Role role = guild.getRoleById(roleId);
        if (role == null) {
            LOGGER.warn("[ArcadiaBridge] Role de verification introuvable : {}", roleId);
            return;
        }
        var action = give ? guild.addRoleToMember(UserSnowflake.fromId(userId), role)
                : guild.removeRoleFromMember(UserSnowflake.fromId(userId), role);
        action.queue(null, this::logFailure);
    }

    private volatile int presencePlayers = -1;

    /** Ce serveur vient de devenir primaire : il (re)declare les commandes du mod. */
    public void onBecamePrimary() {
        JDA current = jda;
        Guild guild = guild();
        if (isReady() && current != null && guild != null) {
            SlashCommands.register(current, guild);
        }
        // Sinon, l'enregistrement se fera a la connexion du bot (voir start()).
    }

    /** Verifie que les commandes du mod existent toujours (serveur primaire). */
    public void ensureCommands() {
        JDA current = jda;
        if (isReady() && current != null) {
            SlashCommands.ensurePresent(current);
        }
    }

    /** Statut du bot : "X joueurs en ligne". N'appelle Discord que si le nombre a change. */
    public void updatePresence(int players) {
        JDA current = jda;
        if (!isReady() || current == null || players == presencePlayers) {
            return;
        }
        presencePlayers = players;
        try {
            current.getPresence().setActivity(Activity.playing(Lang.get("discord.presence", "players", players)));
        } catch (RuntimeException e) {
            logFailure(e);
        }
    }

    public void logFailure(Throwable error) {
        LOGGER.warn("[ArcadiaBridge] Action Discord echouee : {}", error.toString());
    }

    static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
