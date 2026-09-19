package com.arcadia.bridge.discord;

import java.time.Instant;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.config.BridgeConfig;
import com.mojang.logging.LogUtils;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.messages.MessageRequest;
import org.slf4j.Logger;

/** Connexion au bot Discord et operations utilisees par le reste du mod. */
public final class DiscordBot {

    private static final Logger LOGGER = LogUtils.getLogger();
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
                    SlashCommands.register(guild);
                }
                ready = true;
                LOGGER.info("[ArcadiaBridge] Bot Discord connecte a '{}'.", guild.getName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.error("[ArcadiaBridge] Connexion au bot Discord impossible : {}", e.toString());
            }
        }, "ArcadiaBridge-Discord-Start");
        starter.setDaemon(true);
        starter.start();
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

    public void sendLog(MessageEmbed embed) {
        TextChannel channel = channel(BridgeConfig.snowflake(BridgeConfig.DISCORD_LOGS_CHANNEL_ID));
        if (isReady() && channel != null) {
            channel.sendMessageEmbeds(embed).queue(null, this::logFailure);
        }
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

    public void logFailure(Throwable error) {
        LOGGER.warn("[ArcadiaBridge] Action Discord echouee : {}", error.toString());
    }

    static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
