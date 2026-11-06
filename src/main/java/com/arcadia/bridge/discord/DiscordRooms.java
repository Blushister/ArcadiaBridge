package com.arcadia.bridge.discord;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.arcadia.bridge.config.BridgeConfig;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Salons Discord relies a l'ecran facon Discord d'ArcadiaChat, declares dans la configuration :
 * "nom=identifiant", suivi au besoin de ":ro" (lecture seule en jeu) et de ":staff" (visible par le staff seulement).
 */
public final class DiscordRooms {

    public static final String PREFIX = "discord:";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern ENTRY = Pattern.compile("([a-z0-9_-]{2,24})=(\\d{5,20})((?::(?:ro|staff))*)");

    public record Room(String name, long channelId, boolean readOnly, boolean staffOnly) {
    }

    private static List<? extends String> parsedFrom;
    private static List<Room> rooms = List.of();

    private DiscordRooms() {
    }

    public static synchronized List<Room> all() {
        List<? extends String> configured = BridgeConfig.HISTORY_DISCORD_ROOMS.get();
        if (configured != parsedFrom) {
            parsedFrom = configured;
            rooms = parse(configured);
        }
        return rooms;
    }

    public static Room byName(String name) {
        for (Room room : all()) {
            if (room.name().equals(name)) {
                return room;
            }
        }
        return null;
    }

    public static Room byChannelId(long id) {
        for (Room room : all()) {
            if (room.channelId() == id) {
                return room;
            }
        }
        return null;
    }

    static List<Room> parse(List<? extends String> entries) {
        List<Room> list = new ArrayList<>();
        for (String entry : entries) {
            Matcher matcher = ENTRY.matcher(entry.trim().toLowerCase(Locale.ROOT));
            if (!matcher.matches()) {
                LOGGER.warn("[ArcadiaBridge] Salon Discord ignore (attendu : nom=identifiant[:ro][:staff]) : {}", entry);
                continue;
            }
            try {
                String flags = matcher.group(3);
                list.add(new Room(matcher.group(1), Long.parseUnsignedLong(matcher.group(2)), flags.contains(":ro"),
                        flags.contains(":staff")));
            } catch (NumberFormatException e) {
                LOGGER.warn("[ArcadiaBridge] Salon Discord ignore (identifiant invalide) : {}", entry);
            }
        }
        return List.copyOf(list);
    }
}
