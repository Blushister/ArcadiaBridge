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
    public static final ModConfigSpec.ConfigValue<String> PUBLIC_ADDRESS;
    public static final ModConfigSpec.ConfigValue<String> PRESENTATION;

    // database
    public static final ModConfigSpec.ConfigValue<String> DB_HOST;
    public static final ModConfigSpec.IntValue DB_PORT;
    public static final ModConfigSpec.ConfigValue<String> DB_NAME;
    public static final ModConfigSpec.ConfigValue<String> DB_USER;
    public static final ModConfigSpec.ConfigValue<String> DB_PASSWORD;
    public static final ModConfigSpec.ConfigValue<String> DB_PARAMETERS;
    public static final ModConfigSpec.ConfigValue<String> DB_TABLE_PREFIX;
    public static final ModConfigSpec.IntValue DB_POOL_SIZE;
    public static final ModConfigSpec.BooleanValue DB_IMPORT_LEGACY;

    // discord
    public static final ModConfigSpec.ConfigValue<String> DISCORD_TOKEN;
    public static final ModConfigSpec.ConfigValue<String> DISCORD_GUILD_ID;
    public static final ModConfigSpec.ConfigValue<String> DISCORD_CHAT_CHANNEL_ID;
    public static final ModConfigSpec.ConfigValue<String> DISCORD_VERIFICATION_CHANNEL_ID;
    public static final ModConfigSpec.ConfigValue<String> DISCORD_LOGS_CHANNEL_ID;
    public static final ModConfigSpec.ConfigValue<String> DISCORD_PRIMARY_MODE;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DISCORD_STAFF_ROLE_IDS;
    public static final ModConfigSpec.ConfigValue<String> DISCORD_TEXT_PREFIX;
    public static final ModConfigSpec.BooleanValue DISCORD_STAFF_ONLY_COMMANDS;

    // chat
    public static final ModConfigSpec.BooleanValue CHAT_TO_DISCORD;
    public static final ModConfigSpec.BooleanValue CHAT_TO_MINECRAFT;
    public static final ModConfigSpec.ConfigValue<String> CHAT_DISCORD_FORMAT;
    public static final ModConfigSpec.ConfigValue<String> CHAT_MINECRAFT_FORMAT;
    public static final ModConfigSpec.IntValue CHAT_MAX_LENGTH;
    public static final ModConfigSpec.BooleanValue CHAT_LUCKPERMS_PREFIX;
    public static final ModConfigSpec.ConfigValue<String> CHAT_WEBHOOK_URL;
    public static final ModConfigSpec.ConfigValue<String> CHAT_AVATAR_URL;
    public static final ModConfigSpec.BooleanValue CHAT_CROSS_SERVER;
    public static final ModConfigSpec.ConfigValue<String> CHAT_CROSS_SERVER_FORMAT;
    public static final ModConfigSpec.ConfigValue<String> CHAT_CROSS_SERVER_TAG;
    public static final ModConfigSpec.BooleanValue CHAT_STAFF;
    public static final ModConfigSpec.ConfigValue<String> CHAT_STAFF_FORMAT;
    public static final ModConfigSpec.ConfigValue<String> CHAT_ALERT_FORMAT;
    public static final ModConfigSpec.BooleanValue CHAT_CHANNELS;
    public static final ModConfigSpec.ConfigValue<String> CHAT_CHANNEL_FORMAT;
    public static final ModConfigSpec.IntValue CHAT_CHANNEL_HISTORY;
    public static final ModConfigSpec.IntValue CHAT_CHANNEL_HISTORY_HOURS;
    public static final ModConfigSpec.BooleanValue CHAT_TYPING;
    public static final ModConfigSpec.BooleanValue CHAT_OFFLINE_MESSAGES;
    public static final ModConfigSpec.IntValue CHAT_OFFLINE_MAX;
    public static final ModConfigSpec.IntValue CHAT_OFFLINE_DAYS;
    public static final ModConfigSpec.BooleanValue CHAT_OFFLINE_DISCORD;
    public static final ModConfigSpec.BooleanValue CHAT_CROSS_SERVER_DEFAULT;
    public static final ModConfigSpec.IntValue CHAT_CROSS_SERVER_POLL;

    // status
    public static final ModConfigSpec.BooleanValue STATUS_SERVER;
    public static final ModConfigSpec.BooleanValue STATUS_JOIN_LEAVE;
    public static final ModConfigSpec.BooleanValue STATUS_DEATHS;
    public static final ModConfigSpec.BooleanValue STATUS_BOT_PRESENCE;

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

    // signalements
    public static final ModConfigSpec.BooleanValue REPORTS_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> REPORTS_CHANNEL_ID;
    public static final ModConfigSpec.IntValue REPORTS_COOLDOWN_SECONDS;
    public static final ModConfigSpec.ConfigValue<String> REPORTS_MUTE_DURATION;
    public static final ModConfigSpec.ConfigValue<String> REPORTS_BAN_DURATION;

    // securite
    public static final ModConfigSpec.BooleanValue SECURITY_EVASION_ALERTS;
    public static final ModConfigSpec.IntValue SECURITY_IP_RETENTION_DAYS;
    public static final ModConfigSpec.ConfigValue<String> SECURITY_IP_SALT;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("general");
        SERVER_ID = b.comment("Identifiant unique de ce serveur (ex : survie, creatif). Enregistre avec chaque sanction.")
                .define("serverId", "survie");
        SERVER_NAME = b.comment("Nom affiche sur Discord pour ce serveur (vide = pas de nom affiche).")
                .define("serverName", "");
        LANGUAGE = b.comment("Langue des messages : fr_fr ou en_us. Les textes sont modifiables dans config/arcadiabridge/lang/.")
                .define("language", "fr_fr");
        PUBLIC_ADDRESS = b.comment(
                "Adresse publique de ce serveur (ex : serveur1.arcadia-eop.com:25565). Avec ArcadiaChat, les joueurs",
                "des autres serveurs peuvent la rejoindre en cliquant sur un message venu d'ici. Vide = pas de lien.")
                .define("publicAddress", "");
        PRESENTATION = b.comment(
                        "Style des messages envoyes sur Discord :",
                        "  compact : tout est poste par le bot, une ligne par evenement.",
                        "  riche   : les joueurs parlent via un webhook (pseudo et tete de skin)",
                        "            et les annonces deviennent des encarts colores.")
                .define("presentation", "compact",
                        o -> "compact".equals(o) || "riche".equals(o) || "rich".equals(o));
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
        DB_IMPORT_LEGACY = b.comment(
                        "Au demarrage, recopier le registre des joueurs de l'ancien mod (table 'players' de la",
                        "meme base) : pseudos, UUID et derniere activite. L'ancienne table n'est jamais modifiee.")
                .define("importLegacyPlayers", true);
        b.pop();

        b.push("discord");
        DISCORD_TOKEN = b.comment("Token du bot Discord. Ne jamais le partager.")
                .define("token", "");
        DISCORD_GUILD_ID = b.comment("ID du serveur Discord.").define("guildId", "");
        DISCORD_CHAT_CHANNEL_ID = b.comment("ID du salon relaye avec le chat Minecraft.").define("chatChannelId", "");
        DISCORD_VERIFICATION_CHANNEL_ID = b.comment("ID du salon ou les joueurs font !verify (optionnel, /verify marche partout).")
                .define("verificationChannelId", "");
        DISCORD_LOGS_CHANNEL_ID = b.comment("ID du salon de logs staff (sanctions, liaisons).").define("logsChannelId", "");
        DISCORD_PRIMARY_MODE = b.comment(
                        "Le serveur primaire gere les commandes slash, /verify, les bans faits sur Discord et les",
                        "expirations. Un seul serveur l'est a la fois.",
                        "  auto   : election automatique entre les serveurs (verrou dans la base) ; si le primaire",
                        "           s'arrete ou plante, un autre prend le relais en moins de syncIntervalSeconds.",
                        "  always : toujours primaire (installation a un seul serveur).",
                        "  never  : jamais primaire (serveur de test, par exemple).")
                .define("primaryMode", "auto", o -> "auto".equals(o) || "always".equals(o) || "never".equals(o));
        DISCORD_STAFF_ROLE_IDS = b.comment("IDs des roles Discord autorises a utiliser /mc (en plus de la permission Bannir).")
                .defineListAllowEmpty("staffRoleIds", List.of(), () -> "", o -> o instanceof String);
        DISCORD_TEXT_PREFIX = b.comment("Prefixe des commandes texte (ex : !verify).").define("textCommandPrefix", "!");
        DISCORD_STAFF_ONLY_COMMANDS = b.comment(
                        "Reserver /verify et /online au staff (permission Bannir). Utile pendant une phase de test",
                        "sur un serveur Discord deja peuple, pour ne pas montrer les commandes a tout le monde.")
                .define("staffOnlyCommands", false);
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
        CHAT_WEBHOOK_URL = b.comment(
                        "URL de webhook du salon de chat (Parametres du salon > Integrations > Webhooks).",
                        "Si elle est remplie, les messages des joueurs sont postes avec leur pseudo et leur tete,",
                        "au lieu d'un message unique du bot. Vide = comportement classique.")
                .define("webhookUrl", "");
        CHAT_AVATAR_URL = b.comment("Image utilisee comme avatar. Variables : {uuid} {name}")
                .define("avatarUrl", "https://mc-heads.net/avatar/{uuid}/64");
        CHAT_CROSS_SERVER = b.comment(
                        "Afficher en jeu le chat des autres serveurs Arcadia (via la base partagee).",
                        "A activer sur chaque serveur qui doit voir et envoyer.")
                .define("crossServer", false);
        CHAT_CROSS_SERVER_FORMAT = b.comment("Format des messages venus d'un autre serveur. Variables : {server} {prefix} {name} {message}")
                .define("crossServerFormat", "&8[{server}] &f{prefix}{name}&7 : &f{message}");
        CHAT_CROSS_SERVER_TAG = b.comment(
                        "Quand le serveur d'origine met le chat en forme (LPC), sa ligne est reprise telle quelle,",
                        "couleurs comprises, precedee de ce repere. Variable : {server}")
                .define("crossServerTag", "&8[{server}] ");
        CHAT_CROSS_SERVER_DEFAULT = b.comment("Les joueurs voient le chat des autres serveurs par defaut (modifiable par /chat).")
                .define("crossServerByDefault", true);
        CHAT_CROSS_SERVER_POLL = b.comment("Frequence de releve du chat des autres serveurs, en secondes.")
                .defineInRange("crossServerIntervalSeconds", 2, 1, 30);
        CHAT_STAFF = b.comment(
                "Chat du staff, commun a tous les serveurs : /sc <message>, ou l'onglet Staff d'ArcadiaChat.",
                "Il recoit aussi les alertes (signalements, contournement de ban). Permission : arcadiabridge.chat.staff")
                .define("staffChat", true);
        CHAT_STAFF_FORMAT = b.comment("Format du chat staff. Variables : {server} {name} {message}")
                .define("staffFormat", "&c[Staff] &8[{server}] &f{name}&7 : &f{message}");
        CHAT_ALERT_FORMAT = b.comment("Format des alertes envoyees au staff en jeu. Variables : {server} {message}")
                .define("alertFormat", "&c[Alerte] &8[{server}] &e{message}");
        CHAT_CHANNELS = b.comment(
                "Canaux thematiques (commerce, recrutement...) crees par le staff avec /canal create, rejoints par",
                "les joueurs avec /canal join. Communs a tous les serveurs.")
                .define("channels", true);
        CHAT_CHANNEL_FORMAT = b.comment("Format des canaux thematiques. Variables : {channel} {server} {name} {message}")
                .define("channelFormat", "&b[#{channel}] &8[{server}] &f{name}&7 : &f{message}");
        CHAT_CHANNEL_HISTORY = b.comment("Nombre de messages recents montres a qui rejoint un canal (0 = aucun).")
                .defineInRange("channelHistory", 20, 0, 100);
        CHAT_CHANNEL_HISTORY_HOURS = b.comment("Duree de conservation de l'historique des canaux, en heures.")
                .defineInRange("channelHistoryHours", 2, 1, 720);
        CHAT_TYPING = b.comment("Avec ArcadiaChat : afficher aux autres joueurs du serveur qui est en train d'ecrire.")
                .define("typingIndicator", true);
        CHAT_OFFLINE_MESSAGES = b.comment(
                        "/msg a un joueur hors ligne (deja venu sur le reseau) : le message lui est remis a sa prochaine",
                        "connexion, sur n'importe quel serveur.")
                .define("offlineMessages", true);
        CHAT_OFFLINE_MAX = b.comment("Messages en attente au plus par joueur.")
                .defineInRange("offlineMessagesMax", 20, 1, 200);
        CHAT_OFFLINE_DAYS = b.comment("Duree de conservation d'un message non remis, en jours.")
                .defineInRange("offlineMessagesDays", 30, 1, 365);
        CHAT_OFFLINE_DISCORD = b.comment("Prevenir le joueur en message prive sur Discord, si son compte est lie.")
                .define("offlineMessagesDiscord", true);
        b.pop();

        b.push("status");
        STATUS_SERVER = b.comment("Annonce du demarrage / arret du serveur.").define("serverStartStop", true);
        STATUS_JOIN_LEAVE = b.define("joinLeave", true);
        STATUS_DEATHS = b.define("deaths", true);
        STATUS_BOT_PRESENCE = b.comment(
                        "Afficher le nombre de joueurs connectes dans le statut du bot (serveur primaire).",
                        "A laisser desactive si le bot est partage avec une autre application qui gere deja son statut.")
                .define("botPresence", false);
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

        b.push("reports");
        REPORTS_ENABLED = b.comment("Commande /report en jeu, transmise au staff sur Discord.")
                .define("enabled", true);
        REPORTS_CHANNEL_ID = b.comment("Salon des signalements (vide = salon de logs).")
                .define("channelId", "");
        REPORTS_COOLDOWN_SECONDS = b.comment("Delai minimal entre deux signalements par un meme joueur.")
                .defineInRange("cooldownSeconds", 60, 0, 3600);
        REPORTS_MUTE_DURATION = b.comment("Duree appliquee par le bouton 'Mute' du salon staff.")
                .define("muteButtonDuration", "1h");
        REPORTS_BAN_DURATION = b.comment("Duree appliquee par le bouton 'Ban' du salon staff.")
                .define("banButtonDuration", "1d");
        b.pop();

        b.push("security");
        SECURITY_EVASION_ALERTS = b.comment(
                        "Prevenir le staff quand un joueur se connecte depuis la meme adresse qu'un compte banni.",
                        "Alerte uniquement : aucune sanction automatique, les faux positifs sont frequents",
                        "(colocataires, reseau partage, operateur mobile).")
                .define("banEvasionAlerts", true);
        SECURITY_IP_RETENTION_DAYS = b.comment("Duree de conservation des empreintes d'adresse, en jours.")
                .defineInRange("ipRetentionDays", 90, 1, 3650);
        SECURITY_IP_SALT = b.comment(
                        "Sel utilise pour hacher les adresses. Genere automatiquement au premier demarrage.",
                        "Les adresses ne sont jamais stockees en clair. Changer ce sel efface l'historique de detection.")
                .define("ipSalt", "");
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

    /** Style riche : webhook pour les joueurs et encarts pour les annonces. */
    public static boolean isRichPresentation() {
        String value = PRESENTATION.get();
        return "riche".equals(value) || "rich".equals(value);
    }

    public static boolean isKickMode() {
        return "kick".equals(VERIFICATION_MODE.get());
    }
}
