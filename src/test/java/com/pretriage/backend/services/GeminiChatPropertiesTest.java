package com.pretriage.backend.services;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GeminiChatPropertiesTest {
    @Test
    void enlazaConfiguracionConPresupuestoDeSalidaYDefaults() {
        var source = new MapConfigurationPropertySource(Map.of("pretriage.ia.gemini.api-key", "test",
                "pretriage.ia.gemini.max-output-tokens", "4096"));
        var config = new Binder(source).bind("pretriage.ia.gemini", Bindable.of(GeminiChatProperties.class)).get();
        assertEquals(4096, config.maxOutputTokens());
        assertEquals("gemini-3.8-flash", config.model());
        source = new MapConfigurationPropertySource(Map.of("pretriage.ia.gemini.api-key", "test"));
        config = new Binder(source).bind("pretriage.ia.gemini", Bindable.of(GeminiChatProperties.class)).get();
        assertEquals(8192, config.maxOutputTokens());
        assertEquals(2, config.maxAttempts());
        assertEquals(java.time.Duration.ofSeconds(20), config.attemptTimeout());
        assertEquals(java.time.Duration.ofMillis(500), config.retryInitialBackoff());
    }

    @Test
    void rechazaPresupuestoInvalidoAntesDeConsultarElProveedor() {
        for (int presupuesto : new int[]{0, -1, 65537}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new GeminiChatProperties("test", null, null, null, null, presupuesto));
        }
    }

    @Test
    void enlazaOpcionesDeReintentoYRechazaValoresFueraDeRango() {
        var source = new MapConfigurationPropertySource(Map.of(
                "pretriage.ia.gemini.max-attempts", "3",
                "pretriage.ia.gemini.attempt-timeout", "8s",
                "pretriage.ia.gemini.retry-initial-backoff", "250ms"));
        var config = new Binder(source).bind("pretriage.ia.gemini", Bindable.of(GeminiChatProperties.class)).get();
        assertEquals(3, config.maxAttempts());
        assertEquals(java.time.Duration.ofSeconds(8), config.attemptTimeout());
        assertEquals(java.time.Duration.ofMillis(250), config.retryInitialBackoff());

        assertThrows(IllegalArgumentException.class, () -> new GeminiChatProperties("test", null, null,
                null, null, null, 6, null, null));
        assertThrows(IllegalArgumentException.class, () -> new GeminiChatProperties("test", null, null,
                null, null, null, 2, java.time.Duration.ZERO, null));
        assertThrows(IllegalArgumentException.class, () -> new GeminiChatProperties("test", null, null,
                null, null, null, 2, null, java.time.Duration.ofSeconds(31)));
        assertThrows(IllegalArgumentException.class, () -> new GeminiChatProperties("test", null, null,
                java.time.Duration.ZERO, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new GeminiChatProperties("test", null, null,
                null, java.time.Duration.ZERO, null, null, null, null));
    }
}
