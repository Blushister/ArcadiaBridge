package com.arcadia.bridge.util;

import java.util.Locale;

/**
 * Reconnait une tentative de verification ecrite en message texte plutot qu'en commande slash :
 * "!verify ABC123", "/verify ABC123", "verify code:ABC123"...
 *
 * <p>Ces messages laissent le code visible dans le salon : le mod les traite puis les efface, au lieu
 * de les ignorer et de laisser le code a la vue de tous.
 */
public final class VerifyInput {

    private VerifyInput() {
    }

    /**
     * @param raw    contenu brut du message
     * @param prefix prefixe des commandes texte configure (ex : "!")
     * @return le code saisi (chaine vide si l'utilisateur n'en a pas mis), ou null si le message n'est
     *         pas une tentative de verification
     */
    public static String extractCode(String raw, String prefix) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (prefix != null && !prefix.isEmpty() && text.startsWith(prefix)) {
            text = text.substring(prefix.length()).trim();
        } else if (text.startsWith("/")) {
            text = text.substring(1).trim();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        // "verif" couvre la saisie partielle que laisse l'autocompletion Discord quand elle echoue.
        String keyword = lower.startsWith("verify") ? "verify" : lower.startsWith("verif") ? "verif" : null;
        if (keyword == null) {
            return null;
        }
        String rest = text.substring(keyword.length()).trim();
        if (rest.toLowerCase(Locale.ROOT).startsWith("code:")) {
            rest = rest.substring("code:".length()).trim();
        }
        // Un seul mot attendu : au-dela, c'est une phrase qui parle de verification, pas une commande.
        return rest.isEmpty() || !rest.contains(" ") ? rest : null;
    }
}
