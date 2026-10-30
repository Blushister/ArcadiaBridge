package com.arcadia.bridge.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.arcadia.bridge.data.Models.Announcement;
import com.arcadia.bridge.data.Models.ChatFilter;
import com.arcadia.bridge.data.Models.ChatPin;
import com.arcadia.bridge.data.Models.ChatRule;
import com.arcadia.bridge.data.Models.Link;
import com.arcadia.bridge.data.Models.ChannelInfo;
import com.arcadia.bridge.data.Models.ChatLine;
import com.arcadia.bridge.data.Models.JoinResult;
import com.arcadia.bridge.data.Models.Mail;
import com.arcadia.bridge.data.Models.Poll;
import com.arcadia.bridge.data.Models.VoteResult;
import com.arcadia.bridge.data.Models.Share;
import com.arcadia.bridge.data.Models.PlayerRef;
import com.arcadia.bridge.data.Models.Report;
import com.arcadia.bridge.data.Models.RedeemResult;
import com.arcadia.bridge.data.Models.RedeemStatus;
import com.arcadia.bridge.data.Models.Sanction;
import com.arcadia.bridge.data.Models.ServerStatus;
import com.arcadia.bridge.data.Models.SanctionType;
import com.arcadia.bridge.db.Database;

/** Toutes les requetes SQL du mod. Chaque methode est asynchrone. */
public final class Repository {

    private final Database db;
    private final String players;
    private final String links;
    private final String codes;
    private final String sanctions;
    private final String servers;
    private final String reports;
    private final String chat;
    private final String channels;
    private final String channelMembers;
    private final String mail;
    private final String shares;
    private final String chatRules;
    private final String announcements;
    private final String chatFilters;
    private final String ignores;
    private final String chatPins;
    private final String polls;
    private final String pollVotes;

    public Repository(Database db) {
        this.db = db;
        this.players = db.t("players");
        this.links = db.t("links");
        this.codes = db.t("codes");
        this.sanctions = db.t("sanctions");
        this.servers = db.t("servers");
        this.reports = db.t("reports");
        this.chat = db.t("chat");
        this.channels = db.t("channels");
        this.channelMembers = db.t("channel_members");
        this.mail = db.t("mail");
        this.shares = db.t("shares");
        this.chatRules = db.t("chat_rules");
        this.announcements = db.t("announcements");
        this.chatFilters = db.t("chat_filters");
        this.ignores = db.t("ignores");
        this.chatPins = db.t("chat_pins");
        this.polls = db.t("polls");
        this.pollVotes = db.t("poll_votes");
    }

    // ---------------------------------------------------------------- etat des serveurs

    /** Signale que ce serveur est vivant, avec les joueurs connectes. */
    public CompletableFuture<Void> heartbeat(String serverId, String displayName, List<String> names, int maxPlayers) {
        return heartbeat(serverId, displayName, names, maxPlayers, null);
    }

    /** {@code address} : adresse publique du serveur, pour que les joueurs puissent le rejoindre (ou null). */
    public CompletableFuture<Void> heartbeat(String serverId, String displayName, List<String> names, int maxPlayers,
                                             String address) {
        String joined = String.join(",", names);
        if (joined.length() > 4000) {
            joined = joined.substring(0, 4000);
        }
        String playerNames = joined;
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + servers
                    + " (server_id, display_name, players, max_players, player_names, updated_at, address)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE display_name = VALUES(display_name),"
                    + " players = VALUES(players), max_players = VALUES(max_players),"
                    + " player_names = VALUES(player_names), updated_at = VALUES(updated_at),"
                    + " address = VALUES(address)")) {
                ps.setString(1, serverId);
                ps.setString(2, displayName);
                ps.setInt(3, names.size());
                ps.setInt(4, maxPlayers);
                ps.setString(5, playerNames);
                ps.setLong(6, System.currentTimeMillis());
                ps.setString(7, address == null || address.isBlank() ? null : address);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<List<ServerStatus>> loadServers() {
        return db.async(c -> {
            List<ServerStatus> list = new ArrayList<>();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT server_id, display_name, players, max_players, player_names,"
                         + " updated_at, address FROM " + servers + " ORDER BY server_id")) {
                while (rs.next()) {
                    String names = rs.getString(5);
                    List<String> players = names == null || names.isBlank() ? List.of()
                            : List.of(names.split(","));
                    list.add(new ServerStatus(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4),
                            players, rs.getLong(6), rs.getString(7)));
                }
            }
            return list;
        });
    }

    // ---------------------------------------------------------------- signalements

    private static final String REPORT_COLUMNS = "id, reporter_uuid, reporter_name, target_uuid, target_name,"
            + " reason, server_id, created_at, count, status, message_id";

    /**
     * Enregistre un signalement. Si le meme joueur est deja signale et non traite, on incremente le
     * compteur au lieu de creer une entree : le salon staff ne se noie pas sous les doublons.
     */
    public CompletableFuture<Report> addReport(Report draft) {
        return db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + REPORT_COLUMNS + " FROM " + reports
                    + " WHERE target_uuid = ? AND status = 'OPEN' ORDER BY id DESC LIMIT 1 FOR UPDATE")) {
                ps.setString(1, draft.targetUuid().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Report existing = readReport(rs);
                        try (PreparedStatement up = c.prepareStatement("UPDATE " + reports
                                + " SET count = count + 1, reason = ? WHERE id = ?")) {
                            up.setString(1, draft.reason());
                            up.setLong(2, existing.id());
                            up.executeUpdate();
                        }
                        return new Report(existing.id(), draft.reporterUuid(), draft.reporterName(),
                                existing.targetUuid(), existing.targetName(), draft.reason(), draft.serverId(),
                                existing.createdAt(), existing.count() + 1, existing.status(), existing.messageId());
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + reports
                    + " (reporter_uuid, reporter_name, target_uuid, target_name, reason, server_id, created_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, draft.reporterUuid().toString());
                ps.setString(2, draft.reporterName());
                ps.setString(3, draft.targetUuid().toString());
                ps.setString(4, draft.targetName());
                ps.setString(5, draft.reason());
                ps.setString(6, draft.serverId());
                ps.setLong(7, draft.createdAt());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    long id = keys.next() ? keys.getLong(1) : 0;
                    return new Report(id, draft.reporterUuid(), draft.reporterName(), draft.targetUuid(),
                            draft.targetName(), draft.reason(), draft.serverId(), draft.createdAt(), 1, "OPEN", null);
                }
            }
        });
    }

    public CompletableFuture<Void> setReportMessage(long reportId, long messageId) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + reports + " SET message_id = ? WHERE id = ?")) {
                ps.setLong(1, messageId);
                ps.setLong(2, reportId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Optional<Report>> findReport(long reportId) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + REPORT_COLUMNS + " FROM " + reports
                    + " WHERE id = ?")) {
                ps.setLong(1, reportId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(readReport(rs)) : Optional.empty();
                }
            }
        });
    }

    /** Signalements en attente, les plus recents d'abord (panel staff). */
    public CompletableFuture<List<Report>> openReports(int limit) {
        return db.async(c -> {
            List<Report> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + REPORT_COLUMNS + " FROM " + reports
                    + " WHERE status = 'OPEN' ORDER BY created_at DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(readReport(rs));
                    }
                }
            }
            return list;
        });
    }

    /** Marque le signalement comme traite ; false s'il l'etait deja (deux moderateurs en meme temps). */
    public CompletableFuture<Boolean> closeReport(long reportId, String status, String handledBy) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + reports
                    + " SET status = ?, handled_by = ?, handled_at = ? WHERE id = ? AND status = 'OPEN'")) {
                ps.setString(1, status);
                ps.setString(2, handledBy);
                ps.setLong(3, System.currentTimeMillis());
                ps.setLong(4, reportId);
                return ps.executeUpdate() == 1;
            }
        });
    }

    private static Report readReport(ResultSet rs) throws SQLException {
        long messageId = rs.getLong(11);
        return new Report(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3),
                UUID.fromString(rs.getString(4)), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getLong(8), rs.getInt(9), rs.getString(10), rs.wasNull() ? null : messageId);
    }

    // ---------------------------------------------------------------- chat inter-serveurs

    public CompletableFuture<Void> publishChat(String serverId, String playerName, String prefix, String message) {
        return publishChat(serverId, playerName, prefix, message, null);
    }

    /** {@code formatted} : la ligne mise en forme sur le serveur d'origine (JSON de composant), ou null. */
    public CompletableFuture<Void> publishChat(String serverId, String playerName, String prefix, String message,
                                               String formatted) {
        return publishChat(serverId, playerName, prefix, message, formatted, "global");
    }

    /**
     * {@code channel} : "global" (chat inter-serveurs), "staff", "alert" (alertes pour le staff) ou "#nom" pour
     * un canal thematique.
     */
    public CompletableFuture<Void> publishChat(String serverId, String playerName, String prefix, String message,
                                               String formatted, String channel) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + chat
                    + " (server_id, player_name, prefix, message, formatted, created_at, channel)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, serverId);
                ps.setString(2, playerName);
                ps.setString(3, prefix);
                ps.setString(4, message);
                ps.setString(5, formatted);
                ps.setLong(6, System.currentTimeMillis());
                ps.setString(7, channel);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Messages des autres serveurs posterieurs a un identifiant donne. */
    /** Lignes du relais lues par passage. */
    public static final int CHAT_FETCH_LIMIT = 50;

    public CompletableFuture<List<ChatLine>> fetchChat(long afterId, String excludeServerId) {
        return db.async(c -> {
            List<ChatLine> lines = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, server_id, player_name, prefix, message, formatted, channel FROM "
                    + chat + " WHERE id > ? AND server_id <> ? ORDER BY id LIMIT " + CHAT_FETCH_LIMIT)) {
                ps.setLong(1, afterId);
                ps.setString(2, excludeServerId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        lines.add(new ChatLine(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getString(6), rs.getString(7)));
                    }
                }
            }
            return lines;
        });
    }

    /** Choix du joueur : participe-t-il au chat inter-serveurs ? Vide = reglage par defaut du serveur. */
    public CompletableFuture<Optional<Boolean>> getChatGlobal(UUID uuid) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT chat_global FROM " + players + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        boolean value = rs.getBoolean(1);
                        return rs.wasNull() ? Optional.<Boolean>empty() : Optional.of(value);
                    }
                }
            }
            return Optional.<Boolean>empty();
        });
    }

    public CompletableFuture<Void> setChatGlobal(UUID uuid, boolean global) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + players
                    + " SET chat_global = ? WHERE uuid = ?")) {
                ps.setBoolean(1, global);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Dernier identifiant connu : au demarrage, on ne rejoue pas les anciens messages. */
    public CompletableFuture<Long> lastChatId() {
        return db.async(c -> {
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(id), 0) FROM " + chat)) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    /**
     * Efface le relais. Les canaux thematiques gardent leur historique plus longtemps ({@code channelsBefore}),
     * pour le montrer a ceux qui les rejoignent ; tout le reste (chat global, prives, staff...) part a
     * {@code before}.
     */
    public CompletableFuture<Void> purgeChat(long before, long channelsBefore) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + chat
                    + " WHERE created_at < ? AND (channel NOT LIKE '#%' OR created_at < ?)")) {
                ps.setLong(1, before);
                ps.setLong(2, channelsBefore);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> purgeChat(long before) {
        return purgeChat(before, before);
    }

    /** Derniers messages d'un canal thematique, du plus ancien au plus recent. */
    public CompletableFuture<List<ChatLine>> channelHistory(String channel, int limit) {
        return db.async(c -> {
            List<ChatLine> lines = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, server_id, player_name, prefix, message, formatted,"
                    + " channel FROM " + chat + " WHERE channel = ? ORDER BY id DESC LIMIT ?")) {
                ps.setString(1, channel);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        lines.add(0, new ChatLine(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getString(6), rs.getString(7)));
                    }
                }
            }
            return lines;
        });
    }

    // ---------------------------------------------------------------- empreintes d'adresse

    public CompletableFuture<Void> saveIpHash(UUID uuid, String ipHash) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + players
                    + " SET ip_hash = ?, ip_seen_at = ? WHERE uuid = ?")) {
                ps.setString(1, ipHash);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Autres comptes vus depuis la meme adresse et actuellement bannis. */
    public CompletableFuture<List<PlayerRef>> bannedSharingIp(String ipHash, UUID exclude, long now) {
        return db.async(c -> {
            List<PlayerRef> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT p.uuid, p.name FROM " + players + " p"
                    + " JOIN " + sanctions + " s ON s.uuid = p.uuid AND s.type = 'BAN' AND s.active = 1"
                    + " AND (s.expires_at IS NULL OR s.expires_at > ?)"
                    + " WHERE p.ip_hash = ? AND p.uuid <> ? GROUP BY p.uuid, p.name LIMIT 10")) {
                ps.setLong(1, now);
                ps.setString(2, ipHash);
                ps.setString(3, exclude.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new PlayerRef(UUID.fromString(rs.getString(1)), rs.getString(2)));
                    }
                }
            }
            return list;
        });
    }

    /** Oubli des empreintes anciennes : une adresse est une donnee personnelle. */
    public CompletableFuture<Void> purgeIpHashes(long before) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + players
                    + " SET ip_hash = NULL, ip_seen_at = NULL WHERE ip_seen_at IS NOT NULL AND ip_seen_at < ?")) {
                ps.setLong(1, before);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ---------------------------------------------------------------- import de l'ancien mod

    /** Nom de la table de l'ancien mod (arcadiabridge 0.5.x), sans prefixe. */
    private static final String LEGACY_PLAYERS = "players";

    /**
     * Recopie le registre des joueurs de l'ancien mod dans ab_players : UUID, dernier pseudo, dernier
     * serveur et derniere activite. L'ancienne table n'est que lue, jamais modifiee.
     *
     * <p>Rejouable sans risque : un joueur deja connu n'est mis a jour que si l'ancienne donnee est plus
     * recente. Sans ancienne table (ou si notre prefixe est vide, ce qui ferait de "players" notre propre
     * table), il ne se passe rien.
     *
     * @return nombre de lignes inserees ou mises a jour, ou -1 si aucune ancienne table n'a ete trouvee
     */
    public int importLegacyPlayers() throws SQLException {
        if (players.equals(LEGACY_PLAYERS)) {
            return -1;
        }
        return db.sync(c -> {
            if (!legacyTableIsCompatible(c)) {
                return -1;
            }
            // La colonne last_seen est affectee en dernier : les conditions precedentes lisent donc
            // encore l'ancienne valeur.
            String sql = "INSERT INTO " + players + " (uuid, name, last_server, last_seen)"
                    + " SELECT p.uuid, p.minecraft_name, p.server_id, UNIX_TIMESTAMP(p.updated_at) * 1000"
                    + " FROM " + LEGACY_PLAYERS + " p"
                    + " JOIN (SELECT uuid, MAX(updated_at) AS latest FROM " + LEGACY_PLAYERS + " GROUP BY uuid) last"
                    + "   ON last.uuid = p.uuid AND last.latest = p.updated_at"
                    + " WHERE p.uuid IS NOT NULL AND CHAR_LENGTH(p.uuid) = 36"
                    + "   AND p.minecraft_name IS NOT NULL AND CHAR_LENGTH(p.minecraft_name) BETWEEN 1 AND 16"
                    + "   AND p.minecraft_name NOT LIKE 'Unverified\\_%'"
                    + " ON DUPLICATE KEY UPDATE"
                    + "   name = IF(VALUES(last_seen) > last_seen, VALUES(name), name),"
                    + "   last_server = IF(VALUES(last_seen) > last_seen, VALUES(last_server), last_server),"
                    + "   last_seen = GREATEST(last_seen, VALUES(last_seen))";
            try (Statement s = c.createStatement()) {
                return s.executeUpdate(sql);
            }
        });
    }

    private static boolean legacyTableIsCompatible(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM information_schema.columns"
                + " WHERE table_schema = DATABASE() AND table_name = ?"
                + " AND column_name IN ('uuid', 'minecraft_name', 'server_id', 'updated_at')")) {
            ps.setString(1, LEGACY_PLAYERS);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 4;
            }
        }
    }

    // ---------------------------------------------------------------- joueurs

    public CompletableFuture<Void> upsertPlayer(UUID uuid, String name, String serverId) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + players
                    + " (uuid, name, last_server, last_seen) VALUES (?, ?, ?, ?)"
                    + " ON DUPLICATE KEY UPDATE name = VALUES(name), last_server = VALUES(last_server),"
                    + " last_seen = VALUES(last_seen)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setString(3, serverId);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Optional<PlayerRef>> findPlayerByName(String name) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name FROM " + players
                    + " WHERE name = ? ORDER BY last_seen DESC LIMIT 1")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new PlayerRef(UUID.fromString(rs.getString(1)), rs.getString(2)));
                    }
                }
            }
            return Optional.empty();
        });
    }

    // ---------------------------------------------------------------- liaisons

    public CompletableFuture<Map<UUID, Link>> loadAllLinks() {
        return db.async(c -> {
            Map<UUID, Link> result = new HashMap<>();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT uuid, discord_id, name, linked_at FROM " + links)) {
                while (rs.next()) {
                    Link link = readLink(rs);
                    result.put(link.uuid(), link);
                }
            }
            return result;
        });
    }

    public CompletableFuture<Map<UUID, Link>> findLinks(Collection<UUID> uuids) {
        if (uuids.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        return db.async(c -> {
            String placeholders = String.join(",", java.util.Collections.nCopies(uuids.size(), "?"));
            Map<UUID, Link> result = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, discord_id, name, linked_at FROM " + links
                    + " WHERE uuid IN (" + placeholders + ")")) {
                int i = 1;
                for (UUID uuid : uuids) {
                    ps.setString(i++, uuid.toString());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Link link = readLink(rs);
                        result.put(link.uuid(), link);
                    }
                }
            }
            return result;
        });
    }

    public CompletableFuture<Optional<Link>> findLinkByDiscord(long discordId) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, discord_id, name, linked_at FROM " + links
                    + " WHERE discord_id = ?")) {
                ps.setLong(1, discordId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(readLink(rs)) : Optional.empty();
                }
            }
        });
    }

    /** Supprime la liaison d'un joueur ; renvoie la liaison supprimee s'il y en avait une. */
    public CompletableFuture<Optional<Link>> deleteLink(UUID uuid) {
        return db.transaction(c -> {
            Optional<Link> existing;
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, discord_id, name, linked_at FROM " + links
                    + " WHERE uuid = ? FOR UPDATE")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    existing = rs.next() ? Optional.of(readLink(rs)) : Optional.empty();
                }
            }
            if (existing.isPresent()) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + links + " WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    ps.executeUpdate();
                }
            }
            return existing;
        });
    }

    private static Link readLink(ResultSet rs) throws SQLException {
        return new Link(UUID.fromString(rs.getString(1)), rs.getLong(2), rs.getString(3), rs.getLong(4));
    }

    // ---------------------------------------------------------------- codes de verification

    /** Enregistre (ou remplace) le code en attente d'un joueur. Seul le hash du code est stocke. */
    public CompletableFuture<Void> saveCode(UUID uuid, String name, String codeHash, long expiresAt) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("REPLACE INTO " + codes
                    + " (uuid, name, code_hash, expires_at) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setString(3, codeHash);
                ps.setLong(4, expiresAt);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Utilise un code : verifie qu'il existe et n'a pas expire, cree la liaison et supprime le code,
     * le tout dans une seule transaction (un code ne peut servir qu'une fois).
     */
    public CompletableFuture<RedeemResult> redeemCode(String codeHash, long discordId, long now) {
        return db.transaction(c -> {
            UUID uuid;
            String name;
            long expiresAt;
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, expires_at FROM " + codes
                    + " WHERE code_hash = ? FOR UPDATE")) {
                ps.setString(1, codeHash);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return new RedeemResult(RedeemStatus.INVALID_CODE, null);
                    }
                    uuid = UUID.fromString(rs.getString(1));
                    name = rs.getString(2);
                    expiresAt = rs.getLong(3);
                }
            }
            if (expiresAt <= now) {
                deleteCode(c, uuid);
                return new RedeemResult(RedeemStatus.INVALID_CODE, null);
            }
            if (exists(c, "SELECT 1 FROM " + links + " WHERE uuid = ?", uuid.toString())) {
                deleteCode(c, uuid);
                return new RedeemResult(RedeemStatus.ACCOUNT_ALREADY_LINKED, null);
            }
            if (exists(c, "SELECT 1 FROM " + links + " WHERE discord_id = ?", discordId)) {
                return new RedeemResult(RedeemStatus.DISCORD_ALREADY_LINKED, null);
            }
            Link link = new Link(uuid, discordId, name, now);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + links
                    + " (uuid, discord_id, name, linked_at) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, discordId);
                ps.setString(3, name);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
            deleteCode(c, uuid);
            return new RedeemResult(RedeemStatus.SUCCESS, link);
        });
    }

    public CompletableFuture<Void> purgeExpiredCodes(long now) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + codes + " WHERE expires_at <= ?")) {
                ps.setLong(1, now);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void deleteCode(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + codes + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- sanctions

    private static final String SANCTION_COLUMNS = "id, type, uuid, name, discord_id, reason, actor, origin,"
            + " server_id, created_at, expires_at, active";

    public CompletableFuture<Sanction> insertSanction(Sanction s) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + sanctions
                    + " (type, uuid, name, discord_id, reason, actor, origin, server_id, created_at, expires_at, active)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, s.type().name());
                ps.setString(2, s.uuid().toString());
                ps.setString(3, s.name());
                setNullableLong(ps, 4, s.discordId());
                ps.setString(5, s.reason());
                ps.setString(6, s.actor());
                ps.setString(7, s.origin());
                ps.setString(8, s.serverId());
                ps.setLong(9, s.createdAt());
                setNullableLong(ps, 10, s.expiresAt());
                ps.setBoolean(11, s.active());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? s.withId(keys.getLong(1)) : s;
                }
            }
        });
    }

    /** Bans et mutes actuellement en vigueur (tous serveurs confondus). */
    public CompletableFuture<List<Sanction>> loadActive(long now) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + SANCTION_COLUMNS + " FROM " + sanctions
                    + " WHERE active = 1 AND type IN ('BAN', 'MUTE') AND (expires_at IS NULL OR expires_at > ?)")) {
                ps.setLong(1, now);
                return readSanctions(ps);
            }
        });
    }

    public CompletableFuture<Optional<Sanction>> findActive(UUID uuid, SanctionType type, long now) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + SANCTION_COLUMNS + " FROM " + sanctions
                    + " WHERE uuid = ? AND type = ? AND active = 1 AND (expires_at IS NULL OR expires_at > ?)"
                    + " ORDER BY id DESC LIMIT 1")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, type.name());
                ps.setLong(3, now);
                List<Sanction> list = readSanctions(ps);
                return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
            }
        });
    }

    /** Leve toutes les sanctions actives d'un type pour un joueur ; renvoie celles qui ont ete levees. */
    public CompletableFuture<List<Sanction>> revokeActive(UUID uuid, SanctionType type, String revokedBy, long now) {
        return db.transaction(c -> {
            List<Sanction> revoked;
            try (PreparedStatement ps = c.prepareStatement("SELECT " + SANCTION_COLUMNS + " FROM " + sanctions
                    + " WHERE uuid = ? AND type = ? AND active = 1 FOR UPDATE")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, type.name());
                revoked = readSanctions(ps);
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + sanctions
                    + " SET active = 0, revoked_at = ?, revoked_by = ? WHERE uuid = ? AND type = ? AND active = 1")) {
                ps.setLong(1, now);
                ps.setString(2, revokedBy);
                ps.setString(3, uuid.toString());
                ps.setString(4, type.name());
                ps.executeUpdate();
            }
            // Seules celles encore en vigueur comptent comme "levees" pour l'appelant.
            return revoked.stream().filter(s -> s.isInEffect(now)).toList();
        });
    }

    /** Desactive les sanctions arrivees a expiration ; renvoie celles que CET appel a desactivees. */
    public CompletableFuture<List<Sanction>> expireDue(long now) {
        return db.async(c -> {
            List<Sanction> due;
            try (PreparedStatement ps = c.prepareStatement("SELECT " + SANCTION_COLUMNS + " FROM " + sanctions
                    + " WHERE active = 1 AND expires_at IS NOT NULL AND expires_at <= ? LIMIT 200")) {
                ps.setLong(1, now);
                due = readSanctions(ps);
            }
            List<Sanction> expired = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + sanctions
                    + " SET active = 0, revoked_at = ?, revoked_by = 'expiration' WHERE id = ? AND active = 1")) {
                for (Sanction s : due) {
                    ps.setLong(1, now);
                    ps.setLong(2, s.id());
                    if (ps.executeUpdate() == 1) {
                        expired.add(s);
                    }
                }
            }
            return expired;
        });
    }

    public CompletableFuture<Integer> countWarns(UUID uuid) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + sanctions
                    + " WHERE uuid = ? AND type = 'WARN' AND active = 1")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    public CompletableFuture<List<Sanction>> history(UUID uuid, int limit) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + SANCTION_COLUMNS + " FROM " + sanctions
                    + " WHERE uuid = ? ORDER BY id DESC LIMIT ?")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, limit);
                return readSanctions(ps);
            }
        });
    }

    /** Mutes actifs lies a un compte Discord dont le timeout Discord doit etre (re)pose avant 'horizon'. */
    public CompletableFuture<List<Sanction>> mutesNeedingTimeout(long now, long horizon) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + SANCTION_COLUMNS + " FROM " + sanctions
                    + " WHERE type = 'MUTE' AND active = 1 AND discord_id IS NOT NULL"
                    + " AND (expires_at IS NULL OR expires_at > ?)"
                    + " AND (discord_timeout_until IS NULL OR discord_timeout_until < ?)"
                    + " AND (expires_at IS NULL OR discord_timeout_until IS NULL OR discord_timeout_until < expires_at)")) {
                ps.setLong(1, now);
                ps.setLong(2, horizon);
                return readSanctions(ps);
            }
        });
    }

    public CompletableFuture<Void> setDiscordTimeoutUntil(long sanctionId, long until) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + sanctions
                    + " SET discord_timeout_until = ? WHERE id = ?")) {
                ps.setLong(1, until);
                ps.setLong(2, sanctionId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static List<Sanction> readSanctions(PreparedStatement ps) throws SQLException {
        List<Sanction> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long discord = rs.getLong(5);
                Long discordId = rs.wasNull() ? null : discord;
                long expires = rs.getLong(11);
                Long expiresAt = rs.wasNull() ? null : expires;
                list.add(new Sanction(rs.getLong(1), SanctionType.valueOf(rs.getString(2)),
                        UUID.fromString(rs.getString(3)), rs.getString(4), discordId, rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getString(9), rs.getLong(10), expiresAt,
                        rs.getBoolean(12)));
            }
        }
        return list;
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static boolean exists(Connection c, String sql, Object param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ---------------------------------------------------------------- sondages

    public CompletableFuture<Long> createPoll(String question, List<String> options, String createdBy, String serverId,
                                              long endsAt) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + polls
                    + " (question, options, created_by, server_id, created_at, ends_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, question);
                ps.setString(2, String.join("\n", options));
                ps.setString(3, createdBy);
                ps.setString(4, serverId);
                ps.setLong(5, System.currentTimeMillis());
                ps.setLong(6, endsAt);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }

    public CompletableFuture<Poll> loadPoll(long id) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + POLL_COLUMNS + " FROM " + polls + " WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? readPoll(rs) : null;
                }
            }
        });
    }

    /** Vote (ou change son vote). {@code choice} commence a 0. */
    public CompletableFuture<VoteResult> vote(long pollId, UUID uuid, int choice) {
        return db.async(c -> {
            Poll poll;
            try (PreparedStatement ps = c.prepareStatement("SELECT " + POLL_COLUMNS + " FROM " + polls + " WHERE id = ?")) {
                ps.setLong(1, pollId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return VoteResult.UNKNOWN;
                    }
                    poll = readPoll(rs);
                }
            }
            if (poll.closed() || poll.endsAt() <= System.currentTimeMillis()) {
                return VoteResult.CLOSED;
            }
            if (choice < 0 || choice >= poll.options().size()) {
                return VoteResult.BAD_CHOICE;
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + pollVotes + " (poll_id, uuid, choice)"
                    + " VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE choice = VALUES(choice)")) {
                ps.setLong(1, pollId);
                ps.setString(2, uuid.toString());
                ps.setInt(3, choice);
                ps.executeUpdate();
            }
            return VoteResult.OK;
        });
    }

    /** Nombre de votes par choix. */
    public CompletableFuture<int[]> pollResults(long pollId, int optionCount) {
        return db.async(c -> {
            int[] counts = new int[optionCount];
            try (PreparedStatement ps = c.prepareStatement("SELECT choice, COUNT(*) FROM " + pollVotes
                    + " WHERE poll_id = ? GROUP BY choice")) {
                ps.setLong(1, pollId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int choice = rs.getInt(1);
                        if (choice >= 0 && choice < optionCount) {
                            counts[choice] = rs.getInt(2);
                        }
                    }
                }
            }
            return counts;
        });
    }

    /** Sondages de ce serveur arrives a leur terme et pas encore clotures. */
    public CompletableFuture<List<Poll>> duePolls(String serverId, long now) {
        return db.async(c -> {
            List<Poll> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + POLL_COLUMNS + " FROM " + polls
                    + " WHERE closed = 0 AND server_id = ? AND ends_at <= ?")) {
                ps.setString(1, serverId);
                ps.setLong(2, now);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(readPoll(rs));
                    }
                }
            }
            return list;
        });
    }

    /** @return true si ce serveur vient de cloturer le sondage (une seule fois, meme en cas de concurrence) */
    public CompletableFuture<Boolean> closePoll(long id) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + polls + " SET closed = 1 WHERE id = ? AND closed = 0")) {
                ps.setLong(1, id);
                return ps.executeUpdate() == 1;
            }
        });
    }

    private static final String POLL_COLUMNS = "id, question, options, created_by, server_id, ends_at, closed";

    private static Poll readPoll(ResultSet rs) throws SQLException {
        return new Poll(rs.getLong(1), rs.getString(2), List.of(rs.getString(3).split("\n")), rs.getString(4),
                rs.getString(5), rs.getLong(6), rs.getBoolean(7));
    }

    // ---------------------------------------------------------------- partages du chat

    public CompletableFuture<Void> saveShare(String id, String owner, String kind, String data) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + shares
                    + " (id, owner, kind, data, created_at) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, owner);
                ps.setString(3, kind);
                ps.setString(4, data);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Partage encore valide, ou null. */
    public CompletableFuture<Share> loadShare(String id) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, owner, kind, data, created_at FROM " + shares
                    + " WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Share(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getLong(5)) : null;
                }
            }
        });
    }

    public CompletableFuture<Void> purgeShares(long before) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + shares + " WHERE created_at < ?")) {
                ps.setLong(1, before);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ---------------------------------------------------------------- moderation du chat (panel staff)

    /** Tout ce que le panel staff regle : regles du chat, annonces automatiques et mots filtres. */
    public record ModerationData(List<ChatRule> rules, List<Announcement> announcements, List<ChatFilter> filters,
                                 List<ChatPin> pins) {
    }

    public CompletableFuture<ModerationData> loadModeration() {
        return db.async(c -> {
            List<ChatRule> rules = new ArrayList<>();
            List<Announcement> list = new ArrayList<>();
            List<ChatFilter> filters = new ArrayList<>();
            try (Statement s = c.createStatement()) {
                try (ResultSet rs = s.executeQuery("SELECT scope, slow_seconds, locked FROM " + chatRules)) {
                    while (rs.next()) {
                        rules.add(new ChatRule(rs.getString(1), rs.getInt(2), rs.getBoolean(3)));
                    }
                }
                try (ResultSet rs = s.executeQuery("SELECT id, message, interval_minutes, servers, discord, enabled FROM "
                        + announcements + " ORDER BY id")) {
                    while (rs.next()) {
                        list.add(new Announcement(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                                rs.getBoolean(5), rs.getBoolean(6)));
                    }
                }
                try (ResultSet rs = s.executeQuery("SELECT word, block FROM " + chatFilters + " ORDER BY word")) {
                    while (rs.next()) {
                        filters.add(new ChatFilter(rs.getString(1), rs.getBoolean(2)));
                    }
                }
            }
            List<ChatPin> pins = new ArrayList<>();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT scope, message FROM " + chatPins + " ORDER BY scope")) {
                while (rs.next()) {
                    pins.add(new ChatPin(rs.getString(1), rs.getString(2)));
                }
            }
            return new ModerationData(rules, list, filters, pins);
        });
    }

    /** Mode lent et verrou d'un serveur (ou de tous, "*") ; une regle sans effet est supprimee. */
    public CompletableFuture<Void> setChatRule(String scope, int slowSeconds, boolean locked, String by) {
        return db.async(c -> {
            if (slowSeconds <= 0 && !locked) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + chatRules + " WHERE scope = ?")) {
                    ps.setString(1, scope);
                    ps.executeUpdate();
                }
                return null;
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + chatRules
                    + " (scope, slow_seconds, locked, updated_by, updated_at) VALUES (?, ?, ?, ?, ?)"
                    + " ON DUPLICATE KEY UPDATE slow_seconds = VALUES(slow_seconds), locked = VALUES(locked),"
                    + " updated_by = VALUES(updated_by), updated_at = VALUES(updated_at)")) {
                ps.setString(1, scope);
                ps.setInt(2, Math.max(0, slowSeconds));
                ps.setBoolean(3, locked);
                ps.setString(4, by);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> addAnnouncement(String message, int intervalMinutes, String servers, boolean discord,
                                                   String by) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + announcements
                    + " (message, interval_minutes, servers, discord, enabled, created_by, created_at)"
                    + " VALUES (?, ?, ?, ?, 1, ?, ?)")) {
                ps.setString(1, message);
                ps.setInt(2, intervalMinutes);
                ps.setString(3, servers);
                ps.setBoolean(4, discord);
                ps.setString(5, by);
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> setAnnouncementEnabled(long id, boolean enabled) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + announcements + " SET enabled = ? WHERE id = ?")) {
                ps.setBoolean(1, enabled);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> deleteAnnouncement(long id) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + announcements + " WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Ajoute un mot filtre, ou change son action s'il existe deja. */
    public CompletableFuture<Void> setChatFilter(String word, boolean block, String by) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + chatFilters
                    + " (word, block, created_by, created_at) VALUES (?, ?, ?, ?)"
                    + " ON DUPLICATE KEY UPDATE block = VALUES(block)")) {
                ps.setString(1, word);
                ps.setBoolean(2, block);
                ps.setString(3, by);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Epingle un message (remplace celui de la meme portee). */
    public CompletableFuture<Void> setChatPin(String scope, String message, String by) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + chatPins
                    + " (scope, message, updated_by, updated_at) VALUES (?, ?, ?, ?)"
                    + " ON DUPLICATE KEY UPDATE message = VALUES(message), updated_by = VALUES(updated_by),"
                    + " updated_at = VALUES(updated_at)")) {
                ps.setString(1, scope);
                ps.setString(2, message);
                ps.setString(3, by);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> deleteChatPin(String scope) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + chatPins + " WHERE scope = ?")) {
                ps.setString(1, scope);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> deleteChatFilter(String word) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + chatFilters + " WHERE word = ?")) {
                ps.setString(1, word);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ---------------------------------------------------------------- joueurs ignores (/ignore)

    public CompletableFuture<List<PlayerRef>> loadIgnores(UUID uuid) {
        return db.async(c -> {
            List<PlayerRef> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT ignored_uuid, ignored_name FROM " + ignores
                    + " WHERE uuid = ? ORDER BY ignored_name")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new PlayerRef(UUID.fromString(rs.getString(1)), rs.getString(2)));
                    }
                }
            }
            return list;
        });
    }

    public CompletableFuture<Void> addIgnore(UUID uuid, PlayerRef ignored) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + ignores
                    + " (uuid, ignored_uuid, ignored_name, created_at) VALUES (?, ?, ?, ?)"
                    + " ON DUPLICATE KEY UPDATE ignored_name = VALUES(ignored_name)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, ignored.uuid().toString());
                ps.setString(3, ignored.name());
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> removeIgnore(UUID uuid, UUID ignored) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + ignores + " WHERE uuid = ? AND ignored_uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, ignored.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ---------------------------------------------------------------- messages hors ligne

    /**
     * Laisse un message a un joueur hors ligne.
     *
     * @return false si sa boite est pleine ({@code maxPending} messages en attente)
     */
    public CompletableFuture<Boolean> addMail(UUID to, String fromName, String fromServer, String message,
                                              int maxPending) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + mail + " WHERE to_uuid = ?")) {
                ps.setString(1, to.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) >= maxPending) {
                        return false;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + mail
                    + " (to_uuid, from_name, from_server, message, created_at) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, to.toString());
                ps.setString(2, fromName);
                ps.setString(3, fromServer);
                ps.setString(4, message);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return true;
        });
    }

    /** Les messages en attente d'un joueur, du plus ancien au plus recent ; ils sont retires de la base. */
    public CompletableFuture<List<Mail>> takeMail(UUID to) {
        return db.transaction(c -> {
            List<Mail> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, from_name, from_server, message, created_at FROM "
                    + mail + " WHERE to_uuid = ? ORDER BY id FOR UPDATE")) {
                ps.setString(1, to.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new Mail(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)));
                    }
                }
            }
            if (!list.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + mail + " WHERE to_uuid = ?")) {
                    ps.setString(1, to.toString());
                    ps.executeUpdate();
                }
            }
            return list;
        });
    }

    public CompletableFuture<Void> purgeMail(long before) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + mail + " WHERE created_at < ?")) {
                ps.setLong(1, before);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ---------------------------------------------------------------- canaux thematiques

    public CompletableFuture<List<String>> listChannels() {
        return listChannelInfos().thenApply(list -> list.stream().map(ChannelInfo::name).toList());
    }

    public CompletableFuture<List<ChannelInfo>> listChannelInfos() {
        return db.async(c -> {
            List<ChannelInfo> list = new ArrayList<>();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT name, is_private FROM " + channels + " ORDER BY name")) {
                while (rs.next()) {
                    list.add(new ChannelInfo(rs.getString(1), rs.getBoolean(2)));
                }
            }
            return list;
        });
    }

    /** @return false si le canal existe deja */
    public CompletableFuture<Boolean> createChannel(String name, String createdBy) {
        return createChannel(name, createdBy, false);
    }

    /** @return false si le canal existe deja */
    public CompletableFuture<Boolean> createChannel(String name, String createdBy, boolean isPrivate) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + channels
                    + " (name, created_by, created_at, is_private) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, name);
                ps.setString(2, createdBy);
                ps.setLong(3, System.currentTimeMillis());
                ps.setBoolean(4, isPrivate);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** @return false si le canal n'existe pas */
    public CompletableFuture<Boolean> setChannelPrivate(String name, boolean isPrivate) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + channels + " SET is_private = ? WHERE name = ?")) {
                ps.setBoolean(1, isPrivate);
                ps.setString(2, name);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + channels + " WHERE name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /** Membres d'un canal (pseudos connus, sinon UUID). */
    public CompletableFuture<List<String>> channelMembers(String name) {
        return db.async(c -> {
            List<String> names = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(p.name, m.uuid) FROM " + channelMembers
                    + " m LEFT JOIN " + players + " p ON p.uuid = m.uuid WHERE m.channel = ? ORDER BY 1")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        names.add(rs.getString(1));
                    }
                }
            }
            return names;
        });
    }

    /** Supprime le canal et ses membres. @return false s'il n'existait pas */
    public CompletableFuture<Boolean> deleteChannel(String name) {
        return db.transaction(c -> {
            try (PreparedStatement members = c.prepareStatement("DELETE FROM " + channelMembers + " WHERE channel = ?");
                 PreparedStatement channel = c.prepareStatement("DELETE FROM " + channels + " WHERE name = ?")) {
                members.setString(1, name);
                members.executeUpdate();
                channel.setString(1, name);
                return channel.executeUpdate() == 1;
            }
        });
    }

    /** Canaux rejoints par un joueur (seulement ceux qui existent encore). */
    public CompletableFuture<List<String>> memberChannels(UUID uuid) {
        return db.async(c -> {
            List<String> names = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT m.channel FROM " + channelMembers + " m JOIN "
                    + channels + " ch ON ch.name = m.channel WHERE m.uuid = ? ORDER BY m.channel")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        names.add(rs.getString(1));
                    }
                }
            }
            return names;
        });
    }

    /** Rejoindre un canal public. @return false si le canal n'existe pas ou s'il est prive */
    public CompletableFuture<Boolean> joinChannel(UUID uuid, String name) {
        return joinChannel(uuid, name, false).thenApply(result -> result == JoinResult.JOINED);
    }

    /**
     * Ajoute un joueur a un canal. {@code force} : ajout par le staff, possible aussi pour un canal prive.
     */
    public CompletableFuture<JoinResult> joinChannel(UUID uuid, String name, boolean force) {
        return db.async(c -> {
            Boolean isPrivate = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT is_private FROM " + channels + " WHERE name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        isPrivate = rs.getBoolean(1);
                    }
                }
            }
            if (isPrivate == null) {
                return JoinResult.UNKNOWN;
            }
            if (isPrivate && !force) {
                return JoinResult.PRIVATE;
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + channelMembers
                    + " (uuid, channel, joined_at) VALUES (?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return JoinResult.JOINED;
        });
    }

    public CompletableFuture<Void> leaveChannel(UUID uuid, String name) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + channelMembers
                    + " WHERE uuid = ? AND channel = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.executeUpdate();
            }
            return null;
        });
    }
}
