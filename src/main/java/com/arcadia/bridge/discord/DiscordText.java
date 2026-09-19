package com.arcadia.bridge.discord;

import com.arcadia.bridge.i18n.Lang;
import com.arcadia.bridge.verification.VerificationService.VerifyResult;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;

/** Reponses textuelles du bot. */
final class DiscordText {

    private DiscordText() {
    }

    static String verifyUsage() {
        return Lang.get("discord.verify.usage");
    }

    static String verifyResult(VerifyResult result) {
        if (result == null) {
            return Lang.get("discord.error");
        }
        return switch (result.outcome()) {
            case SUCCESS -> Lang.get("discord.verify.success", "name", MarkdownSanitizer.escape(result.link().name()));
            case INVALID -> Lang.get("discord.verify.invalid");
            case TOO_MANY_ATTEMPTS -> Lang.get("discord.verify.too_many");
            case DISCORD_ALREADY_LINKED -> Lang.get("discord.verify.discord_already_linked");
            case ACCOUNT_ALREADY_LINKED -> Lang.get("discord.verify.account_already_linked");
            case UNAVAILABLE -> Lang.get("discord.unavailable");
        };
    }
}
