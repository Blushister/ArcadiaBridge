package com.arcadia.bridge.discord;

import java.awt.Color;
import java.time.Instant;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.data.Models.Link;
import com.arcadia.bridge.data.Models.Sanction;
import com.arcadia.bridge.data.Models.SanctionType;
import com.arcadia.bridge.i18n.Lang;
import com.arcadia.bridge.util.Durations;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;

/** Embeds envoyes dans le salon de logs staff. */
public final class DiscordLogs {

    private static final Color RED = new Color(0xE0, 0x3E, 0x3E);
    private static final Color ORANGE = new Color(0xE8, 0x8A, 0x2E);
    private static final Color YELLOW = new Color(0xE6, 0xC2, 0x29);
    private static final Color GREEN = new Color(0x3B, 0xA5, 0x5D);
    private static final Color BLUE = new Color(0x4F, 0x7C, 0xD9);

    private DiscordLogs() {
    }

    public static MessageEmbed sanctionApplied(Sanction s) {
        Color color = switch (s.type()) {
            case BAN -> RED;
            case MUTE -> ORANGE;
            case WARN -> YELLOW;
        };
        String titleKey = switch (s.type()) {
            case BAN -> s.isPermanent() ? "log.ban" : "log.tempban";
            case MUTE -> s.isPermanent() ? "log.mute" : "log.tempmute";
            case WARN -> "log.warn";
        };
        EmbedBuilder eb = base(color, Lang.get(titleKey), s.name(), s.discordId())
                .addField(Lang.get("log.field.reason"), escape(s.reason()), false)
                .addField(Lang.get("log.field.by"), escape(s.actor()), true)
                .addField(Lang.get("log.field.server"), escape(s.serverId()), true);
        if (!s.isPermanent()) {
            eb.addField(Lang.get("log.field.duration"), Durations.format(s.expiresAt() - s.createdAt()), true);
        }
        return eb.build();
    }

    public static MessageEmbed sanctionRevoked(Sanction s, String actor) {
        String titleKey = s.type() == SanctionType.BAN ? "log.unban" : "log.unmute";
        return base(GREEN, Lang.get(titleKey), s.name(), s.discordId())
                .addField(Lang.get("log.field.by"), escape(actor), true)
                .addField(Lang.get("log.field.server"), escape(Bridge.serverId()), true)
                .build();
    }

    public static MessageEmbed sanctionExpired(Sanction s) {
        String titleKey = s.type() == SanctionType.BAN ? "log.ban_expired" : "log.mute_expired";
        return base(GREEN, Lang.get(titleKey), s.name(), s.discordId()).build();
    }

    public static MessageEmbed linked(Link link) {
        return base(BLUE, Lang.get("log.linked"), link.name(), link.discordId()).build();
    }

    public static MessageEmbed unlinked(Link link, String actor) {
        return base(BLUE, Lang.get("log.unlinked"), link.name(), link.discordId())
                .addField(Lang.get("log.field.by"), escape(actor), true)
                .build();
    }

    private static EmbedBuilder base(Color color, String title, String playerName, Long discordId) {
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(color)
                .setTitle(title)
                .setTimestamp(Instant.now())
                .addField(Lang.get("log.field.player"), escape(playerName), true);
        if (discordId != null) {
            eb.addField(Lang.get("log.field.discord"), "<@" + discordId + ">", true);
        }
        return eb;
    }

    private static String escape(String text) {
        String value = text == null || text.isBlank() ? "-" : MarkdownSanitizer.escape(text);
        return DiscordBot.truncate(value, MessageEmbed.VALUE_MAX_LENGTH);
    }
}
