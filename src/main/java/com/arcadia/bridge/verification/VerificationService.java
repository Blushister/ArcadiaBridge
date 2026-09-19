package com.arcadia.bridge.verification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.data.Models.Link;
import com.arcadia.bridge.data.Models.RedeemResult;
import com.arcadia.bridge.data.Repository;
import com.arcadia.bridge.discord.DiscordLogs;
import com.arcadia.bridge.i18n.Lang;
import com.arcadia.bridge.permission.Permissions;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * Verification des comptes : a la premiere connexion, le joueur recoit un code (visible par lui seul)
 * qu'il saisit sur Discord avec /verify. Le code est aleatoire (SecureRandom), stocke uniquement sous
 * forme de hash, a usage unique et limite dans le temps.
 */
public final class VerificationService {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Sans 0/O ni 1/I pour eviter les confusions a la saisie. */
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 6;
    private static final long FAILURE_WINDOW_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long REMINDER_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30);
    private static final long LINK_CHECK_INTERVAL_MS = TimeUnit.SECONDS.toMillis(5);

    private final Bridge bridge;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<Long, Deque<Long>> failures = new ConcurrentHashMap<>();
    private final AtomicBoolean linkCheckRunning = new AtomicBoolean();
    private long nextLinkCheck;

    private static final class Pending {
        String code;
        long codeExpiresAt;
        final long kickAt;
        long nextReminder;

        Pending(String code, long codeExpiresAt, long kickAt, long nextReminder) {
            this.code = code;
            this.codeExpiresAt = codeExpiresAt;
            this.kickAt = kickAt;
            this.nextReminder = nextReminder;
        }
    }

    public VerificationService(Bridge bridge) {
        this.bridge = bridge;
    }

    // ---------------------------------------------------------------- cote Minecraft

    /** Appele a la connexion d'un joueur (thread principal). */
    public void onJoin(ServerPlayer player) {
        if (!BridgeConfig.VERIFICATION_ENABLED.get() || bridge.cache().isLinked(player.getUUID())) {
            return;
        }
        if (!bridge.isDatabaseReady()) {
            LOGGER.warn("[ArcadiaBridge] Base indisponible : verification ignoree pour {}.", player.getGameProfile().getName());
            return;
        }
        if (Permissions.has(player, Permissions.VERIFICATION_BYPASS)) {
            return;
        }
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().getName();
        long now = System.currentTimeMillis();

        if (BridgeConfig.isKickMode()) {
            issueCode(uuid, name).thenAccept(code -> bridge.runOnMain(() -> {
                ServerPlayer online = bridge.server().getPlayerList().getPlayer(uuid);
                if (online != null && !bridge.cache().isLinked(uuid)) {
                    online.connection.disconnect(kickScreen(code));
                }
            })).exceptionally(e -> logError("emission du code", e));
            return;
        }

        long kickAt = now + TimeUnit.SECONDS.toMillis(BridgeConfig.VERIFICATION_GRACE_SECONDS.get());
        issueCode(uuid, name).thenAccept(code -> bridge.runOnMain(() -> {
            ServerPlayer online = bridge.server().getPlayerList().getPlayer(uuid);
            if (online == null || bridge.cache().isLinked(uuid)) {
                return;
            }
            Pending p = new Pending(code, now + codeValidityMs(), kickAt, now + REMINDER_INTERVAL_MS);
            pending.put(uuid, p);
            sendCodeMessage(online, p);
        })).exceptionally(e -> logError("emission du code", e));
    }

    public void onLeave(ServerPlayer player) {
        pending.remove(player.getUUID());
    }

    public boolean isPending(UUID uuid) {
        return pending.containsKey(uuid);
    }

    /** Appele chaque seconde sur le thread principal. */
    public void tick() {
        if (pending.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Pending> entry : pending.entrySet()) {
            UUID uuid = entry.getKey();
            Pending p = entry.getValue();
            ServerPlayer player = bridge.server().getPlayerList().getPlayer(uuid);
            if (player == null) {
                pending.remove(uuid);
            } else if (bridge.cache().isLinked(uuid)) {
                pending.remove(uuid);
                player.sendSystemMessage(Lang.mc("verify.success_ingame"));
            } else if (now >= p.kickAt) {
                pending.remove(uuid);
                if (p.codeExpiresAt - now < TimeUnit.MINUTES.toMillis(2)) {
                    // Le code expire bientot : on en donne un neuf sur l'ecran de deconnexion.
                    issueCode(uuid, player.getGameProfile().getName())
                            .thenAccept(code -> bridge.runOnMain(() -> disconnectIfPresent(uuid, code)))
                            .exceptionally(e -> logError("emission du code", e));
                } else {
                    player.connection.disconnect(kickScreen(p.code));
                }
            } else if (now >= p.nextReminder) {
                p.nextReminder = now + REMINDER_INTERVAL_MS;
                if (now >= p.codeExpiresAt) {
                    refreshCode(uuid, player.getGameProfile().getName(), p);
                } else {
                    sendCodeMessage(player, p);
                }
            }
        }
        if (now >= nextLinkCheck) {
            nextLinkCheck = now + LINK_CHECK_INTERVAL_MS;
            checkPendingLinks();
        }
    }

    /**
     * La verification peut avoir ete faite via le bot d'un autre serveur : on interroge la base pour les
     * seuls joueurs en attente, pour les debloquer en quelques secondes plutot qu'a la synchro suivante.
     */
    private void checkPendingLinks() {
        Repository repo = bridge.repository();
        if (repo == null || pending.isEmpty() || !linkCheckRunning.compareAndSet(false, true)) {
            return;
        }
        Set<UUID> uuids = Set.copyOf(pending.keySet());
        repo.findLinks(uuids).whenComplete((found, error) -> {
            linkCheckRunning.set(false);
            if (found != null && !found.isEmpty()) {
                found.values().forEach(bridge.cache()::putLink);
            }
        });
    }

    private void refreshCode(UUID uuid, String name, Pending p) {
        issueCode(uuid, name).thenAccept(code -> bridge.runOnMain(() -> {
            p.code = code;
            p.codeExpiresAt = System.currentTimeMillis() + codeValidityMs();
            ServerPlayer player = bridge.server().getPlayerList().getPlayer(uuid);
            if (player != null) {
                sendCodeMessage(player, p);
            }
        })).exceptionally(e -> logError("renouvellement du code", e));
    }

    private void disconnectIfPresent(UUID uuid, String code) {
        ServerPlayer player = bridge.server().getPlayerList().getPlayer(uuid);
        if (player != null && !bridge.cache().isLinked(uuid)) {
            player.connection.disconnect(kickScreen(code));
        }
    }

    /** /arcadia_verify en jeu : statut de liaison, ou nouveau code si le joueur n'est pas lie. */
    public void showStatus(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (bridge.cache().isLinked(uuid)) {
            player.sendSystemMessage(Lang.mc("verify.already_linked"));
            return;
        }
        if (!bridge.isDatabaseReady()) {
            player.sendSystemMessage(Lang.mc("common.unavailable"));
            return;
        }
        issueCode(uuid, player.getGameProfile().getName()).thenAccept(code -> bridge.runOnMain(() -> {
            ServerPlayer online = bridge.server().getPlayerList().getPlayer(uuid);
            if (online == null) {
                return;
            }
            long now = System.currentTimeMillis();
            Pending existing = pending.get(uuid);
            if (existing != null) {
                existing.code = code;
                existing.codeExpiresAt = now + codeValidityMs();
                sendCodeMessage(online, existing);
            } else {
                sendCodeMessage(online, new Pending(code, now + codeValidityMs(), Long.MAX_VALUE, Long.MAX_VALUE));
            }
        })).exceptionally(e -> logError("emission du code", e));
    }

    private void sendCodeMessage(ServerPlayer player, Pending p) {
        long remaining = p.kickAt == Long.MAX_VALUE ? -1 : Math.max(0, (p.kickAt - System.currentTimeMillis()) / 1000);
        MutableComponent codeComponent = Lang.mc("verify.code_line", "code", p.code).withStyle(style -> style
                // On ne copie que le code : coller "/verify CODE" dans Discord n'ouvre pas la commande slash
                // et enverrait le code en message visible par tout le salon.
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, p.code))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Lang.mc("verify.click_to_copy", "code", p.code))));
        player.sendSystemMessage(Lang.mc("verify.header"));
        player.sendSystemMessage(codeComponent);
        player.sendSystemMessage(Lang.mc("verify.instructions", "code", p.code,
                "minutes", BridgeConfig.VERIFICATION_CODE_MINUTES.get()));
        if (remaining >= 0) {
            player.sendSystemMessage(Lang.mc("verify.kick_warning", "seconds", remaining));
        }
    }

    private static Component kickScreen(String code) {
        return Lang.mc("verify.kick_screen", "code", code, "minutes", BridgeConfig.VERIFICATION_CODE_MINUTES.get());
    }

    // ---------------------------------------------------------------- codes

    /** Genere un nouveau code pour ce joueur (remplace le precedent) et l'enregistre hache en base. */
    private CompletableFuture<String> issueCode(UUID uuid, String name) {
        Repository repo = bridge.repository();
        if (repo == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("base indisponible"));
        }
        String code = generateCode();
        return repo.saveCode(uuid, name, hash(code), System.currentTimeMillis() + codeValidityMs())
                .thenApply(v -> code);
    }

    private String generateCode() {
        char[] chars = new char[CODE_LENGTH];
        for (int i = 0; i < CODE_LENGTH; i++) {
            chars[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(chars);
    }

    static String normalize(String input) {
        return input == null ? "" : input.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    static String hash(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long codeValidityMs() {
        return TimeUnit.MINUTES.toMillis(BridgeConfig.VERIFICATION_CODE_MINUTES.get());
    }

    // ---------------------------------------------------------------- cote Discord

    public enum Outcome {
        SUCCESS, INVALID, TOO_MANY_ATTEMPTS, DISCORD_ALREADY_LINKED, ACCOUNT_ALREADY_LINKED, UNAVAILABLE
    }

    public record VerifyResult(Outcome outcome, Link link) {
    }

    /** /verify sur Discord (serveur primaire uniquement). */
    public CompletableFuture<VerifyResult> redeem(long discordId, String rawCode) {
        Repository repo = bridge.repository();
        if (repo == null) {
            return CompletableFuture.completedFuture(new VerifyResult(Outcome.UNAVAILABLE, null));
        }
        long now = System.currentTimeMillis();
        if (recentFailures(discordId, now) >= BridgeConfig.VERIFICATION_MAX_ATTEMPTS.get()) {
            return CompletableFuture.completedFuture(new VerifyResult(Outcome.TOO_MANY_ATTEMPTS, null));
        }
        String code = normalize(rawCode);
        if (code.length() != CODE_LENGTH) {
            recordFailure(discordId, now);
            return CompletableFuture.completedFuture(new VerifyResult(Outcome.INVALID, null));
        }
        return repo.redeemCode(hash(code), discordId, now).thenApply(result -> {
            Outcome outcome = toOutcome(result);
            if (outcome == Outcome.INVALID) {
                recordFailure(discordId, now);
            } else if (outcome == Outcome.SUCCESS) {
                onLinked(result.link());
            }
            return new VerifyResult(outcome, result.link());
        });
    }

    private void onLinked(Link link) {
        bridge.cache().putLink(link);
        bridge.discord().setVerifiedRole(link.discordId(), true);
        bridge.discord().sendLog(DiscordLogs.linked(link));
        LOGGER.info("[ArcadiaBridge] {} ({}) lie au compte Discord {}.", link.name(), link.uuid(), link.discordId());
        bridge.runOnMain(() -> {
            ServerPlayer player = bridge.server().getPlayerList().getPlayer(link.uuid());
            if (player != null && pending.remove(link.uuid()) != null) {
                player.sendSystemMessage(Lang.mc("verify.success_ingame"));
            }
        });
    }

    /** Supprime la liaison d'un joueur (commande staff). */
    public CompletableFuture<java.util.Optional<Link>> unlink(UUID uuid, String actor) {
        Repository repo = bridge.repository();
        if (repo == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("base indisponible"));
        }
        return repo.deleteLink(uuid).thenApply(removed -> {
            removed.ifPresent(link -> {
                bridge.cache().removeLink(uuid);
                bridge.discord().setVerifiedRole(link.discordId(), false);
                bridge.discord().sendLog(DiscordLogs.unlinked(link, actor));
            });
            return removed;
        });
    }

    private static Outcome toOutcome(RedeemResult result) {
        return switch (result.status()) {
            case SUCCESS -> Outcome.SUCCESS;
            case INVALID_CODE -> Outcome.INVALID;
            case ACCOUNT_ALREADY_LINKED -> Outcome.ACCOUNT_ALREADY_LINKED;
            case DISCORD_ALREADY_LINKED -> Outcome.DISCORD_ALREADY_LINKED;
        };
    }

    private int recentFailures(long discordId, long now) {
        Deque<Long> deque = failures.get(discordId);
        if (deque == null) {
            return 0;
        }
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst() < now - FAILURE_WINDOW_MS) {
                deque.pollFirst();
            }
            return deque.size();
        }
    }

    private void recordFailure(long discordId, long now) {
        Deque<Long> deque = failures.computeIfAbsent(discordId, id -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(now);
        }
        if (failures.size() > 10_000) {
            failures.entrySet().removeIf(e -> recentFailures(e.getKey(), now) == 0);
        }
    }

    private static Void logError(String action, Throwable e) {
        LOGGER.error("[ArcadiaBridge] Echec ({}) : {}", action, e.toString());
        return null;
    }
}
