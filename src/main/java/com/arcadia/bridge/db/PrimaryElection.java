package com.arcadia.bridge.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Election du serveur primaire par un verrou MariaDB ({@code GET_LOCK}).
 *
 * <p>Le serveur qui obtient le verrou est primaire tant qu'il garde ouverte la connexion qui le detient.
 * S'il s'arrete proprement, il rend le verrou ; s'il plante ou perd le reseau, MariaDB le libere de
 * lui-meme a la fermeture de la connexion. Un autre serveur le reprend alors au tour suivant.
 *
 * <p>Toutes les methodes sont appelees depuis le thread de synchronisation.
 */
public final class PrimaryElection implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(PrimaryElection.class);
    /** Limite MariaDB sur la longueur d'un nom de verrou. */
    private static final int MAX_LOCK_NAME = 64;

    private final Database db;
    private final String lockName;
    /** Connexion qui detient le verrou ; non nulle tant que ce serveur est primaire. */
    private Connection held;

    public PrimaryElection(Database db, String lockName) {
        this.db = db;
        this.lockName = lockName.length() > MAX_LOCK_NAME ? lockName.substring(0, MAX_LOCK_NAME) : lockName;
    }

    /**
     * Un tour d'election : verifie que le verrou est toujours detenu, ou tente de l'obtenir.
     *
     * @return true si ce serveur est primaire a l'issue du tour
     */
    public synchronized boolean tick() {
        if (held != null) {
            if (stillHolds()) {
                return true;
            }
            LOGGER.warn("[ArcadiaBridge] Verrou de serveur primaire perdu (connexion interrompue).");
            closeQuietly();
        }
        Connection candidate = null;
        try {
            candidate = db.rawConnection();
            try (PreparedStatement ps = candidate.prepareStatement("SELECT GET_LOCK(?, 0)")) {
                ps.setString(1, lockName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) == 1) {
                        held = candidate;
                        return true;
                    }
                }
            }
        } catch (SQLException e) {
            LOGGER.warn("[ArcadiaBridge] Election du serveur primaire impossible : {}", e.getMessage());
        }
        closeQuietly(candidate);
        return false;
    }

    public synchronized boolean isPrimary() {
        return held != null;
    }

    private boolean stillHolds() {
        try (PreparedStatement ps = held.prepareStatement("SELECT IS_USED_LOCK(?) = CONNECTION_ID()")) {
            ps.setString(1, lockName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /** Rend le verrou : un autre serveur pourra devenir primaire des son prochain tour. */
    @Override
    public synchronized void close() {
        if (held == null) {
            return;
        }
        try (PreparedStatement ps = held.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            ps.setString(1, lockName);
            ps.executeQuery().close();
        } catch (SQLException ignored) {
            // La fermeture de la connexion libere le verrou de toute facon.
        }
        closeQuietly();
    }

    private void closeQuietly() {
        closeQuietly(held);
        held = null;
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Rien a faire : la connexion est de toute facon perdue.
        }
    }
}
