package com.arcadia.bridge.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;

import com.arcadia.bridge.db.Database;

/**
 * Messages conserves pour l'ecran facon Discord d'ArcadiaChat : historique, reponses, modifications, suppressions,
 * reactions et position de lecture de chaque joueur.
 *
 * <p>Un salon est designe par une clef : "global", "staff", "#canal", "discord:salon", "server:identifiant" (ce qui a
 * ete ecrit sur ce serveur, chat global compris) ou "@a|b" (conversation privee, pseudos en minuscules et tries).
 * Un message supprime reste en base jusqu'a la purge, pour que le staff garde une trace ; idem pour l'ancien texte
 * d'un message modifie.
 */
public final class MessageStore {

    /** Message tel qu'il est en base. {@code recipient} : le correspondant d'un message prive, sinon vide. */
    public record Row(long id, String channel, String sender, String recipient, String prefix, String serverId,
                      boolean discord, String raw, String formatted, long replyId, String replySender, String replyText,
                      long createdAt, long editedAt, boolean deleted, Map<String, List<String>> reactions, String lang,
                      String translatedFormatted) {
    }

    /** Non-lus d'un salon ; {@code label} : le correspondant d'une conversation privee. */
    public record RoomState(String key, String label, int unread, long firstUnread) {
    }

    /** Une page de messages, du plus ancien au plus recent ; {@code more} : il en reste avant. */
    public record Page(List<Row> rows, boolean more) {
    }

    private static final String SELECT = "SELECT m.id, m.channel, m.sender, m.recipient, m.prefix, m.server_id,"
            + " m.from_discord, m.raw, m.formatted, m.reply_to, r.sender, r.raw, r.deleted_at, m.created_at, m.edited_at,"
            + " m.deleted_at, m.lang, m.translated_formatted FROM %s m LEFT JOIN %s r ON r.id = m.reply_to AND (r.channel = m.channel"
            + " OR (r.channel IN ('global', 'local') AND m.channel IN ('global', 'local')))";
    private static final int REPLY_PREVIEW = 100;
    private static final int UNREAD_CAP = 100;

    private final Database db;
    private final String messages;
    private final String reads;
    private final String reactions;
    private final String edits;
    private final String select;

    public MessageStore(Database db) {
        this.db = db;
        this.messages = db.t("messages");
        this.reads = db.t("message_reads");
        this.reactions = db.t("message_reactions");
        this.edits = db.t("message_edits");
        this.select = String.format(SELECT, messages, messages);
    }

    // ---------------------------------------------------------------- ecriture

    /** Conserve un message ; pour une conversation privee, l'ouvre aussi chez ses deux participants. */
    public CompletableFuture<Row> insert(String channel, String sender, String recipient, String prefix, String serverId,
                                         boolean discord, String raw, String formatted, long replyTo, String lang,
                                         String translated, String translatedFormatted) {
        return db.async(c -> {
            long id;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + messages + " (channel, sender, recipient, prefix,"
                    + " server_id, from_discord, raw, formatted, reply_to, created_at, lang, translated, translated_formatted)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, channel);
                ps.setString(2, sender);
                ps.setString(3, recipient);
                ps.setString(4, prefix);
                ps.setString(5, serverId);
                ps.setBoolean(6, discord);
                ps.setString(7, raw);
                ps.setString(8, formatted);
                if (replyTo > 0) {
                    ps.setLong(9, replyTo);
                } else {
                    ps.setNull(9, Types.BIGINT);
                }
                ps.setLong(10, System.currentTimeMillis());
                ps.setString(11, lang);
                ps.setString(12, translated);
                ps.setString(13, translatedFormatted);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            if (channel.startsWith("@")) {
                openConversation(c, sender, recipient, channel, id, true);
                if (!sender.equalsIgnoreCase(recipient)) {
                    openConversation(c, recipient, sender, channel, id, false);
                }
            }
            return read(c, id);
        });
    }

    private void openConversation(Connection c, String player, String partner, String key, long id, boolean sent)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + reads + " (player, channel_key, label, last_id, updated_at)"
                + " VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE label = VALUES(label), updated_at = VALUES(updated_at),"
                + " last_id = GREATEST(last_id, ?)")) {
            ps.setString(1, player.toLowerCase(java.util.Locale.ROOT));
            ps.setString(2, key);
            ps.setString(3, partner);
            // Le destinataire n'a pas encore lu ce message : sa position s'arrete juste avant.
            ps.setLong(4, sent ? id : id - 1);
            ps.setLong(5, System.currentTimeMillis());
            ps.setLong(6, sent ? id : 0);
            ps.executeUpdate();
        }
    }

    /** Nouveau texte d'un message ; l'ancien reste dans la trace. */
    public CompletableFuture<Void> edit(long id, String by, String raw, String formatted) {
        return db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + edits + " (message_id, old_raw, edited_by, edited_at)"
                    + " SELECT id, raw, ?, ? FROM " + messages + " WHERE id = ?")) {
                ps.setString(1, by);
                ps.setLong(2, System.currentTimeMillis());
                ps.setLong(3, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + messages
                    + " SET raw = ?, formatted = ?, edited_at = ?, lang = NULL, translated = NULL, translated_formatted = NULL"
                    + " WHERE id = ?")) {
                ps.setString(1, raw);
                ps.setString(2, formatted);
                ps.setLong(3, System.currentTimeMillis());
                ps.setLong(4, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Enregistre la traduction d'un message. */
    public CompletableFuture<Void> setTranslation(long id, String lang, String translated, String formatted) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + messages
                    + " SET lang = ?, translated = ?, translated_formatted = ? WHERE id = ?")) {
                ps.setString(1, lang);
                ps.setString(2, translated);
                ps.setString(3, formatted);
                ps.setLong(4, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Masque un message pour les joueurs ; il reste en base, avec l'auteur de la suppression, jusqu'a la purge. */
    public CompletableFuture<Void> delete(long id, String by) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + messages
                    + " SET deleted_at = ?, deleted_by = ? WHERE id = ? AND deleted_at IS NULL")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, by);
                ps.setLong(3, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> setReaction(long id, String emoji, String player, boolean add) {
        return db.async(c -> {
            String sql = add
                    ? "INSERT IGNORE INTO " + reactions + " (message_id, emoji, player) VALUES (?, ?, ?)"
                    : "DELETE FROM " + reactions + " WHERE message_id = ? AND emoji = ? AND player = ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, id);
                ps.setString(2, emoji);
                ps.setString(3, player);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Lu jusqu'a {@code id}. Une conversation fermee ne se rouvre pas ici : il faut un nouveau message. */
    public CompletableFuture<Void> markRead(String player, String key, long id) {
        return db.async(c -> {
            if (key.startsWith("@")) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE " + reads
                        + " SET last_id = GREATEST(last_id, ?) WHERE player = ? AND channel_key = ?")) {
                    ps.setLong(1, id);
                    ps.setString(2, player);
                    ps.setString(3, key);
                    ps.executeUpdate();
                }
                return null;
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + reads
                    + " (last_id, updated_at, player, channel_key, label) VALUES (?, ?, ?, ?, '')"
                    + " ON DUPLICATE KEY UPDATE last_id = GREATEST(last_id, VALUES(last_id))")) {
                ps.setLong(1, id);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, player);
                ps.setString(4, key);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Ferme une conversation chez ce joueur ; un nouveau message la rouvrira. */
    public CompletableFuture<Void> closeConversation(String player, String key) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + reads + " WHERE player = ? AND channel_key = ?")) {
                ps.setString(1, player);
                ps.setString(2, key);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ---------------------------------------------------------------- lecture

    /** Messages recents de ce serveur que le traducteur n'a pas pu traiter (ni prives, ni supprimes). */
    public CompletableFuture<List<Row>> untranslated(String serverId, long since, int limit) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(select + " WHERE m.server_id = ? AND m.created_at > ? AND m.lang IS NULL"
                    + " AND m.deleted_at IS NULL AND m.channel NOT LIKE '@%' ORDER BY m.id LIMIT ?")) {
                ps.setString(1, serverId);
                ps.setLong(2, since);
                ps.setInt(3, limit);
                return collect(c, ps);
            }
        });
    }

    public CompletableFuture<Long> maxId() {
        return db.async(this::maxId);
    }

    private long maxId(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(id), 0) FROM " + messages)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public CompletableFuture<Row> find(long id) {
        return db.async(c -> read(c, id));
    }

    private Row read(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(select + " WHERE m.id = ?")) {
            ps.setLong(1, id);
            List<Row> rows = collect(c, ps);
            return rows.isEmpty() ? null : rows.get(0);
        }
    }

    /** Messages des autres serveurs ecrits apres {@code afterId}. */
    public CompletableFuture<List<Row>> fetchAfter(long afterId, String excludeServerId, int limit) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(select + " WHERE m.id > ? AND m.server_id <> ? ORDER BY m.id LIMIT ?")) {
                ps.setLong(1, afterId);
                ps.setString(2, excludeServerId);
                ps.setInt(3, limit);
                return collect(c, ps);
            }
        });
    }

    private static final java.util.regex.Pattern FROM_FILTER =
            java.util.regex.Pattern.compile("(?i)(^|\\s)(?:de|from):@?([A-Za-z0-9_]{1,16})(?=\\s|$)");

    /** Une page d'un salon, avant {@code beforeId} (0 : les plus recents), eventuellement filtree par un texte. */
    public CompletableFuture<Page> page(String key, long beforeId, int limit, String query) {
        return db.async(c -> {
            // "de:Pseudo" (ou "from:") ne garde que les messages de ce joueur ; le reste est cherche dans le texte.
            String text = query == null ? "" : query.trim();
            String from = null;
            java.util.regex.Matcher filter = FROM_FILTER.matcher(text);
            if (filter.find()) {
                from = filter.group(2);
                text = (text.substring(0, filter.start()) + " " + text.substring(filter.end())).trim();
            }
            boolean search = !text.isEmpty();
            String sql = select + " WHERE " + clause(key) + " AND m.deleted_at IS NULL"
                    + (beforeId > 0 ? " AND m.id < ?" : "")
                    + (from != null ? " AND m.sender = ?" : "")
                    + (search ? " AND (m.raw LIKE ? ESCAPE '|' OR m.translated LIKE ? ESCAPE '|')" : "")
                    + " ORDER BY m.id DESC LIMIT ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = 1;
                ps.setString(i++, clauseValue(key));
                if (beforeId > 0) {
                    ps.setLong(i++, beforeId);
                }
                if (from != null) {
                    ps.setString(i++, from);
                }
                if (search) {
                    String pattern = "%" + text.replace("|", "||").replace("%", "|%").replace("_", "|_") + "%";
                    ps.setString(i++, pattern);
                    ps.setString(i++, pattern);
                }
                ps.setInt(i, limit + 1);
                List<Row> rows = new ArrayList<>(collect(c, ps));
                boolean more = rows.size() > limit;
                if (more) {
                    rows.remove(rows.size() - 1);
                }
                java.util.Collections.reverse(rows);
                return new Page(rows, more);
            }
        });
    }

    /**
     * Non-lus de ces salons et des conversations du joueur. Un salon jamais ouvert commence sans non-lus : a sa
     * premiere connexion, un joueur ne voit pas tout l'historique marque comme nouveau.
     */
    public CompletableFuture<List<RoomState>> roomStates(String player, List<String> keys) {
        return db.async(c -> {
            Map<String, Long> positions = new HashMap<>();
            Map<String, String> labels = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT channel_key, label, last_id FROM " + reads + " WHERE player = ?")) {
                ps.setString(1, player);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        positions.put(rs.getString(1), rs.getLong(3));
                        if (rs.getString(1).startsWith("@")) {
                            labels.put(rs.getString(1), rs.getString(2));
                        }
                    }
                }
            }
            long max = maxId(c);
            List<RoomState> states = new ArrayList<>();
            for (String key : keys) {
                Long position = positions.get(key);
                if (position == null) {
                    markStart(c, player, key, max);
                    states.add(new RoomState(key, "", 0, 0));
                } else {
                    states.add(state(c, player, key, "", position));
                }
            }
            for (Map.Entry<String, String> conversation : labels.entrySet()) {
                states.add(state(c, player, conversation.getKey(), conversation.getValue(), positions.get(conversation.getKey())));
            }
            return states;
        });
    }

    private void markStart(Connection c, String player, String key, long position) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + reads
                + " (player, channel_key, label, last_id, updated_at) VALUES (?, ?, '', ?, ?)")) {
            ps.setString(1, player);
            ps.setString(2, key);
            ps.setLong(3, position);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private RoomState state(Connection c, String player, String key, String label, long position) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*), COALESCE(MIN(t.id), 0) FROM (SELECT m.id FROM " + messages
                + " m WHERE " + clause(key) + " AND m.id > ? AND m.deleted_at IS NULL AND m.sender <> ? ORDER BY m.id LIMIT "
                + UNREAD_CAP + ") t")) {
            ps.setString(1, clauseValue(key));
            ps.setLong(2, position);
            ps.setString(3, player);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new RoomState(key, label, rs.getInt(1), rs.getLong(2));
            }
        }
    }

    private static String clause(String key) {
        return key.startsWith("server:") ? "m.server_id = ? AND m.channel IN ('global', 'local')" : "m.channel = ?";
    }

    private static String clauseValue(String key) {
        return key.startsWith("server:") ? key.substring("server:".length()) : key;
    }

    private List<Row> collect(Connection c, PreparedStatement ps) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long replyId = rs.getLong(10);
                String replySender = rs.getString(11);
                String replyText = rs.getString(12);
                boolean replyDeleted = rs.getObject(13) != null;
                boolean hasReply = replySender != null;
                String preview = !hasReply || replyDeleted ? "" : replyText.length() <= REPLY_PREVIEW
                        ? replyText : replyText.substring(0, REPLY_PREVIEW - 1) + "…";
                rows.add(new Row(rs.getLong(1), rs.getString(2), rs.getString(3), nullToEmpty(rs.getString(4)),
                        nullToEmpty(rs.getString(5)), rs.getString(6), rs.getBoolean(7), rs.getString(8), rs.getString(9),
                        hasReply ? replyId : 0, nullToEmpty(replySender), preview, rs.getLong(14), rs.getLong(15),
                        rs.getObject(16) != null, new LinkedHashMap<>(), nullToEmpty(rs.getString(17)), rs.getString(18)));
            }
        }
        loadReactions(c, rows);
        return rows;
    }

    private void loadReactions(Connection c, List<Row> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        Map<Long, Row> byId = new HashMap<>();
        StringJoiner ids = new StringJoiner(",");
        for (Row row : rows) {
            byId.put(row.id(), row);
            ids.add(Long.toString(row.id()));
        }
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT message_id, emoji, player FROM " + reactions + " WHERE message_id IN (" + ids + ")")) {
            while (rs.next()) {
                byId.get(rs.getLong(1)).reactions().computeIfAbsent(rs.getString(2), k -> new ArrayList<>()).add(rs.getString(3));
            }
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // ---------------------------------------------------------------- purge

    /** Efface ce qui a depasse la duree de conservation : conversations privees d'un cote, salons de l'autre. */
    public CompletableFuture<Void> purge(long before, long privateBefore) {
        return db.async(c -> {
            purge(c, "m.channel NOT LIKE '@%'", before);
            purge(c, "m.channel LIKE '@%'", privateBefore);
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + reads + " WHERE channel_key LIKE '@%' AND updated_at < ?")) {
                ps.setLong(1, privateBefore);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void purge(Connection c, String condition, long before) throws SQLException {
        for (String sql : List.of(
                "DELETE x FROM " + reactions + " x JOIN " + messages + " m ON m.id = x.message_id WHERE m.created_at < ? AND " + condition,
                "DELETE x FROM " + edits + " x JOIN " + messages + " m ON m.id = x.message_id WHERE m.created_at < ? AND " + condition,
                "DELETE m FROM " + messages + " m WHERE m.created_at < ? AND " + condition)) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, before);
                ps.executeUpdate();
            }
        }
    }
}
