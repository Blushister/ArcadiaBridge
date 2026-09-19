package com.arcadia.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arcadia.bridge.util.Durations;
import com.arcadia.bridge.util.Text;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Nettoyage des textes echanges entre Minecraft et Discord. */
class TextSanitizeTest {

    @Test
    @DisplayName("Un joueur ne peut pas afficher une mention de masse sur Discord")
    void massMentionsAreNeutralized() {
        assertFalse(Text.forDiscord("salut @everyone").contains("@everyone"));
        assertFalse(Text.forDiscord("salut @here").contains("@here"));
        // Le texte reste lisible : seule une espace de largeur nulle est inseree apres le @.
        assertTrue(Text.forDiscord("salut @everyone").contains("everyone"));
    }

    @Test
    @DisplayName("Les mentions de role et de membre sont desamorcees")
    void idMentionsAreNeutralized() {
        assertFalse(Text.forDiscord("coucou <@&123456789012345678>").contains("<@&123456789012345678>"));
        assertFalse(Text.forDiscord("coucou <@123456789012345678>").contains("<@123456789012345678>"));
    }

    @Test
    @DisplayName("Une adresse e-mail n'est pas abimee")
    void plainAtSignIsKept() {
        assertEquals("contact@arcadia.fr", Text.forDiscord("contact@arcadia.fr"));
    }

    @Test
    @DisplayName("Le markdown Discord est echappe")
    void markdownIsEscaped() {
        String out = Text.forDiscord("**gras** et `code`");
        assertFalse(out.contains("**gras**"));
        assertFalse(out.contains("`code`"));
    }

    @Test
    @DisplayName("Les codes couleur Minecraft disparaissent dans les deux sens")
    void formattingCodesAreStripped() {
        assertEquals("rouge", Text.forDiscord("§crouge"));
        assertEquals("rouge", Text.forDiscord("&crouge"));
        assertEquals("texte", Text.forMinecraft("§ktexte", 100));
        assertFalse(Text.forMinecraft("&4&ladmin", 100).contains("&4"));
    }

    @Test
    @DisplayName("Un message Discord trop long est coupe et tient sur une ligne")
    void longMessagesAreTruncated() {
        String out = Text.forMinecraft("a".repeat(500) + "\nsuite", 64);
        assertEquals(64, out.length());
        assertFalse(out.contains("\n"));
    }

    @Test
    @DisplayName("Les durees sont analysees et formatees")
    void durationsRoundTrip() {
        assertEquals(30 * 60_000L, Durations.parse("30m"));
        assertEquals(2 * 3_600_000L, Durations.parse("2h"));
        assertEquals(7 * 86_400_000L, Durations.parse("7d"));
        assertEquals(86_400_000L + 12 * 3_600_000L, Durations.parse("1d12h"));
        assertEquals(-1, Durations.parse("bientot"));
        assertEquals(-1, Durations.parse("10"));
        assertEquals(-1, Durations.parse("5m plus tard"));
        assertEquals("1j 12h", Durations.format(Durations.parse("1d12h")));
        assertEquals("30min", Durations.format(Durations.parse("30m")));
    }
}
