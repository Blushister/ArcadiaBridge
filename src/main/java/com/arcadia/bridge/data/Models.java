package com.arcadia.bridge.data;

import java.util.UUID;

/** Types de donnees partages par le mod. */
public final class Models {

    private Models() {
    }

    public enum SanctionType {
        BAN, MUTE, WARN
    }

    /** Joueur Minecraft connu (UUID + dernier pseudo). */
    public record PlayerRef(UUID uuid, String name) {
    }

    /** Liaison compte Minecraft <-> compte Discord. */
    public record Link(UUID uuid, long discordId, String name, long linkedAt) {
    }

    /**
     * Sanction stockee en base.
     *
     * @param expiresAt null = definitive
     * @param origin    "minecraft" ou "discord"
     */
    public record Sanction(long id, SanctionType type, UUID uuid, String name, Long discordId, String reason,
                           String actor, String origin, String serverId, long createdAt, Long expiresAt,
                           boolean active) {

        public boolean isPermanent() {
            return expiresAt == null;
        }

        public boolean isInEffect(long now) {
            return active && (expiresAt == null || expiresAt > now);
        }

        public Sanction withId(long newId) {
            return new Sanction(newId, type, uuid, name, discordId, reason, actor, origin, serverId, createdAt,
                    expiresAt, active);
        }
    }

    /**
     * Etat d'un serveur Arcadia, ecrit a chaque cycle de synchronisation dans la base partagee.
     * Permet a Discord de savoir qui est en ligne et ou, sans lien direct entre les serveurs.
     */
    public record ServerStatus(String serverId, String displayName, int players, int maxPlayers,
                               java.util.List<String> names, long updatedAt) {

        /** Un serveur qui n'a pas donne signe de vie depuis une minute est considere hors ligne. */
        public boolean isOnline(long now) {
            return now - updatedAt < 60_000L;
        }

        public String label() {
            return displayName == null || displayName.isBlank() ? serverId : displayName;
        }
    }

    /**
     * Signalement fait en jeu et transmis au staff sur Discord.
     *
     * @param count  nombre de signalements groupes pour ce joueur tant que le staff n'a pas tranche
     * @param status OPEN tant qu'il n'est pas traite, sinon l'action retenue (MUTED, BANNED, IGNORED)
     */
    public record Report(long id, UUID reporterUuid, String reporterName, UUID targetUuid, String targetName,
                         String reason, String serverId, long createdAt, int count, String status,
                         Long messageId) {
    }

    /**
     * Message de chat relaye d'un serveur a l'autre via la base partagee. {@code formatted} est la ligne
     * telle que le serveur d'origine l'a affichee (JSON de composant), ou null.
     */
    public record ChatLine(long id, String serverId, String playerName, String prefix, String message,
                           String formatted) {
    }

    public enum RedeemStatus {
        SUCCESS, INVALID_CODE, ACCOUNT_ALREADY_LINKED, DISCORD_ALREADY_LINKED
    }

    public record RedeemResult(RedeemStatus status, Link link) {
    }
}
