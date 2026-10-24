package com.arcadia.bridge.discord;

import java.util.concurrent.TimeUnit;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.i18n.Lang;
import com.arcadia.bridge.tabs.ChatTabs;
import com.arcadia.bridge.tabs.TabsProtocol;
import com.arcadia.bridge.util.Text;
import com.arcadia.bridge.util.VerifyInput;
import com.arcadia.bridge.verification.VerificationService.VerifyResult;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.guild.GuildBanEvent;
import net.dv8tion.jda.api.events.guild.GuildUnbanEvent;
import com.arcadia.bridge.report.ReportService;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Evenements Discord : chat relaye en jeu, commandes, bans faits sur Discord. */
public final class DiscordListener extends ListenerAdapter {

    private final Bridge bridge;

    public DiscordListener(Bridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot() || event.isWebhookMessage()
                || event.getGuild().getIdLong() != BridgeConfig.snowflake(BridgeConfig.DISCORD_GUILD_ID)) {
            return;
        }
        Message message = event.getMessage();
        String raw = message.getContentRaw().trim();
        long channelId = event.getChannel().getIdLong();
        long chatChannel = BridgeConfig.snowflake(BridgeConfig.DISCORD_CHAT_CHANNEL_ID);
        long verifyChannel = BridgeConfig.snowflake(BridgeConfig.DISCORD_VERIFICATION_CHANNEL_ID);

        if (channelId == chatChannel || channelId == verifyChannel) {
            String code = VerifyInput.extractCode(raw, BridgeConfig.DISCORD_TEXT_PREFIX.get());
            if (code != null) {
                if (Bridge.isPrimary()) {
                    handleTextVerify(event, code);
                }
                return;
            }
        }

        if (channelId == chatChannel && BridgeConfig.CHAT_TO_MINECRAFT.get()) {
            relayToMinecraft(event, message);
        }
    }

    private void relayToMinecraft(MessageReceivedEvent event, Message message) {
        String content = message.getContentDisplay();
        if (!message.getAttachments().isEmpty()) {
            content = (content + " " + Lang.get("chat.attachments", "count", message.getAttachments().size())).trim();
        }
        if (content.isBlank()) {
            return;
        }
        String name = event.getMember() != null ? event.getMember().getEffectiveName() : event.getAuthor().getName();
        String line = Text.fill(Text.colorize(BridgeConfig.CHAT_MINECRAFT_FORMAT.get()),
                "name", Text.forMinecraft(name, 32),
                "message", Text.forMinecraft(content, BridgeConfig.CHAT_MAX_LENGTH.get()));
        Component component = Component.literal(line);
        bridge.runOnMain(() -> {
            bridge.server().sendSystemMessage(component);
            for (ServerPlayer player : bridge.server().getPlayerList().getPlayers()) {
                ChatTabs.deliver(player, TabsProtocol.CHANNEL_DISCORD, "", "", component);
            }
        });
    }

    /** "!verify CODE" : on supprime le message (le code ne reste pas visible) et on repond brievement. */
    private void handleTextVerify(MessageReceivedEvent event, String code) {
        Message message = event.getMessage();
        message.delete().queue(null, error -> { /* permission Gerer les messages absente : tant pis */ });
        if (code.isEmpty()) {
            reply(event, DiscordText.verifyUsage());
            return;
        }
        bridge.verification().redeem(event.getAuthor().getIdLong(), code)
                .thenAccept(result -> reply(event, "<@" + event.getAuthor().getId() + "> " + DiscordText.verifyResult(result)))
                .exceptionally(error -> {
                    bridge.discord().logFailure(error);
                    reply(event, DiscordText.verifyResult(null));
                    return null;
                });
    }

    private static void reply(MessageReceivedEvent event, String text) {
        event.getChannel().sendMessage(text).queue(sent -> sent.delete().queueAfter(30, TimeUnit.SECONDS,
                null, error -> { }));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!Bridge.isPrimary()) {
            return;
        }
        switch (event.getName()) {
            case SlashCommands.VERIFY -> handleSlashVerify(event);
            case SlashCommands.MC -> SlashCommands.handleStaff(bridge, event);
            case SlashCommands.ONLINE -> SlashCommands.handleOnline(bridge, event);
            default -> { }
        }
    }

    private void handleSlashVerify(SlashCommandInteractionEvent event) {
        var option = event.getOption("code");
        if (option == null) {
            event.reply(DiscordText.verifyUsage()).setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue();
        bridge.verification().redeem(event.getUser().getIdLong(), option.getAsString())
                .thenAccept((VerifyResult result) -> event.getHook().editOriginal(DiscordText.verifyResult(result)).queue())
                .exceptionally(error -> {
                    bridge.discord().logFailure(error);
                    event.getHook().editOriginal(DiscordText.verifyResult(null)).queue();
                    return null;
                });
    }

    /** Boutons des signalements : la permission est revalidee au clic, pas seulement a l'affichage. */
    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!Bridge.isPrimary() || !id.startsWith(ReportService.BUTTON_PREFIX)) {
            return;
        }
        String[] parts = id.substring(ReportService.BUTTON_PREFIX.length()).split(":");
        if (parts.length != 2) {
            return;
        }
        if (!SlashCommands.isStaffMember(event.getMember())) {
            event.reply(Lang.get("discord.no_permission")).setEphemeral(true).queue();
            return;
        }
        long reportId;
        try {
            reportId = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            return;
        }
        String moderator = event.getUser().getName() + " (Discord)";
        event.deferReply(true).queue();
        bridge.reports().handleAction(reportId, parts[1], moderator).thenAccept(outcome -> {
            event.getHook().editOriginal(outcome.message()).queue();
            if (outcome.handled()) {
                // Le signalement est clos : on retire les boutons pour eviter un second clic.
                event.getMessage().editMessageComponents().queue(null, error -> { });
            }
        }).exceptionally(error -> {
            bridge.discord().logFailure(error);
            event.getHook().editOriginal(Lang.get("discord.error")).queue();
            return null;
        });
    }

    @Override
    public void onGuildBan(GuildBanEvent event) {
        if (Bridge.isPrimary() && event.getGuild().getIdLong() == BridgeConfig.snowflake(BridgeConfig.DISCORD_GUILD_ID)) {
            bridge.sanctions().onDiscordBan(event.getUser().getIdLong(), null);
        }
    }

    @Override
    public void onGuildUnban(GuildUnbanEvent event) {
        if (Bridge.isPrimary() && event.getGuild().getIdLong() == BridgeConfig.snowflake(BridgeConfig.DISCORD_GUILD_ID)) {
            bridge.sanctions().onDiscordUnban(event.getUser().getIdLong());
        }
    }
}
