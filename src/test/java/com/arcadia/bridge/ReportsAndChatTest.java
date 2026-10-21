package com.arcadia.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.UUID;

import com.arcadia.bridge.data.Models.ChatLine;
import com.arcadia.bridge.data.Models.PlayerRef;
import com.arcadia.bridge.data.Models.Report;
import com.arcadia.bridge.data.Models.Sanction;
import com.arcadia.bridge.data.Models.SanctionType;
import com.arcadia.bridge.data.Repository;
import com.arcadia.bridge.db.Database;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Signalements, chat inter-serveurs et detection de contournement de ban. */
class ReportsAndChatTest {

    private static final String PREFIX = "t_rep_";
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
                s.execute("DELETE FROM " + PREFIX + "reports");
                s.execute("DELETE FROM " + PREFIX + "chat");
                s.execute("DELETE FROM " + PREFIX + "sanctions");
                s.execute("DELETE FROM " + PREFIX + "players");
            }
            return null;
        });
    }

    private static Report draft(UUID reporter, String reporterName, UUID target, String targetName, String reason) {
        return new Report(0, reporter, reporterName, target, targetName, reason, "test1",
                System.currentTimeMillis(), 1, "OPEN", null);
    }

    @Test
    @DisplayName("Plusieurs signalements du meme joueur sont regroupes")
    void reportsAreGrouped() {
        UUID target = UUID.randomUUID();
        Report first = repo.addReport(draft(UUID.randomUUID(), "Alice", target, "Tricheur", "vole")).join();
        assertEquals(1, first.count());

        Report second = repo.addReport(draft(UUID.randomUUID(), "Bob", target, "Tricheur", "vole vite")).join();
        assertEquals(first.id(), second.id(), "le signalement doit etre le meme");
        assertEquals(2, second.count());
    }

    @Test
    @DisplayName("Un signalement ne peut etre traite qu'une fois")
    void reportIsHandledOnce() {
        Report report = repo.addReport(draft(UUID.randomUUID(), "Alice", UUID.randomUUID(), "Gene", "insultes")).join();

        assertTrue(repo.closeReport(report.id(), "MUTED", "Modo1").join());
        // Deux moderateurs cliquent en meme temps : le second doit etre informe, pas sanctionner deux fois.
        assertFalse(repo.closeReport(report.id(), "BANNED", "Modo2").join());
        assertEquals("MUTED", repo.findReport(report.id()).join().orElseThrow().status());
    }

    @Test
    @DisplayName("Une fois traite, un nouveau signalement ouvre une entree neuve")
    void handledReportDoesNotAbsorbNewOnes() {
        UUID target = UUID.randomUUID();
        Report first = repo.addReport(draft(UUID.randomUUID(), "Alice", target, "Gene", "spam")).join();
        repo.closeReport(first.id(), "IGNORED", "Modo1").join();

        Report second = repo.addReport(draft(UUID.randomUUID(), "Bob", target, "Gene", "recidive")).join();
        assertFalse(first.id() == second.id());
        assertEquals(1, second.count());
    }

    @Test
    @DisplayName("Le chat ne remonte que les messages des autres serveurs")
    void chatExcludesOwnServer() {
        repo.publishChat("test1", "Dev", "[Modo]", "salut depuis test1").join();
        repo.publishChat("test2", "Autre", "", "salut depuis test2").join();

        List<ChatLine> forTest1 = repo.fetchChat(0, "test1").join();
        assertEquals(1, forTest1.size());
        assertEquals("test2", forTest1.get(0).serverId());
        assertEquals("salut depuis test2", forTest1.get(0).message());

        // Une fois lus, les messages ne sont pas rejoues.
        assertTrue(repo.fetchChat(forTest1.get(0).id(), "test1").join().isEmpty());
    }

    @Test
    @DisplayName("Chaque ligne relayee garde son canal (global, staff, canal thematique)")
    void chatKeepsChannel() {
        long before = repo.lastChatId().join();
        repo.publishChat("test3", "Dev", "", "public").join();
        repo.publishChat("test3", "Modo", "", "entre nous", "{\"text\":\"x\"}", "staff").join();
        repo.publishChat("test3", "Dev", "", "je vends", "{\"text\":\"y\"}", "#commerce").join();

        List<ChatLine> lines = repo.fetchChat(before, "test1").join();
        assertEquals(List.of("global", "staff", "#commerce"), lines.stream().map(ChatLine::channel).toList());
    }

    @Test
    @DisplayName("L'historique des canaux survit a la purge du relais, dans l'ordre et limite")
    void channelHistoryIsKept() {
        for (int i = 1; i <= 5; i++) {
            repo.publishChat("test3", "Dev", "", "offre " + i, "{\"text\":\"offre " + i + "\"}", "#vente").join();
        }
        repo.publishChat("test3", "Dev", "", "public", "{\"text\":\"x\"}", "global").join();

        // Purge du relais (tout ce qui est plus vieux que "dans une seconde"), canaux gardes 2 heures.
        long now = System.currentTimeMillis();
        repo.purgeChat(now + 1000, now - 2L * 3600 * 1000).join();

        List<ChatLine> history = repo.channelHistory("#vente", 3).join();
        assertEquals(List.of("offre 3", "offre 4", "offre 5"), history.stream().map(ChatLine::message).toList(),
                "les 3 plus recents, du plus ancien au plus recent");
        assertTrue(repo.fetchChat(0, "test1").join().stream().noneMatch(l -> "global".equals(l.channel())),
                "le chat global, lui, est purge");

        repo.purgeChat(now + 1000, now + 1000).join();
        assertTrue(repo.channelHistory("#vente", 3).join().isEmpty(), "passe le delai, l'historique part aussi");
    }

    @Test
    @DisplayName("Canaux thematiques : creation, adhesion, depart et suppression")
    void channelsLifecycle() {
        UUID joueur = UUID.randomUUID();
        assertTrue(repo.createChannel("commerce", "Modo").join());
        assertFalse(repo.createChannel("commerce", "Modo").join(), "un canal n'est cree qu'une fois");
        assertFalse(repo.joinChannel(joueur, "inexistant").join(), "on ne rejoint pas un canal inconnu");

        assertTrue(repo.joinChannel(joueur, "commerce").join());
        assertTrue(repo.joinChannel(joueur, "commerce").join(), "rejoindre deux fois est sans effet");
        assertEquals(List.of("commerce"), repo.memberChannels(joueur).join());
        assertTrue(repo.listChannels().join().contains("commerce"));

        repo.leaveChannel(joueur, "commerce").join();
        assertTrue(repo.memberChannels(joueur).join().isEmpty());

        repo.joinChannel(joueur, "commerce").join();
        assertTrue(repo.deleteChannel("commerce").join());
        assertTrue(repo.memberChannels(joueur).join().isEmpty(), "les membres partent avec le canal");
        assertFalse(repo.deleteChannel("commerce").join());
    }

    @Test
    @DisplayName("La ligne mise en forme (LPC) voyage avec le message")
    void chatCarriesFormattedLine() {
        long before = repo.lastChatId().join();
        String json = "{\"text\":\"[Admin] \",\"color\":\"red\",\"extra\":[\"Dev: salut\"]}";
        repo.publishChat("test3", "Dev", "[Admin]", "salut", json).join();
        repo.publishChat("test3", "Autre", "", "sans mise en forme").join();

        List<ChatLine> lines = repo.fetchChat(before, "test1").join();
        assertEquals(2, lines.size());
        assertEquals(json, lines.get(0).formatted());
        assertEquals(null, lines.get(1).formatted());
    }

    @Test
    @DisplayName("Le chat relaye est purge apres coup")
    void chatIsPurged() {
        repo.publishChat("test2", "Autre", "", "vieux message").join();
        repo.purgeChat(System.currentTimeMillis() + 1000).join();
        assertTrue(repo.fetchChat(0, "test1").join().isEmpty());
    }

    @Test
    @DisplayName("Un compte banni partageant l'adresse est signale, et seulement lui")
    void banEvasionIsDetected() {
        UUID banned = UUID.randomUUID();
        UUID innocent = UUID.randomUUID();
        UUID newcomer = UUID.randomUUID();
        repo.upsertPlayer(banned, "Banni", "test1").join();
        repo.upsertPlayer(innocent, "Colocataire", "test1").join();
        repo.upsertPlayer(newcomer, "Nouveau", "test1").join();
        repo.saveIpHash(banned, "empreinte-A").join();
        repo.saveIpHash(innocent, "empreinte-A").join();
        repo.saveIpHash(newcomer, "empreinte-A").join();
        repo.insertSanction(new Sanction(0, SanctionType.BAN, banned, "Banni", null, "triche", "Modo",
                "minecraft", "test1", System.currentTimeMillis(), null, true)).join();

        List<PlayerRef> matches = repo.bannedSharingIp("empreinte-A", newcomer, System.currentTimeMillis()).join();
        assertEquals(1, matches.size(), "seul le compte banni doit remonter");
        assertEquals("Banni", matches.get(0).name());

        // Une autre adresse ne declenche rien.
        assertTrue(repo.bannedSharingIp("empreinte-B", newcomer, System.currentTimeMillis()).join().isEmpty());
    }

    @Test
    @DisplayName("Les empreintes d'adresse sont oubliees apres le delai")
    void ipHashesArePurged() {
        UUID player = UUID.randomUUID();
        repo.upsertPlayer(player, "Ancien", "test1").join();
        repo.saveIpHash(player, "empreinte-C").join();
        repo.insertSanction(new Sanction(0, SanctionType.BAN, player, "Ancien", null, "triche", "Modo",
                "minecraft", "test1", System.currentTimeMillis(), null, true)).join();

        repo.purgeIpHashes(System.currentTimeMillis() + 1000).join();
        assertTrue(repo.bannedSharingIp("empreinte-C", UUID.randomUUID(), System.currentTimeMillis()).join().isEmpty());
    }

    @Test
    @DisplayName("Le choix de chat global est conserve par joueur")
    void chatModeIsStored() {
        UUID player = UUID.randomUUID();
        repo.upsertPlayer(player, "Dev", "test1").join();
        assertTrue(repo.getChatGlobal(player).join().isEmpty(), "aucun choix au depart");

        repo.setChatGlobal(player, false).join();
        assertEquals(false, repo.getChatGlobal(player).join().orElseThrow());
        repo.setChatGlobal(player, true).join();
        assertEquals(true, repo.getChatGlobal(player).join().orElseThrow());
    }
}
