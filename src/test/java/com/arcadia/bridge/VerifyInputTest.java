package com.arcadia.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.arcadia.bridge.util.VerifyInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Detection des verifications tapees en message texte : ces messages exposent le code dans le salon,
 * le mod doit les reconnaitre pour les traiter puis les effacer.
 */
class VerifyInputTest {

    private static String code(String raw) {
        return VerifyInput.extractCode(raw, "!");
    }

    @Test
    @DisplayName("Les differentes facons d'ecrire la commande sont reconnues")
    void commandFormsAreRecognized() {
        assertEquals("ABC123", code("!verify ABC123"));
        assertEquals("ABC123", code("/verify ABC123"));
        assertEquals("ABC123", code("verify ABC123"));
        assertEquals("ABC123", code("/verif code:ABC123"));
        assertEquals("ABC123", code("!Verify   abc123".replace("abc123", "ABC123")));
        assertEquals("ABC123", code("  /verify ABC123  "));
    }

    @Test
    @DisplayName("Sans code, on renvoie une chaine vide pour afficher l'aide")
    void missingCodeAsksForHelp() {
        assertEquals("", code("!verify"));
        assertEquals("", code("/verify"));
    }

    @Test
    @DisplayName("Une phrase ordinaire n'est pas prise pour une commande")
    void normalSentencesAreIgnored() {
        assertNull(code("bonjour tout le monde"));
        assertNull(code("comment on verify deja ?"));
        assertNull(code("verify ABC123 stp les gars"));
        assertNull(code("je dois verifier mon compte"));
    }
}
