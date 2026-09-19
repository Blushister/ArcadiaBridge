package com.arcadia.bridge.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Acces MySQL. Toutes les requetes passent par {@link #async} : elles tournent sur un pool de threads
 * dedie, jamais sur le thread principal du serveur.
 */
public final class Database implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(Database.class);
    private static final Pattern SAFE_PREFIX = Pattern.compile("[A-Za-z0-9_]{0,20}");

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private final HikariDataSource dataSource;
    private final ExecutorService executor;
    private final String prefix;

    private Database(HikariDataSource dataSource, int threads, String prefix) {
        this.dataSource = dataSource;
        this.prefix = prefix;
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "ArcadiaBridge-DB-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newFixedThreadPool(threads, factory);
    }

    public static Database connect(String host, int port, String database, String user, String password,
                                   String parameters, String tablePrefix, int poolSize) throws SQLException {
        if (!SAFE_PREFIX.matcher(tablePrefix).matches()) {
            throw new SQLException("tablePrefix invalide (lettres, chiffres et _ uniquement) : " + tablePrefix);
        }
        HikariConfig config = new HikariConfig();
        String query = parameters == null || parameters.isBlank() ? "" : "?" + parameters;
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + query);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000);
        config.setMaxLifetime(1_800_000);
        config.setPoolName("ArcadiaBridge");

        HikariDataSource ds;
        try {
            ds = new HikariDataSource(config);
        } catch (RuntimeException e) {
            throw new SQLException("Connexion MySQL impossible (" + host + ":" + port + "/" + database + ") : "
                    + e.getMessage(), e);
        }
        Database db = new Database(ds, poolSize, tablePrefix);
        db.migrate();
        return db;
    }

    /** Nom de table avec prefixe. */
    public String t(String name) {
        return prefix + name;
    }

    public <T> CompletableFuture<T> async(SqlWork<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection c = dataSource.getConnection()) {
                return work.run(c);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    /** Execute une transaction : commit si tout passe, rollback sinon. */
    public <T> CompletableFuture<T> transaction(SqlWork<T> work) {
        return async(c -> {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        });
    }

    /** Execution bloquante : reservee au demarrage du serveur. */
    public <T> T sync(SqlWork<T> work) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return work.run(c);
        }
    }

    private void migrate() throws SQLException {
        String opts = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        sync(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS " + t("players") + " ("
                        + "uuid CHAR(36) NOT NULL PRIMARY KEY,"
                        + "name VARCHAR(16) NOT NULL,"
                        + "last_server VARCHAR(64) NULL,"
                        + "last_seen BIGINT NOT NULL,"
                        + "INDEX idx_name (name))" + opts);
                s.execute("CREATE TABLE IF NOT EXISTS " + t("links") + " ("
                        + "uuid CHAR(36) NOT NULL PRIMARY KEY,"
                        + "discord_id BIGINT NOT NULL,"
                        + "name VARCHAR(16) NOT NULL,"
                        + "linked_at BIGINT NOT NULL,"
                        + "UNIQUE KEY uk_discord (discord_id))" + opts);
                s.execute("CREATE TABLE IF NOT EXISTS " + t("codes") + " ("
                        + "uuid CHAR(36) NOT NULL PRIMARY KEY,"
                        + "name VARCHAR(16) NOT NULL,"
                        + "code_hash CHAR(64) NOT NULL,"
                        + "expires_at BIGINT NOT NULL,"
                        + "UNIQUE KEY uk_code (code_hash))" + opts);
                s.execute("CREATE TABLE IF NOT EXISTS " + t("sanctions") + " ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                        + "type VARCHAR(8) NOT NULL,"
                        + "uuid CHAR(36) NOT NULL,"
                        + "name VARCHAR(16) NOT NULL,"
                        + "discord_id BIGINT NULL,"
                        + "reason VARCHAR(512) NOT NULL,"
                        + "actor VARCHAR(64) NOT NULL,"
                        + "origin VARCHAR(16) NOT NULL,"
                        + "server_id VARCHAR(64) NOT NULL,"
                        + "created_at BIGINT NOT NULL,"
                        + "expires_at BIGINT NULL,"
                        + "active TINYINT(1) NOT NULL DEFAULT 1,"
                        + "revoked_at BIGINT NULL,"
                        + "revoked_by VARCHAR(64) NULL,"
                        + "discord_timeout_until BIGINT NULL,"
                        + "INDEX idx_player (uuid, type, active),"
                        + "INDEX idx_active (active, type),"
                        + "INDEX idx_discord (discord_id))" + opts);
            }
            return null;
        });
        LOGGER.info("[ArcadiaBridge] Tables MySQL pretes (prefixe '{}').", prefix);
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        dataSource.close();
    }
}
