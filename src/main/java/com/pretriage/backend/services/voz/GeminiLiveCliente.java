package com.pretriage.backend.services.voz;

import com.pretriage.backend.exceptions.ProveedorIaException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Cliente WebSocket de la Live API de Gemini (protocolo BidiGenerateContent).
 * La API key viaja en la URL y nunca se registra en logs.
 */
@Component
public class GeminiLiveCliente {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final GeminiLiveProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public GeminiLiveCliente(GeminiLiveProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
    }

    GeminiLiveCliente(GeminiLiveProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    public CompletableFuture<GeminiLiveConexion> conectar(GeminiLiveOyente oyente) {
        final URI uri;
        try {
            if (!properties.configurado()) throw new IllegalArgumentException("Gemini Live is not configured");
            uri = URI.create(properties.url() + "?key="
                    + URLEncoder.encode(properties.apiKey(), StandardCharsets.UTF_8));
            if (!("wss".equalsIgnoreCase(uri.getScheme()) || "ws".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("Gemini Live URL is invalid");
            }
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(new ProveedorIaException(
                    new IllegalStateException("Gemini Live configuration is invalid"),
                    ProveedorIaException.Categoria.CONFIGURACION));
        }
        return httpClient.newWebSocketBuilder()
                .connectTimeout(properties.connectTimeout())
                .buildAsync(uri, new Receptor(oyente))
                .handle((socket, error) -> {
                    if (error != null) throw new java.util.concurrent.CompletionException(clasificarError(error));
                    return socket;
                })
                .thenApply(ConexionJdk::new);
    }

    /** Conserva solo metadatos de protocolo; nunca propaga texto remoto ni la URL con API key. */
    static ProveedorIaException clasificarError(Throwable error) {
        List<Throwable> causas = cadenaCausas(error);
        for (Throwable actual : causas) {
            if (actual instanceof ProveedorIaException proveedor) return proveedor;
        }
        // Handshake is an IOException subclass, so inspect status-bearing failures before
        // classifying any generic I/O cause as an outage.
        for (Throwable actual : causas) if (actual instanceof WebSocketHandshakeException handshake) {
            HttpResponse<?> response = handshake.getResponse();
            int status = response.statusCode();
            Duration retryAfter = parseRetryAfter(response);
            return new ProveedorIaException(new IllegalStateException("Gemini Live handshake HTTP " + status),
                    categoriaHttp(status), status, retryAfter);
        }
        for (Throwable actual : causas) {
            if (actual instanceof SSLException || actual instanceof CertificateException
                    || actual instanceof CertPathValidatorException || actual instanceof SecurityException
                    || actual instanceof IllegalArgumentException) {
            return new ProveedorIaException(new IllegalStateException("Gemini Live configuration or TLS failure"),
                    ProveedorIaException.Categoria.CONFIGURACION);
            }
        }
        for (Throwable actual : causas) {
            if (actual instanceof IOException || actual instanceof java.util.concurrent.TimeoutException) {
            return new ProveedorIaException(new IllegalStateException("Gemini Live transport failure"),
                    ProveedorIaException.Categoria.API_NO_DISPONIBLE);
            }
        }
        return new ProveedorIaException(new IllegalStateException("Gemini Live operation failed"),
                ProveedorIaException.Categoria.OPERACION_INTERNA);
    }

    private static List<Throwable> cadenaCausas(Throwable error) {
        List<Throwable> causas = new ArrayList<>();
        Set<Throwable> vistas = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable actual = error; actual != null && vistas.add(actual); actual = actual.getCause()) causas.add(actual);
        return causas;
    }

    private static Duration parseRetryAfter(HttpResponse<?> response) {
        try {
            String value = response.headers().firstValue("Retry-After").orElse(null);
            if (value != null) {
                long seconds = Long.parseLong(value.trim());
                if (seconds >= 0) return Duration.ofSeconds(seconds);
            }
            if (value != null && !value.isBlank()) {
                var retryAt = java.time.ZonedDateTime.parse(value,
                        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration delay = Duration.between(java.time.Instant.now(), retryAt);
                return delay.isNegative() ? Duration.ZERO : delay;
            }
        } catch (RuntimeException ignored) {
            // Malformed provider header is omitted; caller applies its bounded fallback.
        }
        return null;
    }

    static ProveedorIaException.Categoria categoriaHttp(int status) {
        if (status == 408 || status == 429 || status >= 500) return ProveedorIaException.Categoria.API_NO_DISPONIBLE;
        if (status == 401 || status == 403 || status == 404) return ProveedorIaException.Categoria.CONFIGURACION;
        if (status >= 400) return ProveedorIaException.Categoria.VALIDACION_RESPUESTA;
        return ProveedorIaException.Categoria.OPERACION_INTERNA;
    }

    static ProveedorIaException clasificarMensajeJson(String mensaje) {
        try {
            JsonNode raiz = OBJECT_MAPPER.readTree(mensaje);
            if (raiz == null || !raiz.isObject()) {
                return new ProveedorIaException(new IllegalStateException("Gemini Live returned invalid JSON"),
                        ProveedorIaException.Categoria.VALIDACION_RESPUESTA);
            }
            JsonNode error = raiz.path("error");
            if (error.isMissingNode() || error.isNull()) return null;
            JsonNode code = error.path("code");
            int status = code.isInt() ? code.asInt() : parseHttpStatus(error.path("status").asText());
            String statusName = error.path("status").asText("").toUpperCase(java.util.Locale.ROOT);
            if (statusName.isBlank()) statusName = code.asText("").toUpperCase(java.util.Locale.ROOT);
            if (status == 0 && (statusName.equals("UNAVAILABLE") || statusName.equals("DEADLINE_EXCEEDED"))) status = 503;
            if (status == 0 && statusName.equals("RESOURCE_EXHAUSTED")) status = 429;
            if (status == 0 && statusName.equals("UNAUTHENTICATED")) status = 401;
            if (status == 0 && statusName.equals("PERMISSION_DENIED")) status = 403;
            if (status == 0 && (statusName.equals("INVALID_ARGUMENT") || statusName.equals("FAILED_PRECONDITION"))) status = 400;
            return new ProveedorIaException(new IllegalStateException("Gemini Live returned an error response"),
                    categoriaHttp(status), status == 0 ? null : status, null);
        } catch (tools.jackson.core.JacksonException malformed) {
            return new ProveedorIaException(new IllegalStateException("Gemini Live returned invalid JSON"),
                    ProveedorIaException.Categoria.VALIDACION_RESPUESTA);
        } catch (RuntimeException ignored) {
            return new ProveedorIaException(new IllegalStateException("Gemini Live error could not be classified"),
                    ProveedorIaException.Categoria.OPERACION_INTERNA);
        }
    }

    private static int parseHttpStatus(String status) {
        try { return Integer.parseInt(status); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static final class ConexionJdk implements GeminiLiveConexion {
        private final WebSocket webSocket;
        private CompletableFuture<?> ultimoEnvio = CompletableFuture.completedFuture(null);

        private ConexionJdk(WebSocket webSocket) {
            this.webSocket = webSocket;
        }

        @Override
        public synchronized void enviar(String mensajeJson) {
            // WebSocket del JDK no admite envios superpuestos: se encadenan.
            ultimoEnvio = ultimoEnvio
                    .exceptionally(error -> null)
                    .thenCompose(ignored -> webSocket.sendText(mensajeJson, true));
        }

        @Override
        public synchronized void cerrar() {
            ultimoEnvio = ultimoEnvio
                    .exceptionally(error -> null)
                    .thenCompose(ignored -> webSocket.isOutputClosed()
                            ? CompletableFuture.completedFuture(webSocket)
                            : webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "fin"));
        }
    }

    /** Reensambla fragmentos: Gemini envia JSON tanto en frames de texto como binarios. */
    static final class Receptor implements WebSocket.Listener {
        private final GeminiLiveOyente oyente;
        private final StringBuilder texto = new StringBuilder();
        private byte[] binario = new byte[0];

        Receptor(GeminiLiveOyente oyente) {
            this.oyente = oyente;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            texto.append(data);
            if (last) {
                String mensaje = texto.toString();
                texto.setLength(0);
                entregarMensaje(mensaje);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] fragmento = new byte[data.remaining()];
            data.get(fragmento);
            byte[] acumulado = new byte[binario.length + fragmento.length];
            System.arraycopy(binario, 0, acumulado, 0, binario.length);
            System.arraycopy(fragmento, 0, acumulado, binario.length, fragmento.length);
            binario = acumulado;
            if (last) {
                String mensaje = new String(binario, StandardCharsets.UTF_8);
                binario = new byte[0];
                entregarMensaje(mensaje);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            oyente.alCerrar(statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            oyente.alFallar(clasificarError(error));
        }

        private void entregarMensaje(String mensaje) {
            ProveedorIaException error = clasificarMensajeJson(mensaje);
            if (error != null) oyente.alFallar(error);
            else oyente.alRecibir(mensaje);
        }
    }
}
