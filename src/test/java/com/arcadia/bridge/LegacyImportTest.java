package com.arcadia.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;

import com.arcadia.bridge.data.Models.PlayerRef;
import com.arcadia.bridge.data.Repository;
import com.arcadia.bridge.db.Database;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reprise du registre des joueurs de l'ancien mod. La table 'players' reproduit le schema de
 * arcadiabridge 0.5.13 : une ligne par couple (serveur, joueur).
 */
class LegacyImportTest {

    private static final String PREFIX = "t_leg_";
    private static final String ALICE = "11111111-1111-1111-1111-111111111111";
    private static final String BOB = "22222222-2222-2222-2222-222222222222";
    private static Database db;
    private static Repository repo;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(TestDatabase.available(), "Base de test indisponible : test ignore");
        db = TestDatabase.connect(PREFIX);
        repo = new Repository(db);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (db != null) {
            db.sync(c -> {
                try (Statement s = c.createStatement()) {
                    s.execute("DROP TABLE IF EXISTS players");
                }
                return null;
            });
            TestDatabase.dropTables(db, PREFIX);
            db.close();
        }
    }

    @BeforeEach
    void legacyData() throws Exception {
        db.sync(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("DROP TABLE IF EXISTS players");
                s.execute("CREATE TABLE players (id INT AUTO_INCREMENT PRIMARY KEY, server_id VARCHAR(50) NOT NULL,"
                        + " uuid CHAR(36) NOT NULL, minecraft_name VARCHAR(16) NOT NULL, discord_id BIGINT,"
                        + " verified BOOLEAN DEFAULT FALSE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + " updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                // Alice est passee sur deux serveurs et a change de pseudo : la derniere activite fait foi.
                s.execute("INSERT INTO players (server_id, uuid, minecraft_name, updated_at) VALUES"
                        + " ('server1', '" + ALICE + "', 'AliceAvant', '2026-07-01 10:00:00'),"
                        + " ('server2', '" + ALICE + "', 'AliceApres', '2026-09-01 10:00:00'),"
                        + " ('server1', '" + BOB + "', 'Bob', '2026-08-01 10:00:00'),"
                        // Lignes invalides laissees par l'ancien mod : ignorees.
                        + " ('server1', 'Unverified_123', 'Unverified_123', '2026-08-01 10:00:00')");
                s.execute("DELETE FROM " + PREFIX + "players");
            }
            return null;
        });
    }

    @Test
    @DisplayName("Chaque joueur est importe une fois, avec son pseudo et son serveur les plus recents")
    void importsLatestIdentity() throws Exception {
        repo.importLegacyPlayers();

        assertEquals(2, count(PREFIX + "players"));
        Optional<PlayerRef> alice = repo.findPlayerByName("AliceApres").join();
        assertTrue(alice.isPresent(), "le pseudo le plus recent doit etre retenu");
        assertEquals(ALICE, alice.get().uuid().toString());
        assertTrue(repo.findPlayerByName("AliceAvant").join().isEmpty());
        assertEquals("server2", lastServer(ALICE));
    }

    @Test
    @DisplayName("L'import est rejouable et n'ecrase pas une donnee plus recente")
    void importIsIdempotentAndKeepsNewerData() throws Exception {
        repo.importLegacyPlayers();
        // Bob s'est reconnecte avec le nouveau mod, sous un nouveau pseudo.
        repo.upsertPlayer(java.util.UUID.fromString(BOB), "BobNouveau", "test").join();

        repo.importLegacyPlayers();
        repo.importLegacyPlayers();

        assertEquals(2, count(PREFIX + "players"));
        assertTrue(repo.findPlayerByName("BobNouveau").join().isPresent(), "la donnee recente doit etre conservee");
        assertEquals("test", lastServer(BOB));
    }

    @Test
    @DisplayName("L'ancienne table n'est jamais modifiee")
    void legacyTableIsReadOnly() throws Exception {
        long before = count("players");
        repo.importLegacyPlayers();
        assertEquals(before, count("players"));
    }

    private static long count(String table) throws Exception {
        return db.sync(c -> {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    private static String lastServer(String uuid) throws Exception {
        return db.sync(c -> {
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT last_server FROM " + PREFIX + "players WHERE uuid = '" + uuid + "'")) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }
}
