package com.arcadia.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arcadia.bridge.util.WebhookUrls;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Reconnaissance des URL de webhook, dans leurs deux formes. */
class WebhookUrlTest {

    @Test
    @DisplayName("L'URL versionnee renvoyee par JDA est acceptee")
    void versionedUrlIsAccepted() {
        assertTrue(WebhookUrls.isWebhookUrl("https://discord.com/api/v10/webhooks/123456789/jeton-abc_DEF"));
    }

    @Test
    @DisplayName("L'URL copiee depuis Discord est acceptee")
    void plainUrlIsAccepted() {
        assertTrue(WebhookUrls.isWebhookUrl("https://discord.com/api/webhooks/123456789/jeton-abc_DEF"));
        assertTrue(WebhookUrls.isWebhookUrl("  https://discordapp.com/api/webhooks/123456789/jeton  "));
    }

    @Test
    @DisplayName("Tout le reste est refuse")
    void othersAreRejected() {
        assertFalse(WebhookUrls.isWebhookUrl(""));
        assertFalse(WebhookUrls.isWebhookUrl(null));
        assertFalse(WebhookUrls.isWebhookUrl("https://exemple.fr/api/webhooks/1/jeton"));
        assertFalse(WebhookUrls.isWebhookUrl("https://discord.com/api/v10/channels/123/messages"));
    }
}
