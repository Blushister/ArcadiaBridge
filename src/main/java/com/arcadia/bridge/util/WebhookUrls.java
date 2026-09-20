package com.arcadia.bridge.util;

import java.util.regex.Pattern;

/** Reconnaissance des URL de webhook Discord. */
public final class WebhookUrls {

    /**
     * JDA renvoie une URL versionnee ({@code /api/v10/webhooks/...}) alors que l'interface Discord en
     * donne une sans version : les deux doivent etre acceptees.
     */
    private static final Pattern WEBHOOK_URL = Pattern.compile(
            "https://(discord|discordapp)\\.com/api/(v\\d+/)?webhooks/\\d+/[\\w-]+");

    private WebhookUrls() {
    }

    public static boolean isWebhookUrl(String url) {
        return url != null && WEBHOOK_URL.matcher(url.trim()).matches();
    }
}
