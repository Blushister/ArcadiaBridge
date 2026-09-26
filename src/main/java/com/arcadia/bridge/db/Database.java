package com.arcadia.bridge.db;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
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

    private static final String DRIVER = "com.mysql.cj.jdbc.Driver";

    private final HikariDataSource dataSource;
    private final ExecutorService executor;
    private final String prefix;
    private final String jdbcUrl;
    private final String user;
    private final String password;

    private Database(HikariDataSource dataSource, int threads, String prefix, String jdbcUrl, String user,
                     String password) {
        this.dataSource = dataSource;
        this.prefix = prefix;
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
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
        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database + query;
        config.setJdbcUrl(jdbcUrl);
        config.setDriverClassName(DRIVER);
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
        Database db = new Database(ds, poolSize, tablePrefix, jdbcUrl, user, password);
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

    /**
     * Connexion dediee, hors du pool : sa fermeture coupe reellement la session MariaDB. Indispensable
     * pour un verrou GET_LOCK, qui vit aussi longtemps que la session : une connexion du pool, une fois
     * "fermee", retournerait au pool avec le verrou toujours pose.
     */
    public Connection rawConnection() throws SQLException {
        try {
            Driver driver = (Driver) Class.forName(DRIVER).getDeclaredConstructor().newInstance();
            Properties properties = new Properties();
            properties.setProperty("user", user);
            properties.setProperty("password", password);
            Connection connection = driver.connect(jdbcUrl, properties);
            if (connection == null) {
                throw new SQLException("URL JDBC non reconnue par le pilote MySQL");
            }
            return connection;
        } catch (ReflectiveOperationException e) {
            throw new SQLException("Pilote MySQL introuvable", e);
        }
    }

    public String databaseName() {
        String path = jdbcUrl.substring(jdbcUrl.lastIndexOf('/') + 1);
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    public String tablePrefix() {
        return prefix;
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
                s.execute("CREATE TABLE IF NOT EXISTS " + t("reports") + " ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                        + "reporter_uuid CHAR(36) NOT NULL,"
                        + "reporter_name VARCHAR(16) NOT NULL,"
                        + "target_uuid CHAR(36) NOT NULL,"
                        + "target_name VARCHAR(16) NOT NULL,"
                        + "reason VARCHAR(512) NOT NULL,"
                        + "server_id VARCHAR(64) NOT NULL,"
                        + "created_at BIGINT NOT NULL,"
                        + "count INT NOT NULL DEFAULT 1,"
                        + "status VARCHAR(16) NOT NULL DEFAULT 'OPEN',"
                        + "handled_by VARCHAR(64) NULL,"
                        + "handled_at BIGINT NULL,"
                        + "message_id BIGINT NULL,"
                        + "INDEX idx_open (status, target_uuid),"
                        + "INDEX idx_message (message_id))" + opts);
                s.execute("CREATE TABLE IF NOT EXISTS " + t("chat") + " ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                        + "server_id VARCHAR(64) NOT NULL,"
                        + "player_name VARCHAR(16) NOT NULL,"
                        + "prefix VARCHAR(64) NULL,"
                        + "message VARCHAR(512) NOT NULL,"
                        + "created_at BIGINT NOT NULL,"
                        + "INDEX idx_created (created_at))" + opts);
                s.execute("CREATE TABLE IF NOT EXISTS " + t("servers") + " ("
                        + "server_id VARCHAR(64) NOT NULL PRIMARY KEY,"
                        + "display_name VARCHAR(64) NULL,"
                        + "players INT NOT NULL DEFAULT 0,"
                        + "max_players INT NOT NULL DEFAULT 0,"
                        + "player_names TEXT NULL,"
                        + "updated_at BIGINT NOT NULL)" + opts);
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
            // Colonne ajoutee apres coup : empreinte d'adresse IP pour la detection de contournement.
            addColumnIfMissing(c, t("players"), "ip_hash", "CHAR(64) NULL");
            addColumnIfMissing(c, t("players"), "ip_seen_at", "BIGINT NULL");
            addColumnIfMissing(c, t("players"), "chat_global", "TINYINT(1) NULL");
            try (Statement s = c.createStatement()) {
                s.execute("CREATE INDEX idx_ip ON " + t("players") + " (ip_hash)");
            } catch (SQLException ignored) {
                // L'index existe deja : MySQL ne propose pas de CREATE INDEX IF NOT EXISTS.
            }
            return null;
        });
        LOGGER.info("[ArcadiaBridge] Tables MySQL pretes (prefixe '{}').", prefix);
    }

    /** MySQL 8 ne connait pas ADD COLUMN IF NOT EXISTS : on regarde d'abord le schema. */
    private static void addColumnIfMissing(Connection c, String table, String column, String definition)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM information_schema.columns"
                + " WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return;
                }
            }
        }
        try (Statement s = c.createStatement()) {
            s.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
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
