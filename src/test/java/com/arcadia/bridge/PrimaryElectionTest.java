package com.arcadia.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import com.arcadia.bridge.db.Database;
import com.arcadia.bridge.db.PrimaryElection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Election du serveur primaire : deux serveurs simules partagent la meme base et se disputent le
 * verrou, y compris quand le primaire plante sans prevenir.
 */
class PrimaryElectionTest {

    private static Database serveurA;
    private static Database serveurB;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(TestDatabase.available(), "Base de test indisponible : test ignore");
        serveurA = TestDatabase.connect("t_elec_");
        serveurB = TestDatabase.connect("t_elec_");
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (serveurA != null) {
            TestDatabase.dropTables(serveurA, "t_elec_");
            serveurA.close();
        }
        if (serveurB != null) {
            serveurB.close();
        }
    }

    /** Nom de verrou propre a chaque test, pour qu'ils ne se genent pas. */
    private static String lockName() {
        return "arcadiabridge:test:" + UUID.randomUUID();
    }

    @Test
    @DisplayName("Un seul serveur devient primaire")
    void onlyOnePrimary() {
        String lock = lockName();
        try (PrimaryElection a = new PrimaryElection(serveurA, lock);
             PrimaryElection b = new PrimaryElection(serveurB, lock)) {
            assertTrue(a.tick(), "le premier a se presenter devient primaire");
            assertFalse(b.tick(), "le second reste secondaire");
            assertTrue(a.tick(), "le primaire le reste aux tours suivants");
            assertFalse(b.tick());
        }
    }

    @Test
    @DisplayName("A l'arret propre du primaire, un autre prend le relais")
    void takeoverAfterCleanShutdown() {
        String lock = lockName();
        try (PrimaryElection b = new PrimaryElection(serveurB, lock)) {
            PrimaryElection a = new PrimaryElection(serveurA, lock);
            assertTrue(a.tick());
            assertFalse(b.tick());

            a.close();
            assertTrue(b.tick(), "le verrou rendu doit etre repris au tour suivant");
        }
    }

    @Test
    @DisplayName("Si le primaire plante, MariaDB libere le verrou et un autre le reprend")
    void takeoverAfterCrash() throws Exception {
        String lock = lockName();
        try (PrimaryElection a = new PrimaryElection(serveurA, lock);
             PrimaryElection b = new PrimaryElection(serveurB, lock)) {
            assertTrue(a.tick());
            assertFalse(b.tick());

            // Plantage simule : on coupe brutalement la session qui detient le verrou.
            killHolder(lock);

            assertTrue(b.tick(), "le verrou doit etre libere a la coupure et repris");
            assertFalse(a.tick(), "l'ancien primaire doit constater qu'il a perdu la main");
            assertFalse(a.isPrimary());
        }
    }

    @Test
    @DisplayName("Deux installations distinctes ont chacune leur primaire")
    void distinctLocksAreIndependent() {
        try (PrimaryElection a = new PrimaryElection(serveurA, lockName());
             PrimaryElection b = new PrimaryElection(serveurB, lockName())) {
            assertTrue(a.tick());
            assertTrue(b.tick());
        }
    }

    private static void killHolder(String lock) throws Exception {
        try (Connection admin = serveurB.rawConnection()) {
            long holder;
            try (PreparedStatement ps = admin.prepareStatement("SELECT IS_USED_LOCK(?)")) {
                ps.setString(1, lock);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    holder = rs.getLong(1);
                }
            }
            try (Statement s = admin.createStatement()) {
                s.execute("KILL CONNECTION " + holder);
            }
        }
    }
}
