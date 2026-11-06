package com.arcadia.bridge.chat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.arcadia.bridge.config.BridgeConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Traduction par l'API DeepL, pour les serveurs qui n'hebergent pas le service local (translator). Meme contrat que lui :
 * la langue detectee (fr ou en) et le texte dans l'autre langue ; un texte vide quand il n'y a rien a traduire.
 *
 * <p>Utilisee seulement si translatorUrl est vide : un serveur qui a le service local ne passe jamais par DeepL. Les mentions,
 * emojis, partages ([item]) et liens sont proteges ; les messages sans contenu ("gg", "ok") ne partent pas, pour ne pas user
 * le quota. Un message anglais coute deux appels (detection, puis traduction en francais) ; un message francais, un seul.
 */
final class DeepLClient {

    private static final Pattern PROTECTED = Pattern.compile("@[A-Za-z0-9_]{1,16}|:[a-z0-9_+-]{1,40}:|\\[[^\\]\\[]{1,48}\\]|https?://\\S+");
    private static final Set<String> NOTHING_TO_TRANSLATE = Set.of("gg", "ok", "okay", "lol", "mdr", "ptdr", "xd", "gj", "wp", "ty", "thx",
            "merci", "oui", "non", "yes", "no", "afk", "brb", "re", "slt", "cc", "bye", "hi", "hello", "salut", "wesh", "bjr", "bonjour");

    private record Answer(String detected, String text) {
    }

    private DeepLClient() {
    }

    /** DeepL ne remplace le service local que s'il n'y en a pas. */
    static boolean active() {
        return BridgeConfig.HISTORY_TRANSLATOR_URL.get().isBlank() && !BridgeConfig.HISTORY_DEEPL_KEY.get().isBlank();
    }

    private static String endpoint() {
        String custom = BridgeConfig.HISTORY_DEEPL_ENDPOINT.get().trim();
        if (!custom.isEmpty()) {
            return custom;
        }
        return BridgeConfig.HISTORY_DEEPL_KEY.get().trim().endsWith(":fx")
                ? "https://api-free.deepl.com/v2/translate" : "https://api.deepl.com/v2/translate";
    }

    /** Rien a traduire : trop court, sans lettres, ou un simple "gg". */
    static boolean nothingToTranslate(String text) {
        String plain = PROTECTED.matcher(text).replaceAll(" ").strip();
        int letters = 0;
        for (int i = 0; i < plain.length(); i++) {
            if (Character.isLetter(plain.charAt(i))) {
                letters++;
            }
        }
        String word = plain.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]", "");
        return letters < 3 || NOTHING_TO_TRANSLATE.contains(word);
    }

    static CompletableFuture<MessageTranslator.Result> translate(HttpClient client, String text, Duration timeout) {
        if (nothingToTranslate(text)) {
            return CompletableFuture.completedFuture(new MessageTranslator.Result("", ""));
        }
        String xml = protect(text.strip());
        return call(client, xml, "EN-US", timeout).thenCompose(first -> switch (first.detected()) {
            case "FR" -> CompletableFuture.completedFuture(new MessageTranslator.Result("fr", unprotect(first.text())));
            case "EN" -> call(client, xml, "FR", timeout).thenApply(second -> new MessageTranslator.Result("en", unprotect(second.text())));
            // Ni francais ni anglais : la langue est notee, sans traduction (comme le service local).
            default -> CompletableFuture.completedFuture(new MessageTranslator.Result(first.detected().toLowerCase(Locale.ROOT), ""));
        });
    }

    private static CompletableFuture<Answer> call(HttpClient client, String xml, String target, Duration timeout) {
        JsonObject body = new JsonObject();
        JsonArray texts = new JsonArray();
        texts.add(xml);
        body.add("text", texts);
        body.addProperty("target_lang", target);
        body.addProperty("tag_handling", "xml");
        JsonArray ignore = new JsonArray();
        ignore.add("x");
        body.add("ignore_tags", ignore);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint()))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "DeepL-Auth-Key " + BridgeConfig.HISTORY_DEEPL_KEY.get().trim())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(response -> {
            if (response.statusCode() != 200) {
                // 403 : cle refusee ; 456 : quota du mois epuise ; 429 : trop de demandes.
                throw new IllegalStateException("DeepL a repondu " + response.statusCode());
            }
            JsonObject translation = JsonParser.parseString(response.body()).getAsJsonObject()
                    .getAsJsonArray("translations").get(0).getAsJsonObject();
            return new Answer(translation.get("detected_source_language").getAsString().toUpperCase(Locale.ROOT),
                    translation.get("text").getAsString());
        });
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Le texte en XML pour DeepL, les elements proteges entoures de x (ignore_tags). */
    static String protect(String text) {
        StringBuilder out = new StringBuilder();
        Matcher matcher = PROTECTED.matcher(text);
        int last = 0;
        while (matcher.find()) {
            out.append(escape(text.substring(last, matcher.start()))).append("<x>").append(escape(matcher.group())).append("</x>");
            last = matcher.end();
        }
        return out.append(escape(text.substring(last))).toString();
    }

    static String unprotect(String xml) {
        return xml.replace("<x>", "").replace("</x>", "").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }
}
