package com.arcadia.bridge.security;

import java.awt.Color;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

import com.arcadia.bridge.Bridge;
import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.data.Models.PlayerRef;
import com.arcadia.bridge.data.Repository;
import com.arcadia.bridge.i18n.Lang;
import com.mojang.logging.LogUtils;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * Detection des retours de joueurs bannis sous un autre compte.
 *
 * <p>L'adresse IP n'est jamais stockee : seule une empreinte salee l'est, et elle est oubliee au bout
 * du delai configure. La detection <b>alerte le staff et ne sanctionne jamais toute seule</b> : deux
 * joueurs peuvent legitimement partager une adresse (meme foyer, reseau d'etablissement, operateur
 * mobile), et un bannissement automatique finirait par frapper un innocent.
 *
 * <p>A noter : la verification Discord constitue deja la barriere principale, puisqu'il faut un second
 * compte Discord non lie pour revenir. Cette detection vise le joueur determine.
 */
public final class IpGuard {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Color PURPLE = new Color(0x8E, 0x5C, 0xD9);

    private final Bridge bridge;

    public IpGuard(Bridge bridge) {
        this.bridge = bridge;
    }

    /** Empreinte salee de l'adresse ; le sel est genere au premier demarrage et conserve en configuration. */
    private static String hash(String address) {
        String salt = BridgeConfig.SECURITY_IP_SALT.get();
        if (salt.isBlank()) {
            byte[] random = new byte[24];
            new SecureRandom().nextBytes(random);
            salt = HexFormat.of().formatHex(random);
            BridgeConfig.SECURITY_IP_SALT.set(salt);
            BridgeConfig.SECURITY_IP_SALT.save();
            LOGGER.info("[ArcadiaBridge] Sel de hachage des adresses genere.");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((salt + '|' + address).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String addressOf(ServerPlayer player) {
        SocketAddress remote = player.connection.getConnection().getRemoteAddress();
        if (remote instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        return remote == null ? "" : remote.toString();
    }

    /** A la connexion : enregistre l'empreinte et previent le staff si un compte banni la partage. */
    public void onJoin(ServerPlayer player) {
        Repository repo = bridge.repository();
        if (repo == null || !BridgeConfig.SECURITY_EVASION_ALERTS.get()) {
            return;
        }
        String address = addressOf(player);
        if (address.isBlank()) {
            return;
        }
        String ipHash = hash(address);
        String name = player.getGameProfile().getName();
        repo.saveIpHash(player.getUUID(), ipHash)
                .thenCompose(v -> repo.bannedSharingIp(ipHash, player.getUUID(), System.currentTimeMillis()))
                .thenAccept(banned -> {
                    if (!banned.isEmpty()) {
                        alert(name, banned);
                    }
                })
                .exceptionally(error -> {
                    LOGGER.warn("[ArcadiaBridge] Detection de contournement impossible : {}", error.toString());
                    return null;
                });
    }

    private void alert(String playerName, List<PlayerRef> banned) {
        String others = banned.stream().map(p -> MarkdownSanitizer.escape(p.name())).collect(Collectors.joining(", "));
        LOGGER.warn("[ArcadiaBridge] {} se connecte depuis la meme adresse que : {}", playerName, others);
        bridge.channels().alert(Lang.get("alert.evasion", "name", playerName,
                "others", banned.stream().map(PlayerRef::name).collect(Collectors.joining(", "))));
        if (!bridge.discord().isReady()) {
            return;
        }
        bridge.discord().sendLog(new EmbedBuilder()
                .setColor(PURPLE)
                .setTitle(Lang.get("log.evasion"))
                .setTimestamp(Instant.now())
                .setDescription(Lang.get("log.evasion.note"))
                .addField(Lang.get("log.field.player"), MarkdownSanitizer.escape(playerName), true)
                .addField(Lang.get("log.field.server"), MarkdownSanitizer.escape(Bridge.serverId()), true)
                .addField(Lang.get("log.evasion.matches"), others, false)
                .build());
    }

    /** Oubli periodique des empreintes (serveur primaire). */
    public void purge() {
        Repository repo = bridge.repository();
        if (repo == null) {
            return;
        }
        long retention = BridgeConfig.SECURITY_IP_RETENTION_DAYS.get() * 86_400_000L;
        repo.purgeIpHashes(System.currentTimeMillis() - retention);
    }
}
