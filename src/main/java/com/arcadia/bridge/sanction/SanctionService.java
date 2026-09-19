package com.arcadia.bridge.sanction;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.data.Models.Link;
import com.arcadia.bridge.data.Models.PlayerRef;
import com.arcadia.bridge.data.Models.Sanction;
import com.arcadia.bridge.data.Models.SanctionType;
import com.arcadia.bridge.data.Repository;
import com.arcadia.bridge.discord.DiscordBot;
import com.arcadia.bridge.discord.DiscordLogs;
import com.arcadia.bridge.i18n.Lang;
import com.arcadia.bridge.util.Durations;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * Bans, mutes et avertissements. La base partagee est la reference : une sanction posee sur un serveur
 * (ou depuis Discord) s'applique partout, et au compte Discord lie si l'option est active.
 */
public final class SanctionService {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String ORIGIN_MINECRAFT = "minecraft";
    public static final String ORIGIN_DISCORD = "discord";

    private final Bridge bridge;

    public SanctionService(Bridge bridge) {
        this.bridge = bridge;
    }

    /**
     * @param durationMs     null = definitive (ignore pour WARN)
     * @param applyOnDiscord false quand la sanction vient deja de Discord (evite de la reappliquer)
     */
    public record Request(SanctionType type, PlayerRef target, Long durationMs, String reason, String actor,
                          String origin, boolean applyOnDiscord) {
    }

    // ---------------------------------------------------------------- appliquer

    public CompletableFuture<Sanction> apply(Request req) {
        Repository repo = bridge.repository();
        if (repo == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("base indisponible"));
        }
        long now = System.currentTimeMillis();
        Long expiresAt = req.type() == SanctionType.WARN || req.durationMs() == null ? null : now + req.durationMs();
        Long discordId = bridge.cache().link(req.target().uuid()).map(Link::discordId).orElse(null);
        Sanction draft = new Sanction(0, req.type(), req.target().uuid(), req.target().name(), discordId,
                req.reason(), req.actor(), req.origin(), Bridge.serverId(), now, expiresAt, true);

        // Un nouveau ban/mute remplace le precedent (ex : un tempban transforme en ban definitif).
        CompletableFuture<List<Sanction>> replaced = req.type() == SanctionType.WARN
                ? CompletableFuture.completedFuture(List.of())
                : repo.revokeActive(req.target().uuid(), req.type(), req.actor(), now);

        return replaced.thenCompose(old -> repo.insertSanction(draft)).thenApply(saved -> {
            bridge.cache().putSanction(saved);
            applyInGame(saved);
            if (req.applyOnDiscord()) {
                applyOnDiscord(saved);
            }
            bridge.discord().sendLog(DiscordLogs.sanctionApplied(saved));
            LOGGER.info("[ArcadiaBridge] {} {} par {} : {}", saved.type(), saved.name(), saved.actor(), saved.reason());
            return saved;
        });
    }

    private void applyInGame(Sanction s) {
        bridge.runOnMain(() -> {
            ServerPlayer player = bridge.server().getPlayerList().getPlayer(s.uuid());
            if (player == null) {
                return;
            }
            switch (s.type()) {
                case BAN -> player.connection.disconnect(banScreen(s));
                case MUTE -> player.sendSystemMessage(muteMessage(s));
                case WARN -> player.sendSystemMessage(Lang.mc("warn.notify", "reason", s.reason()));
            }
        });
    }

    private void applyOnDiscord(Sanction s) {
        if (!BridgeConfig.SANCTIONS_TO_DISCORD.get() || s.discordId() == null || !bridge.discord().isReady()) {
            return;
        }
        DiscordBot discord = bridge.discord();
        String reason = s.reason() + " (" + s.actor() + ")";
        if (s.type() == SanctionType.BAN) {
            discord.ban(s.discordId(), reason).exceptionally(this::discordFailure);
        } else if (s.type() == SanctionType.MUTE) {
            long until = s.expiresAt() != null ? s.expiresAt() : Long.MAX_VALUE;
            discord.timeout(s.discordId(), until, reason)
                    .thenCompose(effective -> effective == null ? CompletableFuture.completedFuture(null)
                            : bridge.repository().setDiscordTimeoutUntil(s.id(), effective))
                    .exceptionally(this::discordFailure);
        }
    }

    // ---------------------------------------------------------------- lever

    /** Leve le ban ou le mute en cours d'un joueur ; renvoie les sanctions levees (vide s'il n'y en avait pas). */
    public CompletableFuture<List<Sanction>> revoke(SanctionType type, PlayerRef target, String actor,
                                                    boolean applyOnDiscord) {
        Repository repo = bridge.repository();
        if (repo == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("base indisponible"));
        }
        return repo.revokeActive(target.uuid(), type, actor, System.currentTimeMillis()).thenApply(revoked -> {
            bridge.cache().removeSanction(target.uuid(), type);
            if (revoked.isEmpty()) {
                return revoked;
            }
            Sanction first = revoked.get(0);
            if (applyOnDiscord && BridgeConfig.SANCTIONS_TO_DISCORD.get()) {
                Long discordId = first.discordId() != null ? first.discordId()
                        : bridge.cache().link(target.uuid()).map(Link::discordId).orElse(null);
                if (discordId != null) {
                    if (type == SanctionType.BAN) {
                        bridge.discord().unban(discordId).exceptionally(this::discordFailure);
                    } else {
                        bridge.discord().removeTimeout(discordId).exceptionally(this::discordFailure);
                    }
                }
            }
            if (type == SanctionType.MUTE) {
                bridge.runOnMain(() -> {
                    ServerPlayer player = bridge.server().getPlayerList().getPlayer(target.uuid());
                    if (player != null) {
                        player.sendSystemMessage(Lang.mc("mute.lifted"));
                    }
                });
            }
            bridge.discord().sendLog(DiscordLogs.sanctionRevoked(first, actor));
            LOGGER.info("[ArcadiaBridge] {} leve pour {} par {}", type, target.name(), actor);
            return revoked;
        });
    }

    // ---------------------------------------------------------------- synchro (serveur primaire)

    /** Sanctions arrivees a expiration : debannir sur Discord et journaliser. */
    public void handleExpired(List<Sanction> expired) {
        for (Sanction s : expired) {
            if (s.type() == SanctionType.BAN && s.discordId() != null && BridgeConfig.SANCTIONS_TO_DISCORD.get()) {
                bridge.discord().unban(s.discordId()).exceptionally(this::discordFailure);
            }
            if (s.type() != SanctionType.WARN) {
                bridge.discord().sendLog(DiscordLogs.sanctionExpired(s));
            }
        }
    }

    /**
     * Un timeout Discord dure au plus 28 jours : pour un mute plus long ou definitif, on le reconduit
     * avant son terme.
     */
    public void refreshDiscordTimeouts(long now) {
        if (!BridgeConfig.SANCTIONS_TO_DISCORD.get()) {
            return;
        }
        Repository repo = bridge.repository();
        List<Sanction> mutes = repo.mutesNeedingTimeout(now, now + TimeUnit.DAYS.toMillis(1)).join();
        for (Sanction s : mutes) {
            long until = s.expiresAt() != null ? s.expiresAt() : Long.MAX_VALUE;
            bridge.discord().timeout(s.discordId(), until, s.reason())
                    .thenCompose(effective -> effective == null ? CompletableFuture.completedFuture(null)
                            : repo.setDiscordTimeoutUntil(s.id(), effective))
                    .exceptionally(error -> {
                        // Membre parti du Discord, permissions manquantes... : on reessaiera dans un jour.
                        repo.setDiscordTimeoutUntil(s.id(), now + TimeUnit.DAYS.toMillis(2));
                        return discordFailure(error);
                    });
        }
    }

    /** Un membre a ete banni directement sur Discord : on bannit son compte Minecraft lie. */
    public void onDiscordBan(long discordId, String reason) {
        Repository repo = bridge.repository();
        if (repo == null || !BridgeConfig.SANCTIONS_FROM_DISCORD_BANS.get()) {
            return;
        }
        repo.findLinkByDiscord(discordId).thenCompose(link -> {
            if (link.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            PlayerRef target = new PlayerRef(link.get().uuid(), link.get().name());
            // Si le ban vient du mod lui-meme (ban en jeu repercute sur Discord), il existe deja : rien a faire.
            return repo.findActive(target.uuid(), SanctionType.BAN, System.currentTimeMillis())
                    .thenCompose(existing -> existing.isPresent() ? CompletableFuture.completedFuture(null)
                            : apply(new Request(SanctionType.BAN, target, null,
                            reason == null || reason.isBlank() ? Lang.get("sanction.discord_ban_reason") : reason,
                            "Discord", ORIGIN_DISCORD, false)));
        }).exceptionally(this::discordFailure);
    }

    /** Un membre a ete debanni directement sur Discord : on debannit son compte Minecraft lie. */
    public void onDiscordUnban(long discordId) {
        Repository repo = bridge.repository();
        if (repo == null || !BridgeConfig.SANCTIONS_FROM_DISCORD_BANS.get()) {
            return;
        }
        repo.findLinkByDiscord(discordId).thenCompose(link -> link.isEmpty()
                        ? CompletableFuture.completedFuture(null)
                        : revoke(SanctionType.BAN, new PlayerRef(link.get().uuid(), link.get().name()), "Discord", false))
                .exceptionally(this::discordFailure);
    }

    // ---------------------------------------------------------------- messages

    public static Component banScreen(Sanction ban) {
        if (ban.isPermanent()) {
            return Lang.mc("ban.screen", "reason", ban.reason(), "actor", ban.actor());
        }
        return Lang.mc("ban.screen_temp", "reason", ban.reason(), "actor", ban.actor(),
                "remaining", Durations.format(ban.expiresAt() - System.currentTimeMillis()));
    }

    public static Component muteMessage(Sanction mute) {
        if (mute.isPermanent()) {
            return Lang.mc("mute.notify", "reason", mute.reason());
        }
        return Lang.mc("mute.notify_temp", "reason", mute.reason(),
                "remaining", Durations.format(mute.expiresAt() - System.currentTimeMillis()));
    }

    public Optional<Sanction> activeMute(ServerPlayer player) {
        return bridge.cache().activeMute(player.getUUID());
    }

    private <T> T discordFailure(Throwable error) {
        bridge.discord().logFailure(error);
        return null;
    }
}
