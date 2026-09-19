package com.arcadia.bridge.config;

import java.util.List;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Configuration du mod : config/arcadiabridge.toml (type COMMON : jamais envoyee aux clients,
 * ce qui compte puisqu'elle contient le token du bot et le mot de passe MySQL).
 */
public final class BridgeConfig {

    public static final ModConfigSpec SPEC;

    // general
    public static final ModConfigSpec.ConfigValue<String> SERVER_ID;
    public static final ModConfigSpec.ConfigValue<String> SERVER_NAME;
    public static final ModConfigSpec.ConfigValue<String> LANGUAGE;

    // database
    public static final ModConfigSpec.ConfigValue<String> DB_HOST;
    public static final ModConfigSpec.IntValue DB_PORT;
    public static final ModConfigSpec.ConfigValue<String> DB_NAME;
    public static final ModConfigSpec.ConfigValue<String> DB_USER;
    public static final ModConfigSpec.ConfigValue<String> DB_PASSWORD;
    public static final ModConfigSpec.ConfigValue<String> DB_PARAMETERS;
    public static final ModConfigSpec.ConfigValue<String> DB_TABLE_PREFIX;
    public static final ModConfigSpec.IntValue DB_POOL_SIZE;

    // discord
    public static final ModConfigSpec.ConfigValue<String> DISCORD_TOKEN;
    public static final ModConfigSpec.ConfigValue<String> DISCORD_GUILD_ID;
    public static final ModConfigSpec.ConfigValue<String> DISCORD_CHAT_CHANNEL_ID;
    public static final ModConfigSpec.ConfigValue<String> DISCORD_VERIFICATION_CHANNEL_ID;
    public static final ModConfigSpec.ConfigValue<String> DISCORD_LOGS_CHANNEL_ID;
    public static final ModConfigSpec.BooleanValue DISCORD_PRIMARY;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DISCORD_STAFF_ROLE_IDS;
    public static final ModConfigSpec.ConfigValue<String> DISCORD_TEXT_PREFIX;

    // chat
    public static final ModConfigSpec.BooleanValue CHAT_TO_DISCORD;
    public static final ModConfigSpec.BooleanValue CHAT_TO_MINECRAFT;
    public static final ModConfigSpec.ConfigValue<String> CHAT_DISCORD_FORMAT;
    public static final ModConfigSpec.ConfigValue<String> CHAT_MINECRAFT_FORMAT;
    public static final ModConfigSpec.IntValue CHAT_MAX_LENGTH;
    public static final ModConfigSpec.BooleanValue CHAT_LUCKPERMS_PREFIX;

    // status
    public static final ModConfigSpec.BooleanValue STATUS_SERVER;
    public static final ModConfigSpec.BooleanValue STATUS_JOIN_LEAVE;
    public static final ModConfigSpec.BooleanValue STATUS_DEATHS;

    // verification
    public static final ModConfigSpec.BooleanValue VERIFICATION_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> VERIFICATION_MODE;
    public static final ModConfigSpec.IntValue VERIFICATION_GRACE_SECONDS;
    public static final ModConfigSpec.IntValue VERIFICATION_CODE_MINUTES;
    public static final ModConfigSpec.BooleanValue VERIFICATION_BLOCK_CHAT;
    public static final ModConfigSpec.IntValue VERIFICATION_MAX_ATTEMPTS;
    public static final ModConfigSpec.ConfigValue<String> VERIFICATION_ROLE_ID;

    // sanctions
    public static final ModConfigSpec.BooleanValue SANCTIONS_TO_DISCORD;
    public static final ModConfigSpec.BooleanValue SANCTIONS_FROM_DISCORD_BANS;
    public static final ModConfigSpec.IntValue SANCTIONS_POLL_SECONDS;
    public static final ModConfigSpec.IntValue SANCTIONS_MAX_WARNS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SANCTIONS_MUTE_BLOCKED_COMMANDS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("general");
        SERVER_ID = b.comment("Identifiant unique de ce serveur (ex : survie, creatif). Enregistre avec chaque sanction.")
                .define("serverId", "survie");
        SERVER_NAME = b.comment("Nom affiche sur Discord pour ce serveur (vide = pas de nom affiche).")
                .define("serverName", "");
        LANGUAGE = b.comment("Langue des messages : fr_fr ou en_us. Les textes sont modifiables dans config/arcadiabridge/lang/.")
                .define("language", "fr_fr");
        b.pop();

        b.push("database");
        DB_HOST = b.define("host", "localhost");
        DB_PORT = b.defineInRange("port", 3306, 1, 65535);
        DB_NAME = b.define("database", "arcadia");
        DB_USER = b.define("username", "arcadia");
        DB_PASSWORD = b.define("password", "");
        DB_PARAMETERS = b.comment("Parametres JDBC ajoutes a l'URL de connexion.")
                .define("jdbcParameters", "useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8");
        DB_TABLE_PREFIX = b.comment("Prefixe des tables creees par le mod (base partagee entre tous les serveurs).")
                .define("tablePrefix", "ab_");
        DB_POOL_SIZE = b.defineInRange("poolSize", 4, 2, 32);
        b.pop();

        b.push("discord");
        DISCORD_TOKEN = b.comment("Token du bot Discord. Ne jamais le partager.")
                .define("token", "");
        DISCORD_GUILD_ID = b.comment("ID du serveur Discord.").define("guildId", "");
        DISCORD_CHAT_CHANNEL_ID = b.comment("ID du salon relaye avec le chat Minecraft.").define("chatChannelId", "");
        DISCORD_VERIFICATION_CHANNEL_ID = b.comment("ID du salon ou les joueurs font !verify (optionnel, /verify marche partout).")
                .define("verificationChannelId", "");
        DISCORD_LOGS_CHANNEL_ID = b.comment("ID du salon de logs staff (sanctions, liaisons).").define("logsChannelId", "");
        DISCORD_PRIMARY = b.comment(
                        "Un seul serveur Minecraft doit etre 'primary' si plusieurs utilisent le meme bot :",
                        "c'est lui qui gere les commandes slash, /verify, les bans Discord et les expirations.")
                .define("primary", true);
        DISCORD_STAFF_ROLE_IDS = b.comment("IDs des roles Discord autorises a utiliser /mc (en plus de la permission Bannir).")
                .defineListAllowEmpty("staffRoleIds", List.of(), () -> "", o -> o instanceof String);
        DISCORD_TEXT_PREFIX = b.comment("Prefixe des commandes texte (ex : !verify).").define("textCommandPrefix", "!");
        b.pop();

        b.push("chat");
        CHAT_TO_DISCORD = b.define("minecraftToDiscord", true);
        CHAT_TO_MINECRAFT = b.define("discordToMinecraft", true);
        CHAT_DISCORD_FORMAT = b.comment("Format sur Discord. Variables : {prefix} {name} {message} {server}")
                .define("discordFormat", "**{prefix}{name}** » {message}");
        CHAT_MINECRAFT_FORMAT = b.comment("Format en jeu (codes couleur &). Variables : {name} {message}")
                .define("minecraftFormat", "&9[Discord] &f{name}&7 » &f{message}");
        CHAT_MAX_LENGTH = b.comment("Longueur maximale d'un message Discord relaye en jeu.")
                .defineInRange("maxLength", 256, 16, 2000);
        CHAT_LUCKPERMS_PREFIX = b.comment("Afficher le prefixe LuckPerms du joueur sur Discord.")
                .define("luckPermsPrefix", true);
        b.pop();

        b.push("status");
        STATUS_SERVER = b.comment("Annonce du demarrage / arret du serveur.").define("serverStartStop", true);
        STATUS_JOIN_LEAVE = b.define("joinLeave", true);
        STATUS_DEATHS = b.define("deaths", true);
        b.pop();

        b.push("verification");
        VERIFICATION_ENABLED = b.define("enabled", true);
        VERIFICATION_MODE = b.comment(
                        "grace : le joueur peut jouer pendant graceSeconds avec son code affiche, puis est expulse.",
                        "kick  : le joueur est expulse immediatement, le code est affiche sur l'ecran de deconnexion.")
                .define("mode", "grace", o -> "grace".equals(o) || "kick".equals(o));
        VERIFICATION_GRACE_SECONDS = b.defineInRange("graceSeconds", 120, 10, 3600);
        VERIFICATION_CODE_MINUTES = b.comment("Duree de validite d'un code.").defineInRange("codeValidityMinutes", 10, 1, 1440);
        VERIFICATION_BLOCK_CHAT = b.comment("Bloquer le chat des joueurs non verifies.").define("blockChatWhileUnverified", true);
        VERIFICATION_MAX_ATTEMPTS = b.comment("Codes faux autorises par utilisateur Discord et par tranche de 10 minutes.")
                .defineInRange("maxAttempts", 5, 1, 50);
        VERIFICATION_ROLE_ID = b.comment("Role Discord donne apres verification (vide = aucun).").define("verifiedRoleId", "");
        b.pop();

        b.push("sanctions");
        SANCTIONS_TO_DISCORD = b.comment("Appliquer les bans/mutes en jeu au compte Discord lie (ban / timeout).")
                .define("applyToDiscord", true);
        SANCTIONS_FROM_DISCORD_BANS = b.comment("Un ban/deban fait directement sur Discord s'applique au compte Minecraft lie.")
                .define("discordBansToMinecraft", true);
        SANCTIONS_POLL_SECONDS = b.comment("Frequence de synchronisation avec la base partagee (autres serveurs, expirations).")
                .defineInRange("syncIntervalSeconds", 15, 5, 300);
        SANCTIONS_MAX_WARNS = b.defineInRange("maxWarns", 3, 1, 100);
        SANCTIONS_MUTE_BLOCKED_COMMANDS = b.comment("Commandes interdites aux joueurs mutes.")
                .defineListAllowEmpty("muteBlockedCommands",
                        List.of("msg", "tell", "w", "me", "teammsg", "tm", "r", "reply", "say"),
                        () -> "", o -> o instanceof String);
        b.pop();

        SPEC = b.build();
    }

    private BridgeConfig() {
    }

    /** Lit un ID Discord (snowflake) stocke en texte ; 0 si vide ou invalide. */
    public static long snowflake(ModConfigSpec.ConfigValue<String> value) {
        String raw = value.get().trim();
        if (raw.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseUnsignedLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public static boolean isKickMode() {
        return "kick".equals(VERIFICATION_MODE.get());
    }
}
