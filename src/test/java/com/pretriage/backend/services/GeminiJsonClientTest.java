package com.pretriage.backend.services;

import com.pretriage.backend.exceptions.ProveedorIaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpHeaders;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GeminiJsonClientTest {
    @Test
    void correccionRespetaElPlazoRestanteSinAmpliarElTimeoutConfigurado() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"{}\"}]}}]}");
        when(http.<String>send(any(), any())).thenReturn(response);
        client.generar("sistema", "prueba", schema(), Duration.ofMillis(250));
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any());
        assertEquals(Duration.ofMillis(250), request.getValue().timeout().orElseThrow());
        clearInvocations(http);
        assertThrows(ProveedorIaException.class, () -> client.generar("sistema", "prueba", schema(), Duration.ZERO));
        verifyNoInteractions(http);
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpClient http;
    private HttpResponse<String> response;
    private GeminiJsonClient client;
    private AtomicLong fakeNanos;
    private List<Duration> sleeps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        http = mock(HttpClient.class);
        response = mock(HttpResponse.class);
        fakeNanos = new AtomicLong();
        sleeps = new java.util.ArrayList<>();
        client = client(properties("test-key", "gemini-3.5-flash-lite"));
    }

    private GeminiJsonClient client(GeminiChatProperties props) {
        return new GeminiJsonClient(props, mapper, http, fakeNanos::get,
                duration -> { sleeps.add(duration); fakeNanos.addAndGet(duration.toNanos()); },
                Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC), () -> 0.5);
    }

    private GeminiChatProperties properties(String key, String model) {
        return new GeminiChatProperties(key, model, "https://example.invalid/v1beta",
                Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    private Map<String, Object> schema() { return Map.of("type", "object"); }

    @Test
    void usaHeaderDeAutenticacionPlazoYUnaSolaPeticion() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"candidates":[{"finishReason":"STOP","content":{"parts":[
                {"thought":true,"text":"internal"},{"text":"{\\\"ok\\\":"},{"text":"true}"}]}}]}
                """);
        when(http.<String>send(any(), any())).thenReturn(response);
        assertEquals("{\"ok\":true}", client.generar("sistema", "prueba", schema()));
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(1)).send(request.capture(), any());
        assertEquals("test-key", request.getValue().headers().firstValue("x-goog-api-key").orElseThrow());
        assertFalse(request.getValue().uri().toString().contains("test-key"));
        assertEquals(Duration.ofSeconds(2), request.getValue().timeout().orElseThrow());
        assertEquals("POST", request.getValue().method());
        var subscriber = HttpResponse.BodySubscribers.ofString(java.nio.charset.StandardCharsets.UTF_8);
        request.getValue().bodyPublisher().orElseThrow().subscribe(
                new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                    public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                        subscriber.onSubscribe(subscription);
                    }
                    public void onNext(java.nio.ByteBuffer buffer) { subscriber.onNext(java.util.List.of(buffer)); }
                    public void onError(Throwable error) { subscriber.onError(error); }
                    public void onComplete() { subscriber.onComplete(); }
                });
        var body = mapper.readTree(subscriber.getBody().toCompletableFuture().join());
        var format = body.path("generationConfig").path("responseFormat").path("text");
        assertEquals(8192, body.path("generationConfig").path("maxOutputTokens").intValue());
        assertEquals("APPLICATION_JSON", format.path("mimeType").asText());
        assertEquals(mapper.valueToTree(schema()), format.path("schema"));
        assertEquals("prueba", body.path("contents").get(0).path("parts").get(0).path("text").asText());
    }

    @Test
    void cuotaAgotadaNoReintentaNiExponeElCuerpo() throws Exception {
        when(response.statusCode()).thenReturn(429);
        when(http.<String>send(any(), any())).thenReturn(response);
        var error = assertThrows(ProveedorIaException.class, () -> client.generar("sistema", "prueba", schema()));
        assertTrue(error.getCause().getMessage().contains("429"));
        verify(response, never()).body();
        verify(http, times(1)).send(any(), any());
    }

    @Test
    void status429ConRetryAfterInvalidoONegativoNoReintenta() throws Exception {
        for (String retryAfter : new String[]{"mañana", "-1"}) {
            clearInvocations(http);
            sleeps.clear();
            var rateLimited = response(429, Map.of("Retry-After", List.of(retryAfter)));
            when(http.<String>send(any(), any())).thenReturn(rateLimited);
            assertThrows(ProveedorIaException.class, () -> client.generar("sistema", "prueba", schema()));
            verify(http, times(1)).send(any(), any());
            assertTrue(sleeps.isEmpty());
        }
    }

    @Test
    void rechazaRespuestasTruncadasBloqueadasOVacias() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(http.<String>send(any(), any())).thenReturn(response);
        for (String body : new String[]{"{}", "{\"candidates\":[]}",
                "{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\"}]}",
                "{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}",
                "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[]}}]}"}) {
            when(response.body()).thenReturn(body);
            assertThrows(ProveedorIaException.class, () -> client.generar("sistema", "prueba", schema()));
        }
    }

    @Test
    void terminacionIncompletaDistingueLimiteDeTokensSinAceptarJsonParcial() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"candidates":[{"finishReason":"MAX_TOKENS","finishMessage":"dato clinico privado",
                "content":{"parts":[{"text":"{\\\"parcial\\\":true}"}]}}],
                "usageMetadata":{"candidatesTokenCount":8100,"thoughtsTokenCount":92}}
                """);
        when(http.<String>send(any(), any())).thenReturn(response);
        var error = assertThrows(ProveedorIaException.class, () -> client.generar("sistema", "prueba", schema()));
        assertTrue(error.getCause().getMessage().contains("finishReason=MAX_TOKENS"));
        assertTrue(error.getCause().getMessage().contains("outputTokens=8100"));
        assertTrue(error.getCause().getMessage().contains("thoughtTokens=92"));
        assertFalse(error.getCause().getMessage().contains("privado"));
        assertFalse(error.getCause().getMessage().contains("parcial"));
        verify(http, times(1)).send(any(), any());
    }

    @Test
    void razonDeCierreDesconocidaNoExponeTextoArbitrarioDelProveedor() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"candidates\":[{\"finishReason\":\"texto privado\"}]}");
        when(http.<String>send(any(), any())).thenReturn(response);
        var error = assertThrows(ProveedorIaException.class, () -> client.generar("sistema", "prueba", schema()));
        assertTrue(error.getCause().getMessage().contains("finishReason=UNKNOWN"));
        assertFalse(error.getCause().getMessage().contains("privado"));
    }

    @Test
    void timeoutTransitorioReintentaYDevuelveLaRespuestaSiguiente() throws Exception {
        var success = mock(HttpResponse.class);
        when(success.statusCode()).thenReturn(200);
        when(success.body()).thenReturn("{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"{}\"}]}}]}");
        when(http.<String>send(any(), any())).thenThrow(new HttpTimeoutException("private timeout detail"))
                .thenReturn(success);
        assertEquals("{}", client.generar("sistema", "prueba", schema()));
        verify(http, times(2)).send(any(), any());
        assertEquals(Duration.ofMillis(500), sleeps.getFirst());
    }

    @Test
    void status503ReintentaConBackoffYRecuperaLaRespuesta() throws Exception {
        var unavailable = response(503, Map.of());
        var success = response(200, Map.of());
        when(success.body()).thenReturn("{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"{}\"}]}}]}");
        when(http.<String>send(any(), any())).thenReturn(unavailable, success);
        var limited = client(new GeminiChatProperties("test-key", "gemini-3.5-flash-lite",
                "https://example.invalid/v1beta", Duration.ofSeconds(1), Duration.ofSeconds(3), 8192,
                2, Duration.ofSeconds(1), Duration.ofMillis(100)));
        assertEquals("{}", limited.generar("sistema", "prueba", schema()));
        verify(http, times(2)).send(any(), any());
        assertEquals(1, sleeps.size());
        assertEquals(Duration.ofMillis(100), sleeps.getFirst());
    }

    @Test
    void status503AgotaElMaximoDeIntentos() throws Exception {
        var unavailable = response(503, Map.of());
        when(http.<String>send(any(), any())).thenReturn(unavailable);
        var limited = client(new GeminiChatProperties("test-key", "gemini-3.5-flash-lite",
                "https://example.invalid/v1beta", Duration.ofSeconds(1), Duration.ofSeconds(3), 8192,
                2, Duration.ofSeconds(1), Duration.ofMillis(100)));
        var error = assertThrows(ProveedorIaException.class,
                () -> limited.generar("sistema", "prueba", schema()));
        assertTrue(error.getCause().getMessage().contains("503"));
        verify(http, times(2)).send(any(), any());
        assertEquals(1, sleeps.size());
    }

    @Test
    void segundoTimeoutSeRecortaAlPresupuestoRestante() throws Exception {
        var requests = new java.util.ArrayList<HttpRequest>();
        var unavailable = response(503, Map.of());
        var success = response(200, Map.of());
        when(success.body()).thenReturn("{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"{}\"}]}}]}");
        when(http.<String>send(any(), any())).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            requests.add(request);
            fakeNanos.addAndGet(request.timeout().orElseThrow().toNanos());
            return requests.size() == 1 ? unavailable : success;
        });
        var bounded = client(new GeminiChatProperties("test-key", "gemini-3.5-flash-lite",
                "https://example.invalid/v1beta", Duration.ofSeconds(1), Duration.ofSeconds(30), 8192,
                2, Duration.ofSeconds(20), Duration.ofMillis(500)));

        assertEquals("{}", bounded.generar("sistema", "prueba", schema()));
        assertEquals(2, requests.size());
        assertEquals(Duration.ofSeconds(20), requests.get(0).timeout().orElseThrow());
        assertEquals(Duration.ofMillis(9500), requests.get(1).timeout().orElseThrow());
        assertEquals(Duration.ofSeconds(30), Duration.ofNanos(fakeNanos.get()));
    }

    @Test
    void retryAfter429SeRespetaYSeOmiteCuandoNoEntraEnElPlazo() throws Exception {
        var limited = client(new GeminiChatProperties("test-key", "gemini-3.5-flash-lite",
                "https://example.invalid/v1beta", Duration.ofSeconds(1), Duration.ofSeconds(3), 8192,
                2, Duration.ofSeconds(1), Duration.ofMillis(50)));
        var rateLimited = response(429, Map.of("Retry-After", List.of("1")));
        var success = response(200, Map.of());
        when(success.body()).thenReturn("{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"{}\"}]}}]}");
        when(http.<String>send(any(), any())).thenReturn(rateLimited, success);
        assertEquals("{}", limited.generar("sistema", "prueba", schema()));
        verify(http, times(2)).send(any(), any());
        assertEquals(Duration.ofSeconds(1), sleeps.getFirst());

        clearInvocations(http);
        sleeps.clear();
        var noTime = client(new GeminiChatProperties("test-key", "gemini-3.5-flash-lite",
                "https://example.invalid/v1beta", Duration.ofSeconds(1), Duration.ofMillis(600), 8192,
                2, Duration.ofMillis(500), Duration.ofMillis(50)));
        var lateRateLimit = response(429, Map.of("Retry-After", List.of("1")));
        when(http.<String>send(any(), any())).thenReturn(lateRateLimit);
        assertThrows(ProveedorIaException.class, () -> noTime.generar("sistema", "prueba", schema()));
        verify(http, times(1)).send(any(), any());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void errorAuthSinReintentoYErrorSemanticoSafetySinReintento() throws Exception {
        var unauthorized = response(401, Map.of());
        when(http.<String>send(any(), any())).thenReturn(unauthorized);
        assertThrows(ProveedorIaException.class, () -> client.generar("sistema", "prueba", schema()));
        verify(http, times(1)).send(any(), any());

        clearInvocations(http);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}");
        when(http.<String>send(any(), any())).thenReturn(response);
        assertThrows(ProveedorIaException.class, () -> client.generar("sistema", "dato clinico privado", schema()));
        verify(http, times(1)).send(any(), any());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void fechaHttpEnRetryAfterEsRespetada() throws Exception {
        var limited = client(new GeminiChatProperties("test-key", "gemini-3.5-flash-lite",
                "https://example.invalid/v1beta", Duration.ofSeconds(1), Duration.ofSeconds(4), 8192,
                2, Duration.ofSeconds(2), Duration.ofMillis(10)));
        var datedUnavailable = response(503, Map.of("Retry-After", List.of("Wed, 21 Oct 2026 12:00:02 GMT")));
        var unavailable = response(503, Map.of());
        when(http.<String>send(any(), any())).thenReturn(datedUnavailable, unavailable);
        assertThrows(ProveedorIaException.class, () -> limited.generar("sistema", "prueba", schema()));
        assertEquals(Duration.ofSeconds(2), sleeps.getFirst());
    }

    @Test
    void interrupcionDuranteElBackoffSePropagaYNoIntentaDeNuevo() throws Exception {
        var unavailable = response(503, Map.of());
        when(http.<String>send(any(), any())).thenReturn(unavailable);
        var interrupted = new GeminiJsonClient(properties("test-key", "gemini-3.5-flash-lite"), mapper, http,
                fakeNanos::get, duration -> { throw new InterruptedException(); },
                Clock.systemUTC(), () -> 0.5);
        assertThrows(ProveedorIaException.class, () -> interrupted.generar("sistema", "prueba", schema()));
        assertTrue(Thread.currentThread().isInterrupted());
        verify(http, times(1)).send(any(), any());
        Thread.interrupted();
    }

    @Test
    void erroresTlsDirectosYEnLaCadenaDeCausasNoSeReintentan() throws Exception {
        for (IOException tlsError : new IOException[]{
                new SSLHandshakeException("certificate detail"),
                new IOException("transport wrapper", new SSLException("certificate detail"))}) {
            reset(http);
            when(http.<String>send(any(), any())).thenThrow(tlsError);
            var error = assertThrows(ProveedorIaException.class,
                    () -> client.generar("sistema", "prueba", schema()));
            assertFalse(error.getCause().getMessage().contains("certificate detail"));
            verify(http, times(1)).send(any(), any());
        }
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, Map<String, List<String>> headers) {
        HttpResponse<String> result = mock(HttpResponse.class);
        when(result.statusCode()).thenReturn(status);
        when(result.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        return result;
    }

    @Test
    void configuracionInvalidaNoEnviaPeticiones() {
        for (GeminiChatProperties properties : new GeminiChatProperties[]{
                properties("", "gemini-3.5-flash-lite"), properties("test-key", "invalid/model")}) {
            var invalid = new GeminiJsonClient(properties, mapper, http);
            assertThrows(ProveedorIaException.class, () -> invalid.generar("sistema", "prueba", schema()));
        }
        verifyNoInteractions(http);
    }

    @Test
    void interrupcionRestauraLaBanderaDelHilo() throws Exception {
        when(http.<String>send(any(), any())).thenThrow(new InterruptedException());
        try {
            assertThrows(ProveedorIaException.class, () -> client.generar("sistema", "prueba", schema()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
