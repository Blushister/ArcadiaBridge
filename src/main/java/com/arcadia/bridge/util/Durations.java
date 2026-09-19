package com.arcadia.bridge.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Durees au format "30m", "2h", "7d", "1w2d"... (s, m, h, d/j, w/sem). */
public final class Durations {

    private static final Pattern PART = Pattern.compile("(\\d+)\\s*(s|m|min|h|d|j|w|sem)");

    private Durations() {
    }

    /** @return la duree en millisecondes, ou -1 si le texte est invalide. */
    public static long parse(String input) {
        if (input == null) {
            return -1;
        }
        String text = input.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return -1;
        }
        Matcher m = PART.matcher(text);
        long total = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) {
                return -1;
            }
            long n = Long.parseLong(m.group(1));
            long unit = switch (m.group(2)) {
                case "s" -> 1_000L;
                case "m", "min" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d", "j" -> 86_400_000L;
                default -> 604_800_000L;
            };
            total = Math.addExact(total, Math.multiplyExact(n, unit));
            end = m.end();
        }
        return end == text.length() && total > 0 ? total : -1;
    }

    /** Formate une duree en texte court : "2j 3h", "45min", "10s". */
    public static String format(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        long minutes = seconds % 3_600 / 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("j ");
        }
        if (hours > 0) {
            sb.append(hours).append("h ");
        }
        if (minutes > 0 && days == 0) {
            sb.append(minutes).append("min ");
        }
        if (sb.isEmpty()) {
            sb.append(Math.max(1, seconds)).append("s");
        }
        return sb.toString().trim();
    }
}
