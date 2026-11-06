package com.pretriage.backend.services;

import com.pretriage.backend.exceptions.ProveedorIaException;
import com.pretriage.backend.exceptions.ProveedorIaException.Categoria;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.net.ssl.SSLException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/** Bounded Gemini REST request with JSON Schema and no credentials or patient text in logs. */
@Service
@EnableConfigurationProperties(GeminiChatProperties.class)
public class GeminiJsonClient {
    private static final Logger log = LoggerFactory.getLogger(GeminiJsonClient.class);
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(408, 429, 500, 502, 503, 504);
    private static final Set<String> FINISH_REASONS = Set.of("FINISH_REASON_UNSPECIFIED", "STOP", "MAX_TOKENS",
            "SAFETY", "RECITATION", "LANGUAGE", "OTHER", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII",
            "MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL", "TOO_MANY_TOOL_CALLS", "MALFORMED_RESPONSE");

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final GeminiChatProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final LongSupplier nanoClock;
    private final Sleeper sleeper;
    private final Clock wallClock;
    private final DoubleSupplier random;

    @Autowired
    public GeminiJsonClient(GeminiChatProperties properties, ObjectMapper mapper) {
        this(properties, mapper, HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
    }

    GeminiJsonClient(GeminiChatProperties properties, ObjectMapper mapper, HttpClient http) {
        this(properties, mapper, http, System::nanoTime,
                duration -> Thread.sleep(duration.toMillis(), duration.minusMillis(duration.toMillis()).getNano()),
                Clock.systemUTC(), Math::random);
    }

    GeminiJsonClient(GeminiChatProperties properties, ObjectMapper mapper, HttpClient http,
                     LongSupplier nanoClock, Sleeper sleeper, Clock wallClock, DoubleSupplier random) {
        this.properties = properties;
        this.mapper = mapper;
        this.http = http;
        this.nanoClock = nanoClock;
        this.sleeper = sleeper;
        this.wallClock = wallClock;
        this.random = random;
    }

    public String generar(String systemPrompt, String input, Map<String, Object> schema) {
        return generar(systemPrompt, input, schema, properties.requestTimeout());
    }

    public String generar(String systemPrompt, String input, Map<String, Object> schema, Duration maxWait) {
        long startedAt = nanoClock.getAsLong();
        Duration budget = maxWait.compareTo(properties.requestTimeout()) < 0 ? maxWait : properties.requestTimeout();
        if (budget.isZero() || budget.isNegative()) throw failure("Plazo Gemini agotado", Categoria.OPERACION_INTERNA);
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw failure("Gemini no configurado: falta GEMINI_API_KEY", Categoria.CONFIGURACION);
        }
        String model = properties.model().replaceFirst("^models/", "");
        if (!model.matches("[A-Za-z0-9._-]+")) throw failure("Modelo Gemini invalido", Categoria.CONFIGURACION);

        final URI endpoint;
        final String serializedBody;
        try {
            Map<String, Object> body = Map.of(
                    "systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                    "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", input)))),
                    "generationConfig", Map.of("temperature", 0, "maxOutputTokens", properties.maxOutputTokens(),
                            "responseFormat", Map.of("text", Map.of("mimeType", "APPLICATION_JSON", "schema", schema))));
            endpoint = URI.create(properties.baseUrl().replaceAll("/+$", "") + "/models/" + model + ":generateContent");
            serializedBody = mapper.writeValueAsString(body);
        } catch (Exception error) {
            throw failure("No se pudo preparar la consulta Gemini");
        }

        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            Duration remaining = remaining(budget, startedAt);
            if (remaining.isZero() || remaining.isNegative()) throw failure("Plazo Gemini agotado");
            Duration attemptLimit = min(properties.attemptTimeout(), remaining);
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(attemptLimit)
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", properties.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(serializedBody)).build();
            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw failure("Consulta Gemini interrumpida", Categoria.OPERACION_INTERNA);
            } catch (IOException error) {
                long elapsedMs = elapsedMillis(startedAt);
                if (!isRetryableTransport(error) || !canRetry(attempt, budget, startedAt)) {
                    log.warn("Gemini attempt={} status=transport-error elapsedMs={}", attempt, elapsedMs);
                    Categoria categoria = isRetryableTransport(error)
                            ? Categoria.API_NO_DISPONIBLE : Categoria.CONFIGURACION;
                    throw failure("Fallo de transporte Gemini (" + error.getClass().getSimpleName() + ")", categoria);
                }
                Duration delay = backoff(attempt);
                if (!waitBeforeRetry(attempt, "transport-error", delay, budget, startedAt)) {
                    throw failure("Fallo de transporte Gemini (" + error.getClass().getSimpleName() + ")",
                            Categoria.API_NO_DISPONIBLE);
                }
                continue;
            } catch (Exception error) {
                throw failure("Fallo de transporte Gemini: " + error.getClass().getSimpleName(),
                        Categoria.OPERACION_INTERNA);
            }

            int status = response.statusCode();
            if (status == 200) return parseSuccess(response);
            Duration retryAfter = retryAfter(response);
            boolean apiUnavailable = status == 408 || status == 429 || status >= 500;
            boolean retryable = RETRYABLE_STATUSES.contains(status) || (status == 429 && retryAfter != null);
            if (!retryable) {
                log.warn("Gemini attempt={} status={} elapsedMs={}", attempt, status, elapsedMillis(startedAt));
                Categoria categoria = apiUnavailable ? Categoria.API_NO_DISPONIBLE
                        : (status >= 300 && status < 500 ? Categoria.CONFIGURACION : Categoria.OPERACION_INTERNA);
                throw failure("Gemini HTTP " + status, categoria, status, retryAfter);
            }
            if (!canRetry(attempt, budget, startedAt)) {
                log.warn("Gemini attempt={} status={} elapsedMs={} retry=exhausted", attempt, status, elapsedMillis(startedAt));
                throw failure("Gemini HTTP " + status, Categoria.API_NO_DISPONIBLE, status, retryAfter);
            }
            Duration delay = retryAfter != null && (status == 429 || status == 503)
                    ? retryAfter : backoff(attempt);
            if (!waitBeforeRetry(attempt, Integer.toString(status), delay, budget, startedAt)) {
                throw failure("Gemini HTTP " + status, Categoria.API_NO_DISPONIBLE, status, retryAfter);
            }
        }
        throw failure("Reintentos Gemini agotados", Categoria.OPERACION_INTERNA);
    }

    private String parseSuccess(HttpResponse<String> response) {
        try {
            var root = mapper.readTree(response.body());
            var candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) throw failure("Gemini no devolvio candidatos", Categoria.VALIDACION_RESPUESTA);
            var candidate = candidates.get(0);
            if (!"STOP".equals(candidate.path("finishReason").asText())) {
                String reason = candidate.path("finishReason").asText("");
                // Never log arbitrary provider text (including finishMessage) or clinical content.
                String safeReason = reason.isEmpty() ? "MISSING" : FINISH_REASONS.contains(reason) ? reason : "UNKNOWN";
                var usage = root.path("usageMetadata");
                throw failure("Gemini no completo la respuesta: finishReason=" + safeReason
                        + " outputTokens=" + usage.path("candidatesTokenCount").asLong(-1)
                        + " thoughtTokens=" + usage.path("thoughtsTokenCount").asLong(-1)
                        + " maxOutputTokens=" + properties.maxOutputTokens(), Categoria.VALIDACION_RESPUESTA);
            }
            StringBuilder output = new StringBuilder();
            for (var part : candidate.path("content").path("parts")) {
                if (!part.path("thought").asBoolean(false) && part.path("text").isString()) {
                    output.append(part.path("text").stringValue());
                }
            }
            if (output.isEmpty()) throw failure("Gemini devolvio una respuesta vacia", Categoria.VALIDACION_RESPUESTA);
            return output.toString();
        } catch (ProveedorIaException error) {
            throw error;
        } catch (Exception error) {
            // Do not attach provider response bodies: they may contain clinical data.
            throw failure("Fallo de formato Gemini", Categoria.VALIDACION_RESPUESTA);
        }
    }

    private boolean canRetry(int attempt, Duration budget, long startedAt) {
        return attempt < properties.maxAttempts() && !remaining(budget, startedAt).isZero();
    }

    private static boolean isRetryableTransport(IOException error) {
        // TLS negotiation and certificate validation failures are deterministic until configuration changes.
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof SSLException || cause instanceof CertificateException
                    || cause instanceof CertPathValidatorException) return false;
        }
        return true;
    }

    private boolean waitBeforeRetry(int attempt, String status, Duration delay, Duration budget, long startedAt) {
        Duration remaining = remaining(budget, startedAt);
        // A retry that cannot start within the operation budget is not useful.
        if (delay.compareTo(remaining) >= 0) {
            log.warn("Gemini attempt={} status={} elapsedMs={} retry=skipped", attempt, status, elapsedMillis(startedAt));
            return false;
        }
        log.info("Gemini attempt={} status={} elapsedMs={} retryDelayMs={}",
                attempt, status, elapsedMillis(startedAt), safeMillis(delay));
        try {
            if (!delay.isZero()) sleeper.sleep(delay);
            return !remaining(budget, startedAt).isZero();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw failure("Consulta Gemini interrumpida durante espera", Categoria.OPERACION_INTERNA);
        }
    }

    private Duration backoff(int attempt) {
        long initialNanos = properties.retryInitialBackoff().toNanos();
        long exponentialNanos = initialNanos;
        for (int i = 1; i < attempt && exponentialNanos < Duration.ofSeconds(5).toNanos(); i++) {
            exponentialNanos = Math.min(Duration.ofSeconds(5).toNanos(), exponentialNanos * 2);
        }
        // 50% to 150% of the exponential delay, bounded to avoid overflow.
        double factor = 0.5 + random.getAsDouble();
        return Duration.ofNanos(Math.max(0, Math.min(Duration.ofSeconds(8).toNanos(), (long) (exponentialNanos * factor))));
    }

    private Duration retryAfter(HttpResponse<?> response) {
        if (response.headers() == null) return null;
        String value = response.headers().firstValue("Retry-After").orElse(null);
        if (value == null || value.isBlank()) return null;
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            try {
                var retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration duration = Duration.between(wallClock.instant(), retryAt);
                return duration.isNegative() ? Duration.ZERO : duration;
            } catch (DateTimeParseException ignoredDate) {
                return null;
            }
        }
    }

    private Duration remaining(Duration budget, long startedAt) {
        long budgetNanos;
        try {
            budgetNanos = budget.toNanos();
        } catch (ArithmeticException overflow) {
            budgetNanos = Long.MAX_VALUE;
        }
        long elapsed = Math.max(0, nanoClock.getAsLong() - startedAt);
        return Duration.ofNanos(Math.max(0, budgetNanos - elapsed));
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0, nanoClock.getAsLong() - startedAt) / 1_000_000;
    }

    private static long safeMillis(Duration duration) {
        try {
            return duration.toMillis();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private ProveedorIaException failure(String message) {
        return failure(message, Categoria.OPERACION_INTERNA);
    }

    private ProveedorIaException failure(String message, Categoria categoria) {
        // Only caller-generated technical messages; never response bodies, URLs or credentials.
        log.warn("Gemini rejected reason={}", message);
        return new ProveedorIaException(new IllegalStateException(message), categoria);
    }

    private ProveedorIaException failure(String message, Categoria categoria, Integer status, Duration retryAfter) {
        // Only caller-generated technical messages; never response bodies, URLs or credentials.
        log.warn("Gemini rejected reason={}", message);
        return new ProveedorIaException(new IllegalStateException(message), categoria, status, retryAfter);
    }
}
