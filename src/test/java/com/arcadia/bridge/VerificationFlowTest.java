package com.arcadia.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.arcadia.bridge.data.Models.RedeemResult;
import com.arcadia.bridge.data.Models.RedeemStatus;
import com.arcadia.bridge.data.Repository;
import com.arcadia.bridge.db.Database;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verification des comptes : usage unique du code, expiration, liaisons deja prises, concurrence. */
class VerificationFlowTest {

    private static final String PREFIX = "t_verif_";
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
            TestDatabase.dropTables(db, PREFIX);
            db.close();
        }
    }

    @BeforeEach
    void clean() throws Exception {
        db.sync(c -> {
            try (var s = c.createStatement()) {
                s.execute("DELETE FROM " + PREFIX + "codes");
                s.execute("DELETE FROM " + PREFIX + "links");
            }
            return null;
        });
    }

    private static String hash(String code) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(code.getBytes(StandardCharsets.UTF_8)));
    }

    private static long future() {
        return System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10);
    }

    @Test
    @DisplayName("Un code valide lie le compte, et ne peut pas resservir")
    void codeIsSingleUse() throws Exception {
        UUID player = UUID.randomUUID();
        repo.saveCode(player, "Joueur1", hash("ABC123"), future()).join();

        RedeemResult first = repo.redeemCode(hash("ABC123"), 1111L, System.currentTimeMillis()).join();
        assertEquals(RedeemStatus.SUCCESS, first.status());
        assertEquals(player, first.link().uuid());
        assertEquals(1111L, first.link().discordId());

        // Le meme code rejoue par quelqu'un d'autre (ex : code vu sur une capture d'ecran) doit echouer.
        RedeemResult replay = repo.redeemCode(hash("ABC123"), 2222L, System.currentTimeMillis()).join();
        assertEquals(RedeemStatus.INVALID_CODE, replay.status());
        assertTrue(repo.findLinkByDiscord(2222L).join().isEmpty());
        assertEquals(1111L, repo.loadAllLinks().join().get(player).discordId());
    }

    @Test
    @DisplayName("Un code expire est refuse")
    void expiredCodeIsRejected() throws Exception {
        UUID player = UUID.randomUUID();
        repo.saveCode(player, "Joueur2", hash("EXPIRE"), System.currentTimeMillis() - 1000).join();

        RedeemResult result = repo.redeemCode(hash("EXPIRE"), 3333L, System.currentTimeMillis()).join();
        assertEquals(RedeemStatus.INVALID_CODE, result.status());
        assertTrue(repo.loadAllLinks().join().isEmpty());
    }

    @Test
    @DisplayName("Un compte Minecraft deja lie ne peut pas etre re-lie a un autre Discord")
    void accountCannotBeRelinked() throws Exception {
        UUID player = UUID.randomUUID();
        repo.saveCode(player, "Joueur3", hash("FIRST1"), future()).join();
        assertEquals(RedeemStatus.SUCCESS, repo.redeemCode(hash("FIRST1"), 4444L, System.currentTimeMillis()).join().status());

        // Un nouveau code est emis (ex : le joueur revient), mais un autre Discord tente de l'utiliser.
        repo.saveCode(player, "Joueur3", hash("SECOND"), future()).join();
        RedeemResult stolen = repo.redeemCode(hash("SECOND"), 5555L, System.currentTimeMillis()).join();
        assertEquals(RedeemStatus.ACCOUNT_ALREADY_LINKED, stolen.status());
        assertEquals(4444L, repo.loadAllLinks().join().get(player).discordId());
    }

    @Test
    @DisplayName("Un compte Discord deja lie ne peut pas prendre un second compte Minecraft")
    void discordCannotLinkTwice() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        repo.saveCode(first, "Joueur4", hash("CODE01"), future()).join();
        repo.saveCode(second, "Joueur5", hash("CODE02"), future()).join();
        assertEquals(RedeemStatus.SUCCESS, repo.redeemCode(hash("CODE01"), 6666L, System.currentTimeMillis()).join().status());

        RedeemResult second_ = repo.redeemCode(hash("CODE02"), 6666L, System.currentTimeMillis()).join();
        assertEquals(RedeemStatus.DISCORD_ALREADY_LINKED, second_.status());
        assertFalse(repo.loadAllLinks().join().containsKey(second));
    }

    @Test
    @DisplayName("Deux Discord qui utilisent le meme code en meme temps : un seul gagne")
    void concurrentRedeemHasSingleWinner() throws Exception {
        UUID player = UUID.randomUUID();
        repo.saveCode(player, "Joueur6", hash("RACE01"), future()).join();

        long now = System.currentTimeMillis();
        List<CompletableFuture<RedeemResult>> attempts = List.of(
                repo.redeemCode(hash("RACE01"), 7001L, now),
                repo.redeemCode(hash("RACE01"), 7002L, now),
                repo.redeemCode(hash("RACE01"), 7003L, now),
                repo.redeemCode(hash("RACE01"), 7004L, now));
        AtomicInteger successes = new AtomicInteger();
        for (CompletableFuture<RedeemResult> attempt : attempts) {
            if (attempt.join().status() == RedeemStatus.SUCCESS) {
                successes.incrementAndGet();
            }
        }
        assertEquals(1, successes.get(), "un seul compte Discord doit obtenir la liaison");
        assertEquals(1, repo.loadAllLinks().join().size());
    }

    @Test
    @DisplayName("La suppression d'une liaison la retire vraiment")
    void unlinkRemovesLink() throws Exception {
        UUID player = UUID.randomUUID();
        repo.saveCode(player, "Joueur7", hash("UNLNK1"), future()).join();
        repo.redeemCode(hash("UNLNK1"), 8888L, System.currentTimeMillis()).join();

        assertTrue(repo.deleteLink(player).join().isPresent());
        assertTrue(repo.loadAllLinks().join().isEmpty());
        assertTrue(repo.deleteLink(player).join().isEmpty());
    }

    @Test
    @DisplayName("Les codes expires sont purges")
    void expiredCodesArePurged() throws Exception {
        UUID player = UUID.randomUUID();
        repo.saveCode(player, "Joueur8", hash("OLD001"), System.currentTimeMillis() - 5000).join();
        repo.purgeExpiredCodes(System.currentTimeMillis()).join();

        repo.saveCode(player, "Joueur8", hash("NEW001"), future()).join();
        assertEquals(RedeemStatus.SUCCESS, repo.redeemCode(hash("NEW001"), 9999L, System.currentTimeMillis()).join().status());
    }
}
