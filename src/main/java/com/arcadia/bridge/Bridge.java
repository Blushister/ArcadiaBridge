package com.arcadia.bridge;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.arcadia.bridge.chat.ChatChannels;
import com.arcadia.bridge.chat.CrossServerChat;
import com.arcadia.bridge.chat.PollService;
import com.arcadia.bridge.moderation.ChatModeration;
import com.arcadia.bridge.config.BridgeConfig;
import com.arcadia.bridge.data.BridgeCache;
import com.arcadia.bridge.data.Models.Link;
import com.arcadia.bridge.data.Models.PlayerRef;
import com.arcadia.bridge.data.Models.Sanction;
import com.arcadia.bridge.data.Models.ServerStatus;
import com.arcadia.bridge.data.Repository;
import com.arcadia.bridge.db.Database;
import com.arcadia.bridge.db.PrimaryElection;
import com.arcadia.bridge.discord.DiscordBot;
import com.arcadia.bridge.i18n.Lang;
import com.arcadia.bridge.report.ReportService;
import com.arcadia.bridge.security.IpGuard;
import com.arcadia.bridge.tabs.ChatTabs;
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
    private final ReportService reports;
    private final CrossServerChat crossChat;
    private final ChatChannels channels;
    private final PollService polls;
    private final ChatModeration moderation;
    private final IpGuard ipGuard;
    private final ScheduledExecutorService scheduler;

    /** Photo des joueurs connectes, prise sur le thread principal et lue par le thread de synchro. */
    private volatile List<String> onlineNames = List.of();
    private volatile Database database;
    private volatile PrimaryElection election;
    private volatile boolean primary;
    private volatile Repository repository;
    private volatile boolean stopping;
    /** Compteur de cycles de synchronisation (thread de synchro uniquement). */
    private int syncCycles;
    private static final int COMMAND_CHECK_EVERY = 20;

    private Bridge(MinecraftServer server) {
        this.server = server;
        this.configDir = FMLPaths.CONFIGDIR.get().resolve(ArcadiaBridge.MOD_ID);
        this.discord = new DiscordBot(this);
        this.verification = new VerificationService(this);
        this.sanctions = new SanctionService(this);
        this.reports = new ReportService(this);
        this.crossChat = new CrossServerChat(this);
        this.channels = new ChatChannels(this);
        this.polls = new PollService(this);
        this.moderation = new ChatModeration(this);
        this.ipGuard = new IpGuard(this);
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
            bridge.importLegacyData();
            bridge.syncNow();
        }
        bridge.discord.start();
        bridge.crossChat.start();
        int interval = BridgeConfig.SANCTIONS_POLL_SECONDS.get();
        bridge.scheduler.scheduleWithFixedDelay(bridge::safeSync, interval, interval, TimeUnit.SECONDS);
        if (CrossServerChat.relayActive()) {
            int chatInterval = BridgeConfig.CHAT_CROSS_SERVER_POLL.get();
            bridge.scheduler.scheduleWithFixedDelay(bridge.crossChat::poll, chatInterval, chatInterval,
                    TimeUnit.SECONDS);
        }
    }

    static void stop() {
        Bridge bridge = instance;
        if (bridge == null) {
            return;
        }
        bridge.stopping = true;
        bridge.scheduler.shutdownNow();
        // Rendre le verrou tout de suite : un autre serveur reprend la main des son prochain cycle.
        PrimaryElection current = bridge.election;
        if (current != null) {
            current.close();
        }
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
            election = new PrimaryElection(database,
                    "arcadiabridge:" + database.databaseName() + ":" + database.tablePrefix() + "primary");
            LOGGER.info("[ArcadiaBridge] Connecte a MySQL.");
        } catch (Exception e) {
            LOGGER.error("[ArcadiaBridge] Base de donnees indisponible : {}. Verification et sanctions suspendues,"
                    + " nouvelle tentative a la prochaine synchronisation.", e.getMessage());
        }
    }

    /** Reprise des donnees de l'ancien mod (voir Repository#importLegacyPlayers). */
    private void importLegacyData() {
        if (!BridgeConfig.DB_IMPORT_LEGACY.get()) {
            return;
        }
        try {
            int rows = repository.importLegacyPlayers();
            if (rows >= 0) {
                LOGGER.info("[ArcadiaBridge] Registre des joueurs de l'ancien mod importe ({} lignes traitees).", rows);
            }
        } catch (Exception e) {
            LOGGER.error("[ArcadiaBridge] Import des joueurs de l'ancien mod impossible : {}", e.getMessage());
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
        updatePrimary();
        long start = System.currentTimeMillis();
        Map<UUID, Link> links = repository.loadAllLinks().join();
        List<Sanction> active = repository.loadActive(start).join();
        cache.replaceLinks(links, start);
        cache.replaceSanctions(active, start);
        runOnMain(this::enforceOnlinePlayers);

        // Chaque serveur signale son etat : c'est ce qui alimente /online et le statut du bot.
        repository.heartbeat(Bridge.serverId(), BridgeConfig.SERVER_NAME.get(), onlineNames,
                server.getMaxPlayers(), BridgeConfig.PUBLIC_ADDRESS.get()).join();
        // Serveurs en ligne, pour les liens "rejoindre ce serveur" d'ArcadiaChat.
        long seen = System.currentTimeMillis();
        List<ServerStatus> online = repository.loadServers().join().stream().filter(st -> st.isOnline(seen)).toList();
        channels.updateRemotePlayers(online);
        runOnMain(() -> ChatTabs.updateServers(online));
        polls.closeDue();
        moderation.reload();
        if (isPrimary() && discord.isReady()) {
            long now = System.currentTimeMillis();
            if (BridgeConfig.STATUS_BOT_PRESENCE.get()) {
                int total = repository.loadServers().join().stream()
                        .filter(st -> st.isOnline(now))
                        .mapToInt(ServerStatus::players).sum();
                discord.updatePresence(total);
            }
            // Toutes les ~5 minutes : nos commandes Discord existent-elles toujours ?
            if (++syncCycles % COMMAND_CHECK_EVERY == 0) {
                discord.ensureCommands();
            }
            sanctions.handleExpired(repository.expireDue(now).join());
            sanctions.refreshDiscordTimeouts(now);
            repository.purgeExpiredCodes(now);
            // Le chat inter-serveurs n'est qu'un relais : on ne garde pas d'historique en base.
            repository.purgeChat(now - TimeUnit.MINUTES.toMillis(5),
                    now - TimeUnit.HOURS.toMillis(BridgeConfig.CHAT_CHANNEL_HISTORY_HOURS.get()));
            ipGuard.purge();
            repository.purgeMail(now - TimeUnit.DAYS.toMillis(BridgeConfig.CHAT_OFFLINE_DAYS.get()));
            repository.purgeShares(now - TimeUnit.MINUTES.toMillis(30));
        }
    }

    /** Determine si ce serveur est primaire, selon le mode configure. */
    private void updatePrimary() {
        boolean now = switch (BridgeConfig.DISCORD_PRIMARY_MODE.get()) {
            case "always" -> true;
            case "never" -> false;
            default -> election != null && election.tick();
        };
        if (now != primary) {
            primary = now;
            LOGGER.info(now ? "[ArcadiaBridge] Ce serveur devient le serveur primaire."
                    : "[ArcadiaBridge] Ce serveur n'est plus le serveur primaire.");
            if (now) {
                discord.onBecamePrimary();
            }
        }
    }

    /** Appele chaque seconde sur le thread principal. */
    public void updateOnlineSnapshot() {
        onlineNames = server.getPlayerList().getPlayers().stream()
                .map(p -> p.getGameProfile().getName())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public List<String> onlineNames() {
        return onlineNames;
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

    public ReportService reports() {
        return reports;
    }

    public CrossServerChat crossChat() {
        return crossChat;
    }

    public PollService polls() {
        return polls;
    }

    public ChatModeration moderation() {
        return moderation;
    }

    public ChatChannels channels() {
        return channels;
    }

    public IpGuard ipGuard() {
        return ipGuard;
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

    /** Ce serveur est-il actuellement le serveur primaire ? (voir PrimaryElection) */
    public static boolean isPrimary() {
        Bridge bridge = instance;
        return bridge != null && bridge.primary;
    }

    public static String serverId() {
        return BridgeConfig.SERVER_ID.get();
    }

    /** Execute une tache sur le thread principal apres un delai. */
    public void runOnMainLater(Runnable task, long delayMillis) {
        if (!stopping) {
            scheduler.schedule(() -> runOnMain(task), delayMillis, TimeUnit.MILLISECONDS);
        }
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
