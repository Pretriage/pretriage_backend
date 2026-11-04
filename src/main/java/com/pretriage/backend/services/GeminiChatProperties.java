package com.pretriage.backend.services;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

@ConfigurationProperties(prefix = "pretriage.ia.gemini")
public record GeminiChatProperties(String apiKey, String model, String baseUrl,
                                   Duration connectTimeout, Duration requestTimeout, Integer maxOutputTokens,
                                   Integer maxAttempts, Duration attemptTimeout, Duration retryInitialBackoff) {
    public GeminiChatProperties(String apiKey, String model, String baseUrl,
                                Duration connectTimeout, Duration requestTimeout) {
        this(apiKey, model, baseUrl, connectTimeout, requestTimeout, null, null, null, null);
    }

    public GeminiChatProperties(String apiKey, String model, String baseUrl,
                                Duration connectTimeout, Duration requestTimeout, Integer maxOutputTokens) {
        this(apiKey, model, baseUrl, connectTimeout, requestTimeout, maxOutputTokens, null, null, null);
    }

    @ConstructorBinding
    public GeminiChatProperties {
        if (model == null || model.isBlank()) model = "gemini-3.5-flash-lite";
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://generativelanguage.googleapis.com/v1beta";
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(5);
        if (requestTimeout == null) requestTimeout = Duration.ofSeconds(45);
        if (maxOutputTokens == null) maxOutputTokens = 8192;
        if (maxAttempts == null) maxAttempts = 2;
        if (attemptTimeout == null) attemptTimeout = Duration.ofSeconds(20);
        if (retryInitialBackoff == null) retryInitialBackoff = Duration.ofMillis(500);
        if (maxOutputTokens < 1 || maxOutputTokens > 65536) {
            throw new IllegalArgumentException("Gemini max-output-tokens debe estar entre 1 y 65536");
        }
        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("Gemini connect-timeout debe ser positivo");
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("Gemini request-timeout debe ser positivo");
        }
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException("Gemini max-attempts debe estar entre 1 y 5");
        }
        if (attemptTimeout.isZero() || attemptTimeout.isNegative()) {
            throw new IllegalArgumentException("Gemini attempt-timeout debe ser positivo");
        }
        if (retryInitialBackoff.isNegative() || retryInitialBackoff.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("Gemini retry-initial-backoff debe estar entre 0 y 5 segundos");
        }
    }
}
