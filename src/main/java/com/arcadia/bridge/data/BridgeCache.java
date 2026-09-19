package com.arcadia.bridge.data;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.arcadia.bridge.data.Models.Link;
import com.arcadia.bridge.data.Models.Sanction;
import com.arcadia.bridge.data.Models.SanctionType;

/**
 * Copie en memoire des liaisons et des sanctions en vigueur, rechargee depuis la base partagee a
 * intervalle regulier. Les verifications du thread principal (connexion, chat) ne lisent que ce cache.
 */
public final class BridgeCache {

    private final Map<UUID, Link> links = new ConcurrentHashMap<>();
    private final Map<UUID, Sanction> bans = new ConcurrentHashMap<>();
    private final Map<UUID, Sanction> mutes = new ConcurrentHashMap<>();
    /** Derniere modification locale par cle ("LINK:uuid", "BAN:uuid"...) : protege contre un rechargement perime. */
    private final Map<String, Long> localChanges = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    public boolean isLoaded() {
        return loaded;
    }

    // ---------------------------------------------------------------- liaisons

    public boolean isLinked(UUID uuid) {
        return links.containsKey(uuid);
    }

    public Optional<Link> link(UUID uuid) {
        return Optional.ofNullable(links.get(uuid));
    }

    public void putLink(Link link) {
        touch("LINK", link.uuid());
        links.put(link.uuid(), link);
    }

    public void removeLink(UUID uuid) {
        touch("LINK", uuid);
        links.remove(uuid);
    }

    /** @param loadStartedAt instant ou la lecture en base a commence (les changements locaux posterieurs sont conserves) */
    public void replaceLinks(Map<UUID, Link> fresh, long loadStartedAt) {
        links.keySet().removeIf(uuid -> !fresh.containsKey(uuid) && !changedSince("LINK", uuid, loadStartedAt));
        fresh.forEach((uuid, link) -> {
            if (!changedSince("LINK", uuid, loadStartedAt)) {
                links.put(uuid, link);
            }
        });
    }

    // ---------------------------------------------------------------- sanctions

    public Optional<Sanction> activeBan(UUID uuid) {
        return inEffect(bans, uuid);
    }

    public Optional<Sanction> activeMute(UUID uuid) {
        return inEffect(mutes, uuid);
    }

    public void putSanction(Sanction s) {
        touch(s.type().name(), s.uuid());
        if (s.type() == SanctionType.BAN) {
            bans.put(s.uuid(), s);
        } else if (s.type() == SanctionType.MUTE) {
            mutes.put(s.uuid(), s);
        }
    }

    public void removeSanction(UUID uuid, SanctionType type) {
        touch(type.name(), uuid);
        if (type == SanctionType.BAN) {
            bans.remove(uuid);
        } else if (type == SanctionType.MUTE) {
            mutes.remove(uuid);
        }
    }

    /** @param loadStartedAt instant ou la lecture en base a commence (les changements locaux posterieurs sont conserves) */
    public void replaceSanctions(List<Sanction> active, long loadStartedAt) {
        Map<UUID, Sanction> freshBans = new ConcurrentHashMap<>();
        Map<UUID, Sanction> freshMutes = new ConcurrentHashMap<>();
        for (Sanction s : active) {
            Map<UUID, Sanction> target = s.type() == SanctionType.BAN ? freshBans : freshMutes;
            // En cas de doublon, on garde la sanction la plus longue.
            target.merge(s.uuid(), s, (a, b) -> lastsLonger(a, b) ? a : b);
        }
        merge(bans, freshBans, "BAN", loadStartedAt);
        merge(mutes, freshMutes, "MUTE", loadStartedAt);
        localChanges.values().removeIf(t -> t < loadStartedAt - 60_000L);
        loaded = true;
    }

    private void merge(Map<UUID, Sanction> current, Map<UUID, Sanction> fresh, String kind, long since) {
        current.keySet().removeIf(uuid -> !fresh.containsKey(uuid) && !changedSince(kind, uuid, since));
        fresh.forEach((uuid, s) -> {
            if (!changedSince(kind, uuid, since)) {
                current.put(uuid, s);
            }
        });
    }

    private void touch(String kind, UUID uuid) {
        localChanges.put(kind + ":" + uuid, System.currentTimeMillis());
    }

    private boolean changedSince(String kind, UUID uuid, long since) {
        Long t = localChanges.get(kind + ":" + uuid);
        return t != null && t >= since;
    }

    private static Optional<Sanction> inEffect(Map<UUID, Sanction> map, UUID uuid) {
        Sanction s = map.get(uuid);
        if (s == null) {
            return Optional.empty();
        }
        if (!s.isInEffect(System.currentTimeMillis())) {
            map.remove(uuid, s);
            return Optional.empty();
        }
        return Optional.of(s);
    }

    private static boolean lastsLonger(Sanction a, Sanction b) {
        if (a.expiresAt() == null) {
            return true;
        }
        return b.expiresAt() != null && a.expiresAt() >= b.expiresAt();
    }
}
