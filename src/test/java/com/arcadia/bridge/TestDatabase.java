package com.arcadia.bridge;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import com.arcadia.bridge.db.Database;

/**
 * Connexion a la base de test locale. Les identifiants sont surchargeables par variables
 * d'environnement (AB_TEST_HOST, AB_TEST_PORT, AB_TEST_DB, AB_TEST_USER, AB_TEST_PASSWORD).
 */
final class TestDatabase {

    static final String HOST = env("AB_TEST_HOST", "localhost");
    static final int PORT = Integer.parseInt(env("AB_TEST_PORT", "3306"));
    static final String NAME = env("AB_TEST_DB", "arcadia_test");
    static final String USER = env("AB_TEST_USER", "arcadia");
    static final String PASSWORD = env("AB_TEST_PASSWORD", "arcadia_dev");
    static final String PARAMS = "useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";

    private TestDatabase() {
    }

    static Database connect(String prefix) throws SQLException {
        return Database.connect(HOST, PORT, NAME, USER, PASSWORD, PARAMS, prefix, 4);
    }

    static Database connect(String database, String prefix) throws SQLException {
        return Database.connect(HOST, PORT, database, USER, PASSWORD, PARAMS, prefix, 4);
    }

    /** @return true si la base de test repond (sinon les tests d'integration sont ignores). */
    static boolean available() {
        try (Database db = connect("probe_")) {
            db.sync(c -> {
                try (Statement s = c.createStatement()) {
                    s.execute("SELECT 1");
                }
                return null;
            });
            dropTables(db, "probe_");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void dropTables(Database db, String prefix) throws SQLException {
        db.sync((Connection c) -> {
            try (Statement s = c.createStatement()) {
                for (String table : new String[]{"sanctions", "codes", "links", "players", "reports", "chat", "servers",
                        "channel_members", "channels", "mail", "shares",
                        "poll_votes", "polls", "chat_rules", "announcements", "chat_filters", "ignores"}) {
                    s.execute("DROP TABLE IF EXISTS " + prefix + table);
                }
            }
            return null;
        });
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
