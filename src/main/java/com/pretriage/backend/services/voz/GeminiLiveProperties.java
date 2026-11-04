package com.pretriage.backend.services.voz;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuracion del proveedor de voz Gemini Live ({@code pretriage.voz.gemini.*}).
 */
@ConfigurationProperties(prefix = "pretriage.voz.gemini")
public record GeminiLiveProperties(
        String apiKey,
        String modelo,
        String url,
        String voz,
        String idioma,
        Duration connectTimeout) {

    public GeminiLiveProperties {
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(10);
        }
    }

    public boolean configurado() {
        return apiKey != null && !apiKey.isBlank()
                && modelo != null && !modelo.isBlank()
                && url != null && !url.isBlank();
    }
}
