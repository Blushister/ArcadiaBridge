package com.arcadia.bridge.moderation;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.arcadia.bridge.db.Database;

/**
 * Notes du staff sur un joueur ("deja averti a l'oral pour du grief") : visibles seulement par le staff, dans l'historique du
 * joueur. La table se cree a la premiere utilisation.
 */
public final class StaffNotes {

    public record Note(String text, String actor, long createdAt) {
    }

    private final Database db;
    private volatile boolean ready;

    public StaffNotes(Database db) {
        this.db = db;
    }

    private void ensureTable(java.sql.Connection c) throws java.sql.SQLException {
        if (!ready) {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS " + db.t("player_notes") + " ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                        + "uuid CHAR(36) NOT NULL,"
                        + "name VARCHAR(16) NOT NULL,"
                        + "text VARCHAR(512) NOT NULL,"
                        + "actor VARCHAR(64) NOT NULL,"
                        + "created_at BIGINT NOT NULL,"
                        + "INDEX (uuid))");
            }
            ready = true;
        }
    }

    public CompletableFuture<Void> add(UUID uuid, String name, String text, String actor) {
        return db.async(c -> {
            ensureTable(c);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.t("player_notes")
                    + " (uuid, name, text, actor, created_at) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setString(3, text.length() > 512 ? text.substring(0, 512) : text);
                ps.setString(4, actor);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<List<Note>> list(UUID uuid, int limit) {
        return db.async(c -> {
            ensureTable(c);
            List<Note> notes = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT text, actor, created_at FROM " + db.t("player_notes")
                    + " WHERE uuid = ? ORDER BY id DESC LIMIT ?")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        notes.add(new Note(rs.getString(1), rs.getString(2), rs.getLong(3)));
                    }
                }
            }
            return notes;
        });
    }
}
