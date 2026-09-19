package com.arcadia.bridge.i18n;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.arcadia.bridge.util.Text;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;

/**
 * Messages du mod, cote serveur (les clients n'ont pas le mod, donc pas de fichiers de langue client).
 * Les textes par defaut sont dans le jar ; une copie modifiable est ecrite dans config/arcadiabridge/lang/.
 */
public final class Lang {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile Map<String, String> messages = Map.of();

    private Lang() {
    }

    public static void load(Path configDir, String language) {
        String lang = language.toLowerCase().replace('-', '_');
        Map<String, String> result = new HashMap<>(readBundled("en_us"));
        result.putAll(readBundled(lang));

        Path file = configDir.resolve("lang").resolve(lang + ".json");
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                try (InputStream in = Lang.class.getResourceAsStream(bundledPath(lang))) {
                    if (in != null) {
                        Files.copy(in, file);
                    }
                }
            }
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    result.putAll(toMap(JsonParser.parseReader(reader).getAsJsonObject()));
                }
            }
        } catch (Exception e) {
            LOGGER.error("[ArcadiaBridge] Impossible de lire le fichier de langue {} : {}", file, e.toString());
        }
        messages = Map.copyOf(result);
    }

    /** Texte brut (pour Discord). */
    public static String get(String key, Object... args) {
        return Text.fill(messages.getOrDefault(key, key), args);
    }

    /**
     * Composant Minecraft : les codes & du modele sont appliques AVANT l'insertion des variables,
     * et les variables sont nettoyees, donc un pseudo ou un message ne peut pas injecter de mise en forme.
     */
    public static MutableComponent mc(String key, Object... args) {
        Object[] safe = args.clone();
        for (int i = 1; i < safe.length; i += 2) {
            safe[i] = Text.stripFormatting(String.valueOf(safe[i]));
        }
        String template = Text.colorize(messages.getOrDefault(key, key));
        return Component.literal(Text.fill(template, safe));
    }

    private static String bundledPath(String lang) {
        return "/arcadiabridge/lang/" + lang + ".json";
    }

    private static Map<String, String> readBundled(String lang) {
        try (InputStream in = Lang.class.getResourceAsStream(bundledPath(lang))) {
            if (in == null) {
                return Map.of();
            }
            return toMap(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (Exception e) {
            LOGGER.error("[ArcadiaBridge] Langue integree {} illisible : {}", lang, e.toString());
            return Map.of();
        }
    }

    private static Map<String, String> toMap(JsonObject json) {
        Map<String, String> map = new HashMap<>();
        json.entrySet().forEach(e -> map.put(e.getKey(), e.getValue().getAsString()));
        return map;
    }
}
