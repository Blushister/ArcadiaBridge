package com.arcadia.bridge;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.data.BridgeCache;
import com.arcadia.bridge.data.Models.Link;
import com.arcadia.bridge.data.Models.PlayerRef;
import com.arcadia.bridge.data.Models.Sanction;
import com.arcadia.bridge.data.Repository;
import com.arcadia.bridge.db.Database;
import com.arcadia.bridge.discord.DiscordBot;
import com.arcadia.bridge.i18n.Lang;
import com.arcadia.bridge.sanction.SanctionService;
import com.arcadia.bridge.verification.VerificationService;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

/**
 * Point central du mod : detient la base, le cache, le bot Discord et les services.
 * Cree au demarrage du serveur, detruit a son arret.
 */
public final class Bridge {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile Bridge instance;

    private final MinecraftServer server;
    private final Path configDir;
    private final BridgeCache cache = new BridgeCache();
    private final DiscordBot discord;
    private final VerificationService verification;
    private final SanctionService sanctions;
    private final ScheduledExecutorService scheduler;

    private volatile Database database;
    private volatile Repository repository;
    private volatile boolean stopping;

    private Bridge(MinecraftServer server) {
        this.server = server;
        this.configDir = FMLPaths.CONFIGDIR.get().resolve(ArcadiaBridge.MOD_ID);
        this.discord = new DiscordBot(this);
        this.verification = new VerificationService(this);
        this.sanctions = new SanctionService(this);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ArcadiaBridge-Sync");
            t.setDaemon(true);
            return t;
        });
    }

    /** @return l'instance active, ou null si le serveur n'est pas demarre. */
    public static Bridge get() {
        return instance;
    }

    // ---------------------------------------------------------------- cycle de vie

    static void start(MinecraftServer server) {
        Bridge bridge = new Bridge(server);
        instance = bridge;
        Lang.load(bridge.configDir, BridgeConfig.LANGUAGE.get());
        bridge.connectDatabase();
        if (bridge.database != null) {
            bridge.syncNow();
        }
        bridge.discord.start();
        int interval = BridgeConfig.SANCTIONS_POLL_SECONDS.get();
        bridge.scheduler.scheduleWithFixedDelay(bridge::safeSync, interval, interval, TimeUnit.SECONDS);
    }

    static void stop() {
        Bridge bridge = instance;
        if (bridge == null) {
            return;
        }
        bridge.stopping = true;
        bridge.scheduler.shutdownNow();
        bridge.discord.shutdown();
        Database db = bridge.database;
        if (db != null) {
            db.close();
        }
        instance = null;
    }

    private void connectDatabase() {
        try {
            database = Database.connect(BridgeConfig.DB_HOST.get(), BridgeConfig.DB_PORT.get(), BridgeConfig.DB_NAME.get(),
                    BridgeConfig.DB_USER.get(), BridgeConfig.DB_PASSWORD.get(), BridgeConfig.DB_PARAMETERS.get(),
                    BridgeConfig.DB_TABLE_PREFIX.get(), BridgeConfig.DB_POOL_SIZE.get());
            repository = new Repository(database);
            LOGGER.info("[ArcadiaBridge] Connecte a MySQL.");
        } catch (Exception e) {
            LOGGER.error("[ArcadiaBridge] Base de donnees indisponible : {}. Verification et sanctions suspendues,"
                    + " nouvelle tentative a la prochaine synchronisation.", e.getMessage());
        }
    }

    private void safeSync() {
        try {
            if (database == null) {
                connectDatabase();
                if (database == null) {
                    return;
                }
            }
            syncNow();
        } catch (Throwable t) {
            LOGGER.error("[ArcadiaBridge] Echec de la synchronisation avec la base", t);
        }
    }

    /**
     * Recharge liaisons et sanctions depuis la base partagee (changements faits sur les autres serveurs ou
     * sur Discord), puis applique les consequences en jeu. Le serveur primaire gere aussi les expirations.
     */
    private void syncNow() {
        long start = System.currentTimeMillis();
        Map<UUID, Link> links = repository.loadAllLinks().join();
        List<Sanction> active = repository.loadActive(start).join();
        cache.replaceLinks(links, start);
        cache.replaceSanctions(active, start);
        runOnMain(this::enforceOnlinePlayers);

        if (isPrimary() && discord.isReady()) {
            long now = System.currentTimeMillis();
            sanctions.handleExpired(repository.expireDue(now).join());
            sanctions.refreshDiscordTimeouts(now);
            repository.purgeExpiredCodes(now);
        }
    }

    /** Expulse les joueurs connectes qui viennent d'etre bannis (depuis un autre serveur ou Discord). */
    private void enforceOnlinePlayers() {
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            cache.activeBan(player.getUUID()).ifPresent(ban -> player.connection.disconnect(SanctionService.banScreen(ban)));
        }
    }

    // ---------------------------------------------------------------- acces

    public MinecraftServer server() {
        return server;
    }

    public BridgeCache cache() {
        return cache;
    }

    public DiscordBot discord() {
        return discord;
    }

    public VerificationService verification() {
        return verification;
    }

    public SanctionService sanctions() {
        return sanctions;
    }

    /** @return le depot SQL, ou null si la base est indisponible. */
    public Repository repository() {
        return repository;
    }

    public boolean isDatabaseReady() {
        return repository != null && cache.isLoaded();
    }

    public boolean isStopping() {
        return stopping;
    }

    public static boolean isPrimary() {
        return BridgeConfig.DISCORD_PRIMARY.get();
    }

    public static String serverId() {
        return BridgeConfig.SERVER_ID.get();
    }

    public void runOnMain(Runnable task) {
        if (!stopping) {
            server.execute(task);
        }
    }

    /**
     * Retrouve un joueur par pseudo : d'abord parmi les connectes, puis dans la base partagee
     * (tous les joueurs passes sur un serveur Arcadia), enfin dans le cache de profils du serveur.
     */
    public CompletableFuture<PlayerRef> resolvePlayer(String name) {
        // La liste des joueurs ne se lit que sur le thread principal.
        CompletableFuture<PlayerRef> online = server.isSameThread()
                ? CompletableFuture.completedFuture(findOnline(name))
                : CompletableFuture.supplyAsync(() -> findOnline(name), server);
        return online.thenCompose(found -> {
            if (found != null || repository == null) {
                return CompletableFuture.completedFuture(found);
            }
            return findOffline(name);
        });
    }

    private PlayerRef findOnline(String name) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(name);
        return player == null ? null : new PlayerRef(player.getUUID(), player.getGameProfile().getName());
    }

    private CompletableFuture<PlayerRef> findOffline(String name) {
        return repository.findPlayerByName(name).thenApply(found -> found.orElseGet(() -> {
            var cacheProfiles = server.getProfileCache();
            if (cacheProfiles == null) {
                return null;
            }
            return cacheProfiles.get(name).map(p -> new PlayerRef(p.getId(), p.getName())).orElse(null);
        }));
    }
}
