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

    public enum RedeemStatus {
        SUCCESS, INVALID_CODE, ACCOUNT_ALREADY_LINKED, DISCORD_ALREADY_LINKED
    }

    public record RedeemResult(RedeemStatus status, Link link) {
    }
}
